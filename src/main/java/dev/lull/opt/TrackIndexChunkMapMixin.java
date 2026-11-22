package dev.lull.opt;

import dev.lull.optsupport.TrackIndex;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** lab.opt.TrackIndex: ChunkMap.move's loop over the entities whose check can change something (optsupport/TrackIndex). */
@Mixin(ChunkMap.class)
public abstract class TrackIndexChunkMapMixin {
    @SuppressWarnings("rawtypes")
    @Shadow @Final private Int2ObjectMap entityMap;

    @Shadow
    abstract int getPlayerViewDistance(ServerPlayer player);

    @SuppressWarnings("rawtypes")
    @Redirect(method = "move", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;", ordinal = 0))
    private ObjectCollection sleep$candidates(Int2ObjectMap map, ServerPlayer player) {
        return TrackIndex.candidates(map, player, this.getPlayerViewDistance(player));
    }

    @Inject(method = "addEntity", at = @At("HEAD"))
    private void sleep$added(Entity entity, CallbackInfo ci) {
        TrackIndex.stale = true;
    }

    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void sleep$removed(Entity entity, CallbackInfo ci) {
        TrackIndex.removed(this.entityMap.get(entity.getId()), entity);
    }
}
