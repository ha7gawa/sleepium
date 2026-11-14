package dev.lull.opt;

import dev.lull.optsupport.TickSlice;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** TickSlice: block entities are ticked after the entities - in a frame in the middle of a tick, drawn as they are. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class TickSliceBlockEntityMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float sleep$partial(float pt) {
        return TickSlice.partialLater(pt);
    }
}
