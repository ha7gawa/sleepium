package testmods.foreign;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import testmods.modlikestate.ModLikeState;

/**
 * Issue #16 test probe (-Dsleep.test.overwriteChunkGen): stand-in for another mod overwriting ChunkMap.applyStep.
 * Also checks whether two FEATURES steps with overlapping 3x3 write windows run concurrently.
 */
@Mixin(ChunkMap.class)
public abstract class ForeignChunkGenOverwriteMixin {
    @Shadow @Final private WorldGenContext worldGenContext;
    @Shadow @Final private ChunkProgressListener progressListener;
    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow
    private CompletableFuture<ChunkAccess> scheduleChunkLoad(ChunkPos pos) {
        throw new AssertionError();
    }

    @Shadow
    protected abstract ChunkGenerator generator();

    /**
     * @author test
     * @reason stand-in for another mod overwriting ChunkMap.applyStep (issue #16)
     */
    @Overwrite
    public CompletableFuture<ChunkAccess> applyStep(GenerationChunkHolder chunk, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache) {
        ChunkPos chunkpos = chunk.getPos();
        if (step.targetStatus() == ChunkStatus.EMPTY) {
            return this.scheduleChunkLoad(chunkpos);
        }
        boolean features = step.targetStatus() == ChunkStatus.FEATURES;
        if (features) ModLikeState.enterFeatures(chunkpos.x, chunkpos.z);
        try {
            GenerationChunkHolder generationchunkholder = cache.get(chunkpos.x, chunkpos.z);
            ChunkAccess chunkaccess = generationchunkholder.getChunkIfPresentUnchecked(step.targetStatus().getParent());
            if (chunkaccess == null) throw new IllegalStateException("Parent chunk missing");
            CompletableFuture<ChunkAccess> completablefuture = step.apply(this.worldGenContext, cache, chunkaccess);
            this.progressListener.onStatusChange(chunkpos, step.targetStatus());
            return completablefuture;
        } catch (Exception exception) {
            exception.getStackTrace();
            CrashReport crashreport = CrashReport.forThrowable(exception, "Exception generating new chunk");
            CrashReportCategory crashreportcategory = crashreport.addCategory("Chunk to be generated");
            crashreportcategory.setDetail("Status being generated", () -> step.targetStatus().getName());
            crashreportcategory.setDetail("Location", String.format(Locale.ROOT, "%d,%d", chunkpos.x, chunkpos.z));
            crashreportcategory.setDetail("Position hash", ChunkPos.asLong(chunkpos.x, chunkpos.z));
            crashreportcategory.setDetail("Generator", this.generator());
            this.mainThreadExecutor.execute(() -> {
                throw new ReportedException(crashreport);
            });
            throw new ReportedException(crashreport);
        } finally {
            if (features) ModLikeState.leaveFeatures(chunkpos.x, chunkpos.z);
        }
    }
}
