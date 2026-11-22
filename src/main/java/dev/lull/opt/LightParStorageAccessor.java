package dev.lull.opt;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LightPar: the light storage's updating map, its copied-this-batch sections and the sections to notify. */
@Mixin(LayerLightSectionStorage.class)
public interface LightParStorageAccessor {
    @Accessor("updatingSectionData")
    DataLayerStorageMap<?> sleep$updating();

    @Accessor("changedSections")
    LongSet sleep$changed();

    @Accessor("sectionsAffectedByLightUpdates")
    LongSet sleep$affected();
}
