package dev.lull.opt;

import dev.lull.optsupport.TrackFast;
import java.util.Set;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** lab.opt.TrackFast: the tracker's per-pair check with the distance first (optsupport/TrackFast). */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackFastMixin {
    @Shadow @Final ServerEntity serverEntity;
    @Shadow @Final Entity entity;
    @Shadow @Final private int range;
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;
    @Shadow(remap = false) @Final ChunkMap this$0;

    @Shadow
    private int getEffectiveRange() {
        throw new AssertionError();
    }

    @Shadow
    private int scaledRange(int trackingDistance) {
        throw new AssertionError();
    }

    /**
     * @author sleepium (lab TrackFast)
     * @reason vanilla's decision with the distance against an upper bound of its range first; same results
     */
    @Overwrite
    public void updatePlayer(ServerPlayer player) {
        if (player == this.entity) return;
        int view = ((TrackFastChunkMapAccessor) this$0).sleep$viewDistance();
        if (!TrackFast.active) {   // vanilla's body (A/B windows)
            Vec3 vec3 = player.position().subtract(this.entity.position());
            double v0 = (double) Math.min(this.getEffectiveRange(), view * 16);
            double v1 = vec3.x * vec3.x + vec3.z * vec3.z;
            if (v1 <= v0 * v0 && this.entity.broadcastToPlayer(player)) {
                if (this.seenBy.add(player.connection)) this.serverEntity.addPairing(player);
            } else if (this.seenBy.remove(player.connection)) {
                this.serverEntity.removePairing(player);
            }
            return;
        }
        double dx = player.getX() - this.entity.getX(), dz = player.getZ() - this.entity.getZ();   // vanilla's Vec3 subtract, x and z
        double d1 = dx * dx + dz * dz;
        double cap = (double) (view * 16);
        boolean flag;
        if (d1 > cap * cap) {   // beyond min(range, view * 16) whatever the range: vanilla's d1 <= d2 is false
            flag = false;
            TrackFast.far++;
        } else {
            int eff = TrackFast.noPassengers(this.entity, ((TrackFastEntityAccessor) this.entity).sleep$passengers()) ? this.scaledRange(this.range) : this.getEffectiveRange();
            double d0 = (double) Math.min(eff, view * 16);
            flag = d1 <= d0 * d0 && this.entity.broadcastToPlayer(player);
            TrackFast.near++;
        }
        if (TrackFast.VERIFY) {   // vanilla's expression as written
            Vec3 vec3 = player.position().subtract(this.entity.position());
            double v0 = (double) Math.min(this.getEffectiveRange(), view * 16);
            double v1 = vec3.x * vec3.x + vec3.z * vec3.z;
            boolean v = v1 <= v0 * v0 && this.entity.broadcastToPlayer(player);
            TrackFast.CHECKED.increment();
            if (v != flag) TrackFast.MISMATCH.increment();
            TrackFast.maybeReport();
        }
        if (flag) {
            if (this.seenBy.add(player.connection)) this.serverEntity.addPairing(player);
        } else if (this.seenBy.remove(player.connection)) {
            this.serverEntity.removePairing(player);
        }
    }
}
