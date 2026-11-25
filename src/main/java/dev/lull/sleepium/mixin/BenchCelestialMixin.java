package dev.lull.sleepium.mixin;

import net.minecraft.world.WorldProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The client bench only (-Dsleep.bench): the sun's angle without the frame's fraction of a tick. Vanilla adds it even
 * with the daylight cycle off, so the sky and fog colours of a screenshot depended on when in the tick it was taken (1
 * LSB over the horizon between two runs of the same code). Both sides of a comparison run with it.
 */
@Mixin(WorldProvider.class)
public abstract class BenchCelestialMixin {
    private static final boolean BENCH = System.getProperty("sleep.bench") != null;

    @ModifyVariable(method = "calculateCelestialAngle(JF)F", at = @At("HEAD"), argsOnly = true)
    private float sleep$wholeTick(float partialTicks) {
        return BENCH ? 0f : partialTicks;
    }

    /** the client counts the day itself between the server's time packets (every 20 ticks): noon, held */
    @ModifyVariable(method = "calculateCelestialAngle(JF)F", at = @At("HEAD"), argsOnly = true)
    private long sleep$noon(long worldTime) {
        return BENCH ? 6000L : worldTime;
    }
}
