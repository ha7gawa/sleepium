package dev.lull.opt;

import dev.lull.optsupport.PaletteCopy;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.function.Consumer;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * PaletteGetAll (-Dlab.opt.PaletteGetAllMixin): PalettedContainer.getAll - FEATURES collects the biomes of its 3 x 3
 * chunks this way (9 x 24 biome containers, 64 entries each, every entry an IntArraySet.add with a linear search) -
 * with the distinct ids found by a 64-bit mask over the raw words (PaletteCopy.distinctIds). Vanilla: every entry's id
 * into an IntArraySet (insertion order = first occurrence), then consumer.accept(palette.valueFor(id)) per id in that
 * order. Here the same ids in the same first-occurrence order, then the same valueFor / accept calls in the same order.
 * Storages with more than 6 bits (ids beyond the mask) or of other classes take vanilla's lines.
 */
@Mixin(PalettedContainer.class)
public abstract class PaletteGetAllMixin<T> {
    @Shadow
    private volatile PalettedContainer.Data<T> data;

    /**
     * @author probe
     * @reason the distinct ids by a bit mask, same order and calls (PaletteGetAll); otherwise vanilla
     */
    @Overwrite
    public void getAll(Consumer<T> consumer) {
        Palette<T> palette = this.data.palette();
        BitStorage storage = this.data.storage();
        int[] ids = PaletteCopy.distinctIds(storage);
        if (ids != null) {
            try {
                int n = ids[64];
                for (int k = 0; k < n; k++) consumer.accept(palette.valueFor(ids[k]));
            } finally {
                ids[65] = 0;   // the thread's scratch given back (a nested getAll from the consumer takes vanilla's lines)
            }
            return;
        }
        IntSet intset = new IntArraySet();
        storage.getAll(intset::add);
        intset.forEach(p_238274_ -> consumer.accept(palette.valueFor(p_238274_)));
    }
}
