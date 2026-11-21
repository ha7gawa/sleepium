package dev.lull.opt;

import java.util.Map;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** OreScan: a chunk's heightmaps as ChunkAccess.getHeight reads them (no priming). */
@Mixin(ChunkAccess.class)
public interface ChunkHeightmapsAccessor {
    @Accessor("heightmaps")
    Map<Heightmap.Types, Heightmap> sleep$heightmaps();
}
