package dev.lull.opt;

import java.util.Optional;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** part of SpecTicks (lab): a section storage (points of interest) touched by a speculative tick - it stops there. */
@Mixin(SectionStorage.class)
public abstract class ServerParallelItemsSectionStorageMixin {
    @Inject(method = {"get", "getOrLoad"}, at = @At("HEAD"))
    private void sleep$specSection(long key, CallbackInfoReturnable<Optional<?>> cir) {
        if (dev.lull.optsupport.SpecTicks.running()) dev.lull.optsupport.SpecTicks.abortIfSpeculative("reads a section storage");
    }

    @Inject(method = "getOrCreate", at = @At("HEAD"))
    private void sleep$specSectionCreate(long key, CallbackInfoReturnable<Object> cir) {
        if (dev.lull.optsupport.SpecTicks.running()) dev.lull.optsupport.SpecTicks.abortIfSpeculative("writes a section storage");
    }
}
