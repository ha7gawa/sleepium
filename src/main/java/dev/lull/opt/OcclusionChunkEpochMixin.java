package dev.lull.opt;

import dev.lull.optsupport.OcclusionFast;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OcclusionFast: which chunks the client holds - a chunk arriving or dropped stamps that chunk; the view centre or
 * radius moving (which chunks are in range) and a new level's cache move the view epoch. The occlusion walk keeps a
 * column's neighbour-chunk answer while none of its four neighbours was stamped and the view epoch stands. Stamped
 * before and after the change: an answer worked out while it ran is stale at once.
 */
@Mixin(ClientChunkCache.class)
public abstract class OcclusionChunkEpochMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void sleep$epochNew(CallbackInfo ci) {
        OcclusionFast.viewChanged();
    }

    @Inject(method = {"updateViewCenter", "updateViewRadius"}, at = {@At("HEAD"), @At("RETURN")})
    private void sleep$epochView(CallbackInfo ci) {
        OcclusionFast.viewChanged();
    }

    @Inject(method = "drop", at = {@At("HEAD"), @At("RETURN")})
    private void sleep$stampDrop(ChunkPos pos, CallbackInfo ci) {
        OcclusionFast.chunkChanged(pos.x, pos.z);
    }

    @Inject(method = "replaceWithPacketData", at = {@At("HEAD"), @At("RETURN")})
    private void sleep$stampLoad(int x, int z, net.minecraft.network.FriendlyByteBuf buf, net.minecraft.nbt.CompoundTag tag,
                                 java.util.function.Consumer<?> out, CallbackInfoReturnable<?> cir) {
        OcclusionFast.chunkChanged(x, z);
    }
}
