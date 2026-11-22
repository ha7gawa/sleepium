package dev.lull.opt;

import dev.lull.optsupport.TrackFast;
import dev.lull.optsupport.TrackIndex;
import java.util.Set;
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
public abstract class TrackFastMixin implements TrackIndex.Tracked {
    @Shadow @Final ServerEntity serverEntity;
    @Shadow @Final Entity entity;
    @Shadow @Final private int range;
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;

    @Override
    public Entity sleep$entity() {
        return this.entity;
    }

    @Override
    public Set<ServerPlayerConnection> sleep$seenBy() {
        return this.seenBy;
    }

    /** TrackIndex's reverse sets follow every change of seenBy */
    @org.spongepowered.asm.mixin.injection.Inject(method = "removePlayer", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void sleep$removePlayer(ServerPlayer player, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (TrackIndex.ON) TrackIndex.seenRemove(player.connection, this.entity.getId());
    }

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
        // the tracker's own ChunkMap (vanilla's ChunkMap.this) through its entity's level - not the synthetic this$0 (the
        // 1.20.1 production jar does not carry it under that name; one code for both)
        TrackFastChunkMapInvoker map = (TrackFastChunkMapInvoker) ((net.minecraft.server.level.ServerLevel) this.entity.level()).getChunkSource().chunkMap;
        int i = map.sleep$viewDistance(player);
        if (!TrackFast.active) {   // vanilla's body (A/B windows)
            Vec3 vec3 = player.position().subtract(this.entity.position());
            double v0 = (double) Math.min(this.getEffectiveRange(), i * 16);
            double v1 = vec3.x * vec3.x + vec3.z * vec3.z;
            boolean v = v1 <= v0 * v0 && this.entity.broadcastToPlayer(player)
                    && map.sleep$chunkTracked(player, this.entity.chunkPosition().x, this.entity.chunkPosition().z);
            if (v) {
                if (this.seenBy.add(player.connection)) {
                    if (TrackIndex.ON) TrackIndex.seenAdd(player.connection, this.entity.getId());
                    this.serverEntity.addPairing(player);
                }
            } else if (this.seenBy.remove(player.connection)) {
                if (TrackIndex.ON) TrackIndex.seenRemove(player.connection, this.entity.getId());
                this.serverEntity.removePairing(player);
            }
            return;
        }
        long t0 = TrackFast.TIME && (++TrackFast.sampleTick & 63) == 0 ? System.nanoTime() : 0;
        double dx = player.getX() - this.entity.getX(), dz = player.getZ() - this.entity.getZ();   // vanilla's Vec3 subtract, x and z
        double d1 = dx * dx + dz * dz;
        double cap = (double) (i * 16);
        boolean flag;
        if (d1 > cap * cap) {   // beyond min(range, i * 16) whatever the range: vanilla's d1 <= d2 is false
            flag = false;
            TrackFast.far++;
        } else {
            int eff = TrackFast.noPassengers(this.entity, ((TrackFastEntityAccessor) this.entity).sleep$passengers()) ? this.scaledRange(this.range) : this.getEffectiveRange();
            double d0 = (double) Math.min(eff, i * 16);
            flag = d1 <= d0 * d0 && this.entity.broadcastToPlayer(player)
                    && map.sleep$chunkTracked(player, this.entity.chunkPosition().x, this.entity.chunkPosition().z);
            TrackFast.near++;
        }
        if (TrackFast.VERIFY) {   // vanilla's expression as written
            Vec3 vec3 = player.position().subtract(this.entity.position());
            double v0 = (double) Math.min(this.getEffectiveRange(), i * 16);
            double v1 = vec3.x * vec3.x + vec3.z * vec3.z;
            boolean v = v1 <= v0 * v0 && this.entity.broadcastToPlayer(player)
                    && map.sleep$chunkTracked(player, this.entity.chunkPosition().x, this.entity.chunkPosition().z);
            TrackFast.CHECKED.increment();
            if (v != flag) TrackFast.MISMATCH.increment();
        }
        if (flag) {
            if (this.seenBy.add(player.connection)) {
                if (TrackIndex.ON) TrackIndex.seenAdd(player.connection, this.entity.getId());
                this.serverEntity.addPairing(player);
            }
        } else if (this.seenBy.remove(player.connection)) {
            if (TrackIndex.ON) TrackIndex.seenRemove(player.connection, this.entity.getId());
            this.serverEntity.removePairing(player);
        }
        if (t0 != 0) {
            long d = System.nanoTime() - t0;
            if (d1 > cap * cap) {
                TrackFast.farNs += d;
                TrackFast.farN++;
            } else {
                TrackFast.nearNs += d;
                TrackFast.nearN++;
            }
        }
    }
}
