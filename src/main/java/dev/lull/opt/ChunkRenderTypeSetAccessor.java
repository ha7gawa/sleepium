package dev.lull.opt;

import java.util.BitSet;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** TypeIter: a ChunkRenderTypeSet's bits. */
@Mixin(ChunkRenderTypeSet.class)
public interface ChunkRenderTypeSetAccessor {
    @Accessor("bits")
    BitSet sleep$bits();
}
