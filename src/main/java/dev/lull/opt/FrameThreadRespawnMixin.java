package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FrameThread: no frame drawn while the main thread handles a respawn or a login - the level, the player and the camera
 * entity are replaced one after another there (the camera entity null for a while: a frame's HUD vignette threw on it,
 * 1.20.1 nether trip 11-17). The level change's own hold (FrameReactor LEVEL_CHANGE) ends at the next frame handed over,
 * and 1.20.1's setLevel hands one over itself (its progress screen's runTick) before the player is replaced. (On the
 * network thread the handler only queues itself: holdForMain is the main thread's alone.)
 */
@Mixin(ClientPacketListener.class)
public abstract class FrameThreadRespawnMixin {
    @WrapMethod(method = "handleRespawn")
    private void sleep$respawn(ClientboundRespawnPacket packet, Operation<Void> original) {
        FrameThread.holdForMain();
        try {
            original.call(packet);
        } finally {
            FrameThread.releaseForMain();
        }
    }

    @WrapMethod(method = "handleLogin")
    private void sleep$login(ClientboundLoginPacket packet, Operation<Void> original) {
        FrameThread.holdForMain();
        try {
            original.call(packet);
        } finally {
            FrameThread.releaseForMain();
        }
    }
}
