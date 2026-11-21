package dev.lull.opt;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** OreScan: Heightmap.getFirstAvailable's storage and chunk. */
@Mixin(Heightmap.class)
public interface HeightmapDataAccessor {
    @Accessor("data")
    BitStorage sleep$data();

    @Accessor("chunk")
    ChunkAccess sleep$chunk();
}
