package dev.lull.opt;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * lab (TrackPar, -Dlab.opt.TrackParMixin=true): the tracker's first loop done by TrackPar (vanilla's loop then walks
 * nothing); the players that changed section handed to vanilla's second loop.
 */
@Mixin(ChunkMap.class)
public abstract class TrackParChunkMapMixin {
    @Shadow @Final ServerLevel level;

    @Shadow
    public abstract net.minecraft.server.level.DistanceManager getDistanceManager();

    @Unique
    private boolean sleep$tp;

    @Redirect(method = "tick()V", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;", ordinal = 0))
    private ObjectCollection<?> sleep$trackPar(Int2ObjectMap<?> map) {
        ObjectCollection<?> v = map.values();
        sleep$tp = dev.lull.optsupport.TrackPar.run(v, level, getDistanceManager());
        return sleep$tp ? ObjectLists.emptyList() : v;
    }

    @ModifyVariable(method = "tick()V", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z", ordinal = 0), ordinal = 0)
    private List<ServerPlayer> sleep$moved(List<ServerPlayer> list) {
        if (sleep$tp) {
            list.addAll(dev.lull.optsupport.TrackPar.moved());
            sleep$tp = false;
        }
        return list;
    }
}
