package dev.lull.flowmixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * The flow carrier (notes/71): a mixin of nothing. Its declared target only makes Mixin prepare it; RenderFieldFlow
 * adds other mods' classes to its mapping at runtime (classes with no mixins of their own that read fields it logs),
 * so that they pass LateEventSites, where FieldLog rewrites those reads.
 */
@Mixin(targets = "dev.lull.optsupport.FlowCarrierTarget", remap = false)
public abstract class FlowCarrierMixin {
}
