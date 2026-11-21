package dev.lull.opt;

import dev.lull.optsupport.AtlasDouble;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.atlasDouble: the whole client tick timed - the double atlas's measured choice (writing the drawn atlas made the
 *  rest of the tick wait too: on the Pi the client tick went 34 -> 9.5 ms while the atlas tick went 9.5 -> 4.9). */
@Mixin(Minecraft.class)
public abstract class AtlasDoubleTickMixin {
    private static long sleep$tickT0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$tickIn(CallbackInfo ci) {
        sleep$tickT0 = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sleep$tickOut(CallbackInfo ci) {
        AtlasDouble.clientTick(System.nanoTime() - sleep$tickT0);
    }
}
