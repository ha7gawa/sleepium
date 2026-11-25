package dev.lull.sleepium.mixin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dsleep.debug.skipRender=SimpleClassName,... (measuring only): those entities are not drawn - each kind's share of a frame. */
@Mixin(RenderManager.class)
public abstract class DebugSkipRenderMixin {
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(System.getProperty("sleep.debug.skipRender", "").split(",")));

    @Inject(method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V", at = @At("HEAD"), cancellable = true)
    private void sleep$skip(Entity e, double x, double y, double z, float yaw, float pt, boolean debug, CallbackInfo ci) {
        if (SKIP.size() > 1 || !SKIP.contains("")) {
            if (SKIP.contains(e.getClass().getSimpleName())) ci.cancel();
        }
    }
}
