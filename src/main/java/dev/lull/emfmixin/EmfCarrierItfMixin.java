package dev.lull.emfmixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** EmfCarrierMixin's interfaces (a carrier of a class cannot target one): their default methods use EMF / ETF's state. */
@Pseudo
@Mixin(targets = {
        "traben.entity_model_features.models.animation.state.EMFEntityRenderState",
        "traben.entity_texture_features.ETFApi$ETFVariantSuffixProvider"
}, remap = false)
public interface EmfCarrierItfMixin {
}
