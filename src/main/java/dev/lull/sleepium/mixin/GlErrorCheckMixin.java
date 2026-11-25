package dev.lull.sleepium.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GL errors looked for once a second, not twice a frame (-Dsleep.opt.glErrorCheck, default on): Minecraft.checkGLError
 * ("Pre render", "Post render") calls glGetError, and on a threaded driver (NVIDIA's default) that waits until the
 * driver has done everything queued - the game's thread and the driver's then work one after the other instead of side
 * by side. glGetError only reads the error flag: what is drawn is the same; an error is still logged, within a second.
 */
@Mixin(Minecraft.class)
public abstract class GlErrorCheckMixin {
    private static final boolean ON = !"false".equals(System.getProperty("sleep.opt.glErrorCheck"));
    private static long sleep$last;

    @Inject(method = "checkGLError", at = @At("HEAD"), cancellable = true)
    private void sleep$rarely(String message, CallbackInfo ci) {
        if (!ON) return;
        long now = System.nanoTime();
        if (now - sleep$last < 1_000_000_000L) ci.cancel();
        else sleep$last = now;
    }
}
