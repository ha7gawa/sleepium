package dev.lull.opt;

import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Entity.isInWall (every living entity's base tick): a stream over the blocks at the eyes, each asked isAir /
 * isSuffocating / its collision shape against the box. A cell whose section mask bit is clear holds a state with a
 * static, empty collision shape (CollisionMask) - the shape test is false there whatever isSuffocating says, so when
 * every cell's bit is clear the answer is false: an empty stream instead of the walk. Unloaded chunks, a section
 * without a current mask, another mod in the masks' reads: vanilla's stream.
 */
@Mixin(Entity.class)
public abstract class InWallMaskMixin {
    @Shadow public abstract Level level();

    @Redirect(method = "isInWall", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;betweenClosedStream(Lnet/minecraft/world/phys/AABB;)Ljava/util/stream/Stream;"))
    private Stream<BlockPos> sleep$inWall(AABB box) {
        return dev.lull.optsupport.CollisionMask.allClear(level(), box) ? Stream.empty() : BlockPos.betweenClosedStream(box);
    }
}
