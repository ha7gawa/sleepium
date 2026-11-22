package dev.lull.opt;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LightPar: a light storage map's sections. */
@Mixin(DataLayerStorageMap.class)
public interface LightParMapAccessor {
    @Accessor("map")
    Long2ObjectOpenHashMap<DataLayer> sleep$map();
}
