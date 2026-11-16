package dev.lull.opt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** FrameThread (1.20.1): Minecraft's own partial tick - its timer and the paused one - read past getFrameTime's redirect. */
@Mixin(Minecraft.class)
public interface FrameThreadTimerAccessor {
    @Accessor("timer")
    Timer sleep$timer();

    @Accessor("pausePartialTick")
    float sleep$pausePartialTick();
}
