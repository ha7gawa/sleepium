package dev.lull.opt;

import dev.lull.optsupport.ShapeFullMemo;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** ShapeFullMemo: the answer's slot on every VoxelShape (see ShapeFullMemo). */
@Mixin(VoxelShape.class)
public abstract class ShapeFullMemoShapeMixin implements ShapeFullMemo {
    @Unique private byte sleep$full;

    @Override
    public byte sleep$fullBlock() {
        return sleep$full;
    }

    @Override
    public void sleep$setFullBlock(byte v) {
        sleep$full = v;
    }
}
