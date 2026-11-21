package dev.lull.opt;

import net.minecraft.client.renderer.SectionBufferBuilderPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * -Dlab.meshThreads=N (11-18): at most N chunk sections meshed at once. Vanilla gives the section dispatcher one buffer
 * pack per processor (16 here) and meshes on the shared background pool (processors - 1 threads): a burst of chunks
 * arriving in flight takes every core, competing with the frame and the integrated server. A mesh holds a pack while it
 * is built; with N packs no more than N meshes run.
 */
@Mixin(SectionBufferBuilderPool.class)
public abstract class MeshThreadsMixin {
    @ModifyVariable(method = "allocate", at = @At("HEAD"), argsOnly = true)
    private static int sleep$packs(int bufferCount) {
        int n = Integer.getInteger("lab.meshThreads", 0);
        if (n <= 0) return bufferCount;
        System.out.println("[sleep/opt] mesh threads: " + Math.min(n, bufferCount) + " section meshes at once (vanilla " + bufferCount + ")");
        return Math.min(n, bufferCount);
    }
}
