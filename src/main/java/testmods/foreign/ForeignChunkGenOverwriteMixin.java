package testmods.foreign;

import com.mojang.datafixers.util.Either;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import testmods.modlikestate.ModLikeState;

/**
 * Issue #16 test probe (-Dsleep.test.overwriteChunkGen): stand-in for ByePregen's @Overwrite of ChunkStatus.generate.
 * Also checks whether two FEATURES steps with overlapping 3x3 write windows run concurrently.
 */
@Mixin(ChunkStatus.class)
public abstract class ForeignChunkGenOverwriteMixin {
    @Unique private static Field sleep$taskField;
    @Unique private static Method sleep$doWork;

    @Unique
    @SuppressWarnings("unchecked")
    private static CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> sleep$runTask(
            ChunkStatus self, Executor executor, ServerLevel level, ChunkGenerator generator,
            StructureTemplateManager templates, ThreadedLevelLightEngine light,
            Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> task,
            List<ChunkAccess> cache, ChunkAccess center) {
        try {
            if (sleep$doWork == null) {
                for (Field f : ChunkStatus.class.getDeclaredFields()) {
                    if (f.getType().getName().endsWith("$GenerationTask")) {
                        f.setAccessible(true);
                        Method m = f.getType().getDeclaredMethods()[0];
                        m.setAccessible(true);
                        sleep$taskField = f;
                        sleep$doWork = m;
                        break;
                    }
                }
            }
            Object genTask = sleep$taskField.get(self);
            return (CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>)
                    sleep$doWork.invoke(genTask, self, executor, level, generator, templates, light, task, cache, center);
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) throw r;
            if (c instanceof Error err) throw err;
            throw new RuntimeException(e);
        }
    }

    /**
     * @author test
     * @reason stand-in for another mod overwriting ChunkStatus.generate (issue #16: ByePregen)
     */
    @Overwrite
    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> generate(
            Executor executor, ServerLevel level, ChunkGenerator generator, StructureTemplateManager templates,
            ThreadedLevelLightEngine light,
            Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> task,
            List<ChunkAccess> cache) {
        ChunkStatus self = (ChunkStatus) (Object) this;
        ChunkAccess center = cache.get(cache.size() / 2);
        ChunkPos pos = center.getPos();
        boolean features = self == ChunkStatus.FEATURES;
        if (features) ModLikeState.enterFeatures(pos.x, pos.z);
        try {
            ProfiledDuration duration = JvmProfiler.INSTANCE.onChunkGenerate(pos, level.dimension(), this.toString());
            return sleep$runTask(self, executor, level, generator, templates, light, task, cache, center).thenApply(either -> {
                either.ifLeft(chunk -> {
                    if (chunk instanceof ProtoChunk proto && !proto.getStatus().isOrAfter(self)) proto.setStatus(self);
                });
                if (duration != null) duration.finish();
                return either;
            });
        } finally {
            if (features) ModLikeState.leaveFeatures(pos.x, pos.z);
        }
    }
}
