package dev.lull.opt;

import dev.lull.optsupport.BlockCollisionMemo;
import dev.lull.optsupport.OptCounters;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Goes with BlockCollisionMemoMixin: the block collision of a move (Entity.collideBoundingBox) is a pure function of
 * the movement, the box and the shapes of the blocks in the range BlockCollisions scans. With no entity colliders,
 * away from the world border and over provably static shapes in unchanged sections, the same movement and box give
 * the same answer: memoized per entity (resting items and mobs repeat it every tick). Same object semantics as
 * vanilla: the movement itself when nothing collides, a new Vec3 otherwise.
 */
@Mixin(Entity.class)
public abstract class EntityCollideMemoMixin {
    /**
     * 1.20.1's collideBoundingBox builds the list inline (1.21 has Entity.collectColliders): the potential hits, the
     * border's shape when the entity is close to it, then the block collisions of the swept box — the same list.
     */
    @org.spongepowered.asm.mixin.Unique
    private static List<VoxelShape> sleep$colliders(@Nullable Entity entity, Level level, List<VoxelShape> potentialHits, AABB swept) {
        com.google.common.collect.ImmutableList.Builder<VoxelShape> builder = com.google.common.collect.ImmutableList.builderWithExpectedSize(potentialHits.size() + 1);
        if (!potentialHits.isEmpty()) builder.addAll(potentialHits);
        net.minecraft.world.level.border.WorldBorder worldborder = level.getWorldBorder();
        if (entity != null && worldborder.isInsideCloseToBorder(entity, swept)) builder.add(worldborder.getCollisionShape());
        builder.addAll(level.getBlockCollisions(entity, swept));
        return builder.build();
    }

    @Shadow
    private static Vec3 collideWithShapes(Vec3 deltaMovement, AABB entityBB, List<VoxelShape> shapes) {
        throw new AssertionError();
    }

    /**
     * The miss path: CollideKernel (vanilla's shapes and math without their garbage) on the owner thread when no other
     * mod's mixin is in that path, else vanilla's list + collideWithShapes.
     */
    @org.spongepowered.asm.mixin.Unique
    private static Vec3 sleep$collide(@Nullable Entity entity, Vec3 vec, AABB collisionBox, AABB bb, Level level, List<VoxelShape> potentialHits) {
        if (dev.lull.optsupport.CollideKernel.ON && dev.lull.optsupport.CollideKernel.usable()
                && (BlockCollisionMemo.usable(level) || dev.lull.optsupport.ParticleWorkers.inWorker())) {
            if (dev.lull.optsupport.CollideKernel.CHECK && dev.lull.optsupport.CollideKernel.vanilla == null)
                dev.lull.optsupport.CollideKernel.vanilla = (e, v, cb, b, l, ph) -> collideWithShapes(v, cb, sleep$colliders(e, l, ph, b));
            Vec3 r = dev.lull.optsupport.CollideKernel.of(level).collide(entity, vec, collisionBox, bb, level, potentialHits);
            if (r != null) return r;
        }
        return collideWithShapes(vec, collisionBox, sleep$colliders(entity, level, potentialHits, bb));
    }

    /**
     * @author probe (lab.opt.BlockCollisionMemoMixin)
     * @reason memoized block collision for repeated identical moves; vanilla body otherwise
     */
    @Overwrite
    public static Vec3 collideBoundingBox(@Nullable Entity entity, Vec3 vec, AABB collisionBox, Level level, List<VoxelShape> potentialHits) {
        AABB bb = collisionBox.expandTowards(vec);
        if (dev.lull.optsupport.sleep.SleepEngine.HOOKS) dev.lull.optsupport.sleep.SleepEngine.readRegion(bb);
        if (entity == null || !BlockCollisionMemo.MOVE_SLOTS || !potentialHits.isEmpty() || !BlockCollisionMemo.usable(level) || level.getWorldBorder().isInsideCloseToBorder(entity, bb)) {
            return sleep$collide(entity, vec, collisionBox, bb, level, potentialHits);
        }
        BlockCollisionMemo.Holder holder = (BlockCollisionMemo.Holder) entity;
        BlockCollisionMemo m = holder.sleep$collideMemo();
        if (m != null && m.valid && m.sameBox(collisionBox) && m.sameKey(vec.x, vec.y, vec.z) && BlockCollisionMemo.unchanged(level, m, bb)) {
            BlockCollisionMemo.collideHits++;
            Vec3 r = m.same ? vec : new Vec3(m.r0, m.r1, m.r2);
            if (BlockCollisionMemo.CHECK) {
                OptCounters.MEMO_CHECKED.incrementAndGet();
                Vec3 v = collideWithShapes(vec, collisionBox, sleep$colliders(entity, level, potentialHits, bb));
                boolean ok = (v == vec) == m.same && Double.doubleToRawLongBits(v.x) == Double.doubleToRawLongBits(r.x)
                        && Double.doubleToRawLongBits(v.y) == Double.doubleToRawLongBits(r.y) && Double.doubleToRawLongBits(v.z) == Double.doubleToRawLongBits(r.z);
                if (!ok && OptCounters.MEMO_MISMATCHES.incrementAndGet() <= 5)
                    System.out.println("[sleep/opt] collide memo MISMATCH for " + entity + ": memo " + r + " vanilla " + v);
            }
            return r;
        }
        BlockCollisionMemo.collideMisses++;
        Vec3 r = sleep$collide(entity, vec, collisionBox, bb, level, potentialHits);
        if (m == null) holder.sleep$setCollideMemo(m = new BlockCollisionMemo());
        m.valid = BlockCollisionMemo.worthRecording(m, collisionBox) && BlockCollisionMemo.record(level, m, bb);
        if (m.valid) {
            m.setBox(collisionBox);
            m.setKey(vec.x, vec.y, vec.z);
            m.same = r == vec;
            m.r0 = r.x;
            m.r1 = r.y;
            m.r2 = r.z;
        }
        return r;
    }
}
