package dev.lull.mixin;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ServeMeter (notes/92, -Dsleep.serveMeter=true, measurement only): a packet's first call here is on the network
 * thread (it is queued for the server thread), its second one is its handler's start on the server thread.
 */
@Mixin(PacketUtils.class)
public abstract class ServeMeterPacketMixin {
    @Inject(method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            at = @At("HEAD"))
    private static void sleep$serve(Packet<?> packet, PacketListener processor, BlockableEventLoop<?> executor, CallbackInfo ci) {
        if (!(executor instanceof net.minecraft.server.MinecraftServer)) return;
        if (executor.isSameThread()) dev.lull.ServeMeter.handled(packet);
        else dev.lull.ServeMeter.queued(packet);
    }
}
