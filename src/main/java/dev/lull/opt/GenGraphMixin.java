package dev.lull.opt;

import dev.lull.optsupport.GenGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GenGraph (-Dlab.opt.GenGraph): ChunkMap.runGenerationTask hands the task to the generation graph (see GenGraph)
 * instead of the `worldgen` mailbox. Also the gate the graph checks before a node runs: pool work joins a region's
 * old-chunk scan (Blender.of, radius 7 around the chunk) and the stronghold ring positions - a node whose futures are
 * not done waits for them outside the pool (as WorldgenReactor's gate did for a whole task).
 */
@Mixin(ChunkMap.class)
public abstract class GenGraphMixin implements GenGraph.Gate {
    @Shadow @Final private ChunkGeneratorStructureState chunkGeneratorState;
    @Unique private volatile boolean sleep$ringsReady;

    /**
     * @author probe
     * @reason generation as a dependency graph (see GenGraph)
     */
    @Overwrite
    private void runGenerationTask(ChunkGenerationTask task) {
        GenGraph.start((ChunkMap) (Object) this, task);
    }

    /** cancellations are applied after this tick's new tasks attached to the nodes they share */
    @Inject(method = "runGenerationTasks", at = @At("TAIL"))
    private void sleep$cancels(CallbackInfo ci) {
        GenGraph.processCancels();
    }

    @Override
    public CompletableFuture<?> sleep$gateFor(ChunkPos c) {
        List<CompletableFuture<?>> wait = null;
        if (!sleep$ringsReady) {
            for (CompletableFuture<?> f : ((ReactorRingsAccessor) this.chunkGeneratorState).sleep$rings().values())
                if (!f.isDone()) (wait = wait == null ? new ArrayList<>() : wait).add(f);
            if (wait == null) sleep$ringsReady = true;
        }
        if ((Object) this instanceof ReactorStorageAccessor sa && sa.sleep$worker() instanceof ReactorIOWorkerInvoker io) {
            int r = 8;
            int rx0 = (c.x - r) >> 5, rx1 = (c.x + r) >> 5, rz0 = (c.z - r) >> 5, rz1 = (c.z + r) >> 5;
            for (int rx = rx0; rx <= rx1; rx++)
                for (int rz = rz0; rz <= rz1; rz++) {
                    CompletableFuture<?> f = io.sleep$oldData(rx, rz);
                    if (!f.isDone()) (wait = wait == null ? new ArrayList<>() : wait).add(f);
                }
        }
        return wait == null ? null : CompletableFuture.allOf(wait.toArray(new CompletableFuture[0]));
    }
}
