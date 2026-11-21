package dev.lull.opt;

import dev.lull.optsupport.ShapeFullMemo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * ShapeFullMemo: Block.isShapeFullBlock without the Guava cache (see ShapeFullMemo): the loader's own expression,
 * computed once per shape object (the cache's weak keys compare by identity too).
 */
@Mixin(Block.class)
public abstract class ShapeFullMemoMixin {
    /**
     * @author probe
     * @reason the same pure answer kept on the shape instead of a shared LoadingCache
     */
    @Overwrite
    public static boolean isShapeFullBlock(VoxelShape shape) {
        ShapeFullMemo m = (ShapeFullMemo) shape;
        byte s = m.sleep$fullBlock();
        if (s == 0) {
            s = !Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME) ? (byte) 1 : (byte) 2;
            m.sleep$setFullBlock(s);
        }
        return s == 1;
    }
}
