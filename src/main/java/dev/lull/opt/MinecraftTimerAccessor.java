package dev.lull.opt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** TickSlice (1.20.1): the client's timer (a private field there). Part of lab.opt.TickSlice. */
@Mixin(Minecraft.class)
public interface MinecraftTimerAccessor {
    @Accessor("timer")
    Timer sleep$timer();
}
