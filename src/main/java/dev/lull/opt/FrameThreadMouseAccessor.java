package dev.lull.opt;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** FrameThread: the mouse movement not yet applied to the player (an extra frame turns its camera by it). */
@Mixin(MouseHandler.class)
public interface FrameThreadMouseAccessor {
    @Accessor("accumulatedDX")
    double sleep$accumulatedDX();

    @Accessor("accumulatedDY")
    double sleep$accumulatedDY();
}
