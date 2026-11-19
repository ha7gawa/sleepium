package dev.lull.opt;

import dev.lull.optsupport.ParallelRender;
import net.minecraft.client.model.SkullModelBase;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.world.level.block.SkullBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * While ParallelRender builds a worker's renderer set, the skull models are baked once for the whole set and shared by
 * its renderers' head layers (a set serves one job at a time). Vanilla bakes them in every CustomHeadLayer - ~20 times a
 * set - and with one set per worker Entity Model Features counted more than 64 creations of each head model and turned
 * its own head models off for the rest of the game ("model attempted creation more than 64 times", 11-19).
 */
@Mixin(SkullBlockRenderer.class)
public abstract class SkullSetShareMixin {
    @Inject(method = "createSkullRenderers", at = @At("HEAD"), cancellable = true)
    private static void sleep$shared(EntityModelSet models, CallbackInfoReturnable<Map<SkullBlock.Type, SkullModelBase>> cir) {
        if (ParallelRender.buildingSets && ParallelRender.setSkulls != null) cir.setReturnValue(ParallelRender.setSkulls);
    }

    @Inject(method = "createSkullRenderers", at = @At("RETURN"))
    private static void sleep$keep(EntityModelSet models, CallbackInfoReturnable<Map<SkullBlock.Type, SkullModelBase>> cir) {
        if (ParallelRender.buildingSets && ParallelRender.setSkulls == null) ParallelRender.setSkulls = cir.getReturnValue();
    }
}
