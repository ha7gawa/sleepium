package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FullRing;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * WorldgenReactor: ServerChunkCache.pollTask is the server's idle-loop entry (MinecraftServer.pollTaskInternal, only
 * while it has time): FullRing's window and deadline apply there.
 */
@Mixin(ServerChunkCache.class)
public abstract class ReactorMainDrainMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final public net.minecraft.server.level.ChunkMap chunkMap;

    @WrapMethod(method = "pollTask")
    private boolean sleep$idle(Operation<Boolean> original) {
        FullRing.idle = true;
        try {
            if ((Object) this.chunkMap instanceof dev.lull.optsupport.ChunkLoadMarks m) m.sleep$drainMarks();
            return original.call();
        } finally {
            FullRing.idle = false;
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$marks(java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if ((Object) this.chunkMap instanceof dev.lull.optsupport.ChunkLoadMarks m) m.sleep$drainMarks();
    }
}
