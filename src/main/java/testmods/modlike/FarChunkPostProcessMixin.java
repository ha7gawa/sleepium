package testmods.modlike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test (-Dsleep.test.farChunk, issue #15): a mod reading a chunk farther than the ticking neighbourhood while a new chunk
 * is post-processed - Sable's physics does (LevelAccelerator.getChunk from a block change in a fluid tick). The main
 * thread then waits for that chunk (managedBlock) inside a task of the idle loop.
 */
@Mixin(LevelChunk.class)
public abstract class FarChunkPostProcessMixin {
    @Inject(method = "postProcessGeneration", at = @At("HEAD"))
    private void sleep$farRead(CallbackInfo ci) {
        LevelChunk c = (LevelChunk) (Object) this;
        if (c.getLevel() instanceof ServerLevel level) {
            int x = c.getPos().x + 3, z = c.getPos().z;
            level.getChunk(x, z);   // FULL, loading / generating it if needed
            if (FARREADS++ == 0) System.out.println("[sleep/test] far chunk read during post-processing (issue #15 hook)");
        }
    }

    private static int FARREADS;
}
