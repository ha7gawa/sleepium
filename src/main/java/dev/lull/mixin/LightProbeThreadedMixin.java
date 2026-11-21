package dev.lull.mixin;

import dev.lull.LightProbe;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Measurement only (-Dlab.light.probe=true): the light actor's batches (pre / BFS / post), its queue, its futures' latency. */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class LightProbeThreadedMixin implements LightProbe.Engine {
    @Shadow @Final private ObjectList<?> lightTasks;
    @Unique private long sleep$t0, sleep$t1, sleep$t2;
    @Unique private int sleep$n, sleep$q;

    @Override
    public int sleep$pendingTasks() {
        return lightTasks.size();
    }

    @Inject(method = "runUpdate", at = @At("HEAD"))
    private void sleep$head(CallbackInfo ci) {
        sleep$q = lightTasks.size();
        sleep$n = Math.min(sleep$q, 1000);
        sleep$t0 = System.nanoTime();
    }

    @Inject(method = "runUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
    private void sleep$bfs(CallbackInfo ci) {
        sleep$t1 = System.nanoTime();
    }

    @Inject(method = "runUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I", shift = At.Shift.AFTER))
    private void sleep$bfsDone(CallbackInfo ci) {
        sleep$t2 = System.nanoTime();
    }

    @Inject(method = "runUpdate", at = @At("RETURN"))
    private void sleep$ret(CallbackInfo ci) {
        long t3 = System.nanoTime();
        LightProbe.batch(sleep$t1 - sleep$t0, sleep$t2 - sleep$t1, t3 - sleep$t2, sleep$n, sleep$q);
    }

    @Inject(method = "initializeLight", at = @At("RETURN"))
    private void sleep$init(ChunkAccess chunk, boolean lit, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LightProbe.call(0);
        long t = System.nanoTime();
        cir.getReturnValue().whenComplete((c, e) -> LightProbe.latency(LightProbe.L_INIT, System.nanoTime() - t));
    }

    @Inject(method = "lightChunk", at = @At("RETURN"))
    private void sleep$light(ChunkAccess chunk, boolean lit, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LightProbe.call(1);
        long t = System.nanoTime();
        cir.getReturnValue().whenComplete((c, e) -> LightProbe.latency(LightProbe.L_LIGHT, System.nanoTime() - t));
    }

    @Inject(method = "waitForPendingTasks", at = @At("RETURN"))
    private void sleep$wait(int x, int z, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        LightProbe.call(3);
        long t = System.nanoTime();
        cir.getReturnValue().whenComplete((c, e) -> LightProbe.latency(LightProbe.L_WAIT, System.nanoTime() - t));
    }

    @Inject(method = "checkBlock", at = @At("HEAD"))
    private void sleep$check(BlockPos pos, CallbackInfo ci) {
        LightProbe.call(2);
    }

    @Inject(method = "updateSectionStatus", at = @At("HEAD"))
    private void sleep$status(SectionPos pos, boolean empty, CallbackInfo ci) {
        LightProbe.call(4);
    }
}
