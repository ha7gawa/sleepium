package dev.lull.opt;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dlab.chunkChurn (measuring): chunks the client receives and is told to forget (ChunkChurn). */
@Mixin(ClientChunkCache.class)
public abstract class ChunkChurnMixin {
    @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
    private void sleep$received(int x, int z, net.minecraft.network.FriendlyByteBuf buf, net.minecraft.nbt.CompoundTag tag,
            java.util.function.Consumer<?> consumer, CallbackInfoReturnable<?> cir) {
        dev.lull.optsupport.ChunkChurn.received(x, z);
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void sleep$dropped(ChunkPos pos, CallbackInfo ci) {
        dev.lull.optsupport.ChunkChurn.dropped(pos.x, pos.z);
    }
}
