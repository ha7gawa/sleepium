package dev.lull.opt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** part of SpecTicks, lab.fast: the section an entity's storage callback holds it in (a move inside it changes nothing) */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public interface ServerParallelItemsSectionKeyAccessor {
    @Accessor("currentSectionKey")
    long sleep$sectionKey();
}
