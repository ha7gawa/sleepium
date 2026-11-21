package dev.lull.opt;

import dev.lull.optsupport.PaletteCopy;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** PaletteCopy: a palette resize's copy with one palette lookup per distinct old id and whole words (see PaletteCopy). */
@Mixin(PalettedContainer.Data.class)
public abstract class PaletteCopyMixin<T> {
    @Shadow
    public abstract BitStorage storage();

    @Shadow
    public abstract Palette<T> palette();

    /**
     * @author probe
     * @reason the same palette calls and bits with one lookup per distinct old id (PaletteCopy); otherwise vanilla
     */
    @Overwrite
    public void copyFrom(Palette<T> palette, BitStorage bitStorage) {
        if (PaletteCopy.copy(palette, bitStorage, this.palette(), this.storage())) return;
        for (int i = 0; i < bitStorage.getSize(); i++) {
            T t = palette.valueFor(bitStorage.get(i));
            this.storage().set(i, this.palette().idFor(t));
        }
    }
}
