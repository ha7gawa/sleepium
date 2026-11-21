package dev.lull.opt;

import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** SaveDirect (optsupport/SavePalettes): the bits a packed palette of n entries is stored with, as PalettedContainer.pack asks. */
@Mixin(PalettedContainer.Strategy.class)
public interface SaveDirectStrategyAccessor {
    @Invoker("calculateBitsForSerialization")
    <A> int sleep$bitsFor(IdMap<A> registry, int size);
}
