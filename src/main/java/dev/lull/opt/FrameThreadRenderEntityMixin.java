package dev.lull.opt;

import com.llamalad7.mixinextras.sugar.Local;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** FrameThread: a frame drawn while the main thread ticks - an entity it ticked already is drawn at its last tick's end. */
@Mixin(LevelRenderer.class)
public abstract class FrameThreadRenderEntityMixin {
    @ModifyVariable(method = "renderEntity", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float sleep$partial(float pt, @Local(argsOnly = true) Entity entity) {
        return FrameThread.partial(entity, pt);
    }
}
