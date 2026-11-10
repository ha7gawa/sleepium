package dev.lull.opt;

import dev.lull.optsupport.BlockCollisionMemo;
import dev.lull.optsupport.OptCounters;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * exp/collision-memo: resting entities ask the same block question every tick (items: noCollision on their deflated
 * box, ~36 block reads each; JFR 4000 items: 28 % of the server thread in BlockCollisions.computeNext). The block part
 * of noCollision / noBlockCollision is a pure function of the box and of the blocks in the scanned range when every
 * one of them has a provably static collision shape (ShapePurity; no level/pos/context/block-entity dependence).
 * So it is memoized per entity: same box (all six doubles), same LevelChunk sections (identity) with the same
 * modification counters (SectionModCountMixin), chunk still loaded -> same answer. Server thread only.
 * {@code -Dlab.opt.collisionMemoCheck=true} recomputes vanilla on every hit and counts differences.
 */
@Mixin(Level.class)
// 1.20.1: the mixin implements CollisionGetter so that the jar's reobfuscation sees noCollision / findSupportingBlock
// as overrides and gives them their SRG names (unannotated merged methods are not in the refmap; under Mojang's names
// they would be dead methods in production). 1.21: Mojang names at run time, no interface needed.
public abstract class BlockCollisionMemoMixin implements CollisionGetter {
    @Shadow public abstract boolean isClientSide();
    @Shadow public abstract WorldBorder getWorldBorder();

    @Unique private static final boolean CHECK = Boolean.getBoolean("lab.opt.collisionMemoCheck");

    /**
     * The entity this level is ticking (guardEntityTick, the owner thread): a block question asked without an entity
     * during its tick (ExperienceOrb: noCollision(box) every tick) is memoized in its slot. A memo is only ever valid over
     * shapes that depend on nothing but the block (ShapePurity: not on the collision context), so the answer is the same
     * with or without the entity; a stale or wrong owner costs hits, never correctness.
     */
    @Unique private Entity sleep$ticking;
    /** -Dlab.opt.collisionMemoAnon=false: questions without an entity go to vanilla as before (A/B) */
    @Unique private static final boolean ANON = !"false".equals(System.getProperty("lab.opt.collisionMemoAnon"));

    @Inject(method = "guardEntityTick", at = @At("HEAD"))
    private void sleep$tickBegin(Consumer<?> tick, Entity entity, CallbackInfo ci) {
        sleep$ticking = entity;
    }

    @Inject(method = "guardEntityTick", at = @At("RETURN"))
    private void sleep$tickEnd(Consumer<?> tick, Entity entity, CallbackInfo ci) {
        sleep$ticking = null;
    }

    /** Overrides the CollisionGetter default: same steps, block part memoized. */
    public boolean noCollision(@Nullable Entity entity, AABB collisionBox) {
        if (dev.lull.optsupport.sleep.SleepEngine.HOOKS) dev.lull.optsupport.sleep.SleepEngine.readRegion(collisionBox);
        if (!sleep$blocksFree(entity, collisionBox)) return false;
        if (!((CollisionGetter) (Object) this).getEntityCollisions(entity, collisionBox).isEmpty()) return false;
        if (entity == null) return true;
        WorldBorder border = this.getWorldBorder();   // CollisionGetter.borderCollision, inlined (private there)
        VoxelShape vs = border.isInsideCloseToBorder(entity, collisionBox) ? border.getCollisionShape() : null;
        return vs == null || !Shapes.joinIsNotEmpty(vs, Shapes.create(collisionBox), BooleanOp.AND);
    }

    /**
     * Overrides the CollisionGetter default. 1.20.1: CollisionGetter has no noBlockCollision (1.21's
     * LivingEntity.wouldNotSuffocateAtTargetPose uses it); here it is only an added method nobody calls.
     */
    public boolean noBlockCollision(@Nullable Entity entity, AABB boundingBox) {
        if (dev.lull.optsupport.sleep.SleepEngine.HOOKS) dev.lull.optsupport.sleep.SleepEngine.readRegion(boundingBox);
        return sleep$blocksFree(entity, boundingBox);
    }

    /**
     * Overrides the CollisionGetter default (every on-ground entity, every tick): memoized per entity like noCollision,
     * keyed also by the entity position (the distances pick the block). Vanilla body on a miss.
     */
    public java.util.Optional<net.minecraft.core.BlockPos> findSupportingBlock(Entity entity, AABB box) {
        if (dev.lull.optsupport.sleep.SleepEngine.HOOKS) dev.lull.optsupport.sleep.SleepEngine.readRegion(box);
        boolean memo = BlockCollisionMemo.MOVE_SLOTS && BlockCollisionMemo.usable((Level) (Object) this);
        BlockCollisionMemo m = null;
        CollisionGetter self = (CollisionGetter) (Object) this;
        if (memo) {
            m = ((BlockCollisionMemo.Holder) entity).sleep$supportMemo();
            if (m != null && m.valid && m.sameBox(box) && m.sameKey(entity.getX(), entity.getY(), entity.getZ()) && BlockCollisionMemo.unchanged(self, m, box)) {
                BlockCollisionMemo.supportHits++;
                @SuppressWarnings("unchecked")
                java.util.Optional<net.minecraft.core.BlockPos> r = (java.util.Optional<net.minecraft.core.BlockPos>) m.result;
                if (CHECK) {
                    OptCounters.MEMO_CHECKED.incrementAndGet();
                    if (!sleep$vanillaSupport(entity, box).equals(r) && OptCounters.MEMO_MISMATCHES.incrementAndGet() <= 5)
                        System.out.println("[sleep/opt] supporting-block memo MISMATCH for " + entity);
                }
                return r;
            }
            BlockCollisionMemo.supportMisses++;
        }
        java.util.Optional<net.minecraft.core.BlockPos> r;
        r = null;
        if (dev.lull.optsupport.CollideKernel.ON && dev.lull.optsupport.CollideKernel.usable()
                && (r = dev.lull.optsupport.CollideKernel.of((Level) (Object) this).support(entity, box, (Level) (Object) this)) != null) {
            // the kernel's walk over the same blocks (BlockCollisions' cursor, checks, shapes): the nearest one, no iterator
            if (dev.lull.optsupport.CollideKernel.CHECK) {
                dev.lull.optsupport.CollideKernel.supportChecked++;
                if (!sleep$vanillaSupport(entity, box).equals(r) && dev.lull.optsupport.CollideKernel.supportMismatches++ < 5)
                    System.out.println("[sleep/opt] kernel support MISMATCH for " + entity + ": " + r + " vs " + sleep$vanillaSupport(entity, box));
            }
        } else r = sleep$vanillaSupport(entity, box);
        if (memo) {
            if (m == null) ((BlockCollisionMemo.Holder) entity).sleep$setSupportMemo(m = new BlockCollisionMemo());
            m.valid = BlockCollisionMemo.worthRecording(m, box) && BlockCollisionMemo.record(self, m, box);
            if (m.valid) {
                m.setBox(box);
                m.setKey(entity.getX(), entity.getY(), entity.getZ());
                m.result = r;
            }
        }
        return r;
    }

    /** CollisionGetter.findSupportingBlock, verbatim */
    @Unique
    private java.util.Optional<net.minecraft.core.BlockPos> sleep$vanillaSupport(Entity entity, AABB box) {
        net.minecraft.core.BlockPos blockpos = null;
        double d0 = Double.MAX_VALUE;
        net.minecraft.world.level.BlockCollisions<net.minecraft.core.BlockPos> blockcollisions =
                new net.minecraft.world.level.BlockCollisions<>((CollisionGetter) (Object) this, entity, box, false, (p, s) -> p);
        while (blockcollisions.hasNext()) {
            net.minecraft.core.BlockPos blockpos1 = blockcollisions.next();
            double d1 = blockpos1.distToCenterSqr(entity.position());
            if (d1 < d0 || d1 == d0 && (blockpos == null || blockpos.compareTo(blockpos1) < 0)) {
                blockpos = blockpos1.immutable();
                d0 = d1;
            }
        }
        return java.util.Optional.ofNullable(blockpos);
    }

    @Unique
    private boolean sleep$vanillaBlocksFree(@Nullable Entity entity, AABB box) {
        for (VoxelShape vs : ((CollisionGetter) (Object) this).getBlockCollisions(entity, box)) {
            if (!vs.isEmpty()) return false;
        }
        return true;
    }

    @Unique
    private boolean sleep$blocksFree(@Nullable Entity entity, AABB box) {
        Entity owner = entity != null ? entity : ANON ? sleep$ticking : null;   // no entity: the one being ticked keeps the memo
        if (owner == null || !BlockCollisionMemo.usable((Level) (Object) this)) return sleep$vanillaBlocksFree(entity, box);
        CollisionGetter self = (CollisionGetter) (Object) this;
        BlockCollisionMemo.Holder holder = (BlockCollisionMemo.Holder) owner;
        BlockCollisionMemo m = holder.sleep$collisionMemo();
        if (m != null && m.valid && m.sameBox(box) && BlockCollisionMemo.unchanged(self, m, box)) {
            BlockCollisionMemo.hits++;
            if (CHECK) {
                OptCounters.MEMO_CHECKED.incrementAndGet();
                if (sleep$vanillaBlocksFree(entity, box) != m.free && OptCounters.MEMO_MISMATCHES.incrementAndGet() <= 5)
                    System.out.println("[sleep/opt] collision memo MISMATCH for " + entity + " box " + box);
            }
            return m.free;
        }
        BlockCollisionMemo.misses++;
        boolean free = sleep$vanillaBlocksFree(entity, box);
        if (m == null) holder.sleep$setCollisionMemo(m = new BlockCollisionMemo());
        m.valid = BlockCollisionMemo.worthRecording(m, box) && BlockCollisionMemo.record(self, m, box);
        if (m.valid) {
            m.setBox(box);
            m.free = free;
        } else {
            BlockCollisionMemo.unmemoizable++;
        }
        return free;
    }

}
