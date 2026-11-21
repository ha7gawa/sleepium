package dev.lull.opt;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** AoCaches: an AO face's calculate reaches the thread's AO cache through its slot (every face meshed). */
@Mixin(ModelBlockRenderer.AmbientOcclusionFace.class)
public abstract class AoCacheSlotFaceMixin {
    @Redirect(method = "calculate", at = @At(value = "INVOKE", target = "Ljava/lang/ThreadLocal;get()Ljava/lang/Object;"))
    private Object sleep$cache(ThreadLocal<?> cache) {
        return dev.lull.optsupport.AoCaches.get(cache);
    }
}
