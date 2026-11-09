package dev.lull.optsupport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An entity's collision context that notes whether it was asked anything (CollideKernel's tick shape memo): every
 * question goes to the real context, so a shape computed with it is the shape vanilla computes; one computed without
 * a single question does not depend on who asks and holds for every entity at that position while the level stays as
 * it is. An EntityCollisionContext itself, so instanceof tests see what they would. Made anew for each computation (a
 * block may keep the context it was given).
 */
public final class WatchedContext extends EntityCollisionContext {
    private final CollisionContext real;
    public boolean asked;

    public WatchedContext(CollisionContext real) {
        super(false, -Double.MAX_VALUE, ItemStack.EMPTY, f -> false, null);
        this.real = real;
    }

    @Override
    public boolean isHoldingItem(Item item) {
        asked = true;
        return real.isHoldingItem(item);
    }

    @Override
    public boolean canStandOnFluid(FluidState fluid1, FluidState fluid2) {
        asked = true;
        return real.canStandOnFluid(fluid1, fluid2);
    }

    @Override
    public boolean isDescending() {
        asked = true;
        return real.isDescending();
    }

    @Override
    public boolean isAbove(VoxelShape shape, BlockPos pos, boolean canAscend) {
        asked = true;
        return real.isAbove(shape, pos, canAscend);
    }

    @Override
    public Entity getEntity() {
        asked = true;
        return real instanceof EntityCollisionContext e ? e.getEntity() : null;
    }
}
