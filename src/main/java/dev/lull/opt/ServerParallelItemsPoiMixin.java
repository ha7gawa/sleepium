package dev.lull.opt;

import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * part of SpecTicks (lab): points of interest are shared world state that vanilla updates while it reads them (the
 * village distance tracker runs its pending updates on a query; sections load on a lookup) - a speculative tick that
 * reaches them stops there and runs again in walk order (iron golems' isVillage, villagers' job sites).
 */
@Mixin(PoiManager.class)
public abstract class ServerParallelItemsPoiMixin {
    @Inject(method = "sectionsToVillage", at = @At("HEAD"))
    private void sleep$specVillage(SectionPos pos, CallbackInfoReturnable<Integer> cir) {
        if (dev.lull.optsupport.SpecTicks.running()) dev.lull.optsupport.SpecTicks.abortIfSpeculative("reads the points of interest");
    }
}
