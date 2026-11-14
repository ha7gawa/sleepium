package dev.lull.opt;

import com.llamalad7.mixinextras.sugar.Local;
import dev.lull.optsupport.TickSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** TickSlice: in a frame in the middle of a tick, an entity the tick has not reached is drawn where it stands. */
@Mixin(LevelRenderer.class)
public abstract class TickSliceRenderMixin {
    @ModifyVariable(method = "renderEntity", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float sleep$partial(float pt, @Local(argsOnly = true) Entity entity) {
        return TickSlice.partial(entity, pt);
    }
}
