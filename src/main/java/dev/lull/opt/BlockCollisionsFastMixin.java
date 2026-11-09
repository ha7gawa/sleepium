package dev.lull.opt;

import dev.lull.optsupport.CollisionMask;
import dev.lull.optsupport.OwnerThread;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * exp/collision-mask: BlockCollisions' state read of a cell its section's mask (CollisionMask) proves can never collide
 * answers air without reading the section - every collision query of every entity (moves, noCollision, supporting
 * blocks) and of mods. Exact: such a state's collision shape is empty in every context, so vanilla's loop drops the
 * cell for it just as for air (the suffocation and edge checks only ever lead to that shape); every other cell is read
 * as before, the loop and its order unchanged (Shapes.collide's early exit makes the order matter). Levels on their
 * owner thread only (the masks are built there); not in a debug world (its chunks invent their states).
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsFastMixin {
    @Shadow @Final private CollisionGetter collisionGetter;
    @Shadow @Final private CollisionContext context;

    /** 0: not decided yet, 1: masks used, 2: vanilla reads */
    @Unique private byte sleep$mode;
    @Unique private LevelChunk sleep$maskChunk;
    @Unique private int sleep$maskSy = Integer.MIN_VALUE;
    @Unique private long[] sleep$mask;

    @Redirect(method = "computeNext", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState sleep$state(BlockGetter getter, BlockPos pos) {
        if (sleep$mode == 0)
            sleep$mode = (byte) (collisionGetter instanceof Level l && !l.isDebug() && OwnerThread.owns(l) && CollisionMask.usable() ? 1 : 2);
        if (sleep$mode == 1 && getter instanceof LevelChunk chunk && sleep$empty(chunk, pos)) return Blocks.AIR.defaultBlockState();
        return getter.getBlockState(pos);
    }

    /** in the client's entity tick: the position's shape kept for the tick when it does not depend on who asks (CollideKernel.windowShape) */
    @Redirect(method = "computeNext", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape sleep$shape(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext ctx) {
        return dev.lull.optsupport.CollideKernel.windowShape(state, getter, pos, ctx, collisionGetter);
    }

    /** the cell's state can never collide (its section's mask bit is clear) */
    @Unique
    private boolean sleep$empty(LevelChunk chunk, BlockPos pos) {
        int y = pos.getY(), sy = y >> 4;
        if (chunk != sleep$maskChunk || sy != sleep$maskSy) {
            sleep$maskChunk = chunk;
            sleep$maskSy = sy;
            int idx = chunk.getSectionIndexFromSectionY(sy);
            LevelChunkSection s = idx >= 0 && idx < chunk.getSections().length ? chunk.getSection(idx) : null;
            sleep$mask = s instanceof CollisionMask.Holder h ? h.sleep$mask() : null;
        }
        long[] m = sleep$mask;
        if (m == null) return false;
        int b = (y & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
        if ((m[b >>> 6] & 1L << b) != 0) return false;
        CollisionMask.skipped++;
        if (CollisionMask.CHECK) {
            CollisionMask.checked++;
            BlockState real = chunk.getBlockState(pos);
            if (!real.getCollisionShape(collisionGetter, pos, context).isEmpty() && CollisionMask.mismatches++ < 5)
                System.out.println("[sleep/opt] collision mask MISMATCH at " + pos + ": " + real);
        }
        return true;
    }
}
