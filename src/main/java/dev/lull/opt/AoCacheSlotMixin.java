package dev.lull.opt;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** AoCaches: ModelBlockRenderer.enableCaching / clearCache reach the thread's AO cache through its slot. */
@Mixin(ModelBlockRenderer.class)
public abstract class AoCacheSlotMixin {
    @Redirect(method = {"enableCaching", "clearCache"}, at = @At(value = "INVOKE", target = "Ljava/lang/ThreadLocal;get()Ljava/lang/Object;"))
    private static Object sleep$cache(ThreadLocal<?> cache) {
        return dev.lull.optsupport.AoCaches.get(cache);
    }
}
