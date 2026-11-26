package dev.lull.sleepium.mixin;

import dev.lull.sleepium.gl.Capture;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The render cache: an entity's drawing is recorded while vanilla draws it (the game's own renderers, not with the
 * debug boxes), and a cached entity is not drawn here (Capture draws it at the end of the pass).
 */
@Mixin(RenderManager.class)
public abstract class RenderManagerCacheMixin {
    @Inject(method = "func_147939_a", at = @At("HEAD"), cancellable = true)
    private void sleep$begin(Entity e, double x, double y, double z, float yaw, float pt, boolean b, CallbackInfoReturnable<Boolean> cir) {
        if (!Capture.ON) return;
        RenderManager rm = (RenderManager) (Object) this;
        Render r = rm.getEntityRenderObject(e);
        boolean allowed = r != null && !RenderManager.debugBoundingBox && (!r.isStaticEntity() || b) && r.getClass().getName().startsWith("net.minecraft.");
        if (Capture.begin(e, allowed, x, y, z, RenderManager.renderPosX, RenderManager.renderPosY, RenderManager.renderPosZ,
                net.minecraftforge.client.MinecraftForgeClient.getRenderPass())) cir.setReturnValue(true);
    }

    @Inject(method = "func_147939_a", at = @At("RETURN"))
    private void sleep$end(Entity e, double x, double y, double z, float yaw, float pt, boolean b, CallbackInfoReturnable<Boolean> cir) {
        if (!Capture.ON) return;
        Capture.end(e.getClass().getSimpleName(), !Capture.endingRecording() ? null : ptP -> {
            Render r = ((RenderManager) (Object) this).getEntityRenderObject(e);
            if (r == null) return;
            r.doRender(e, x, y, z, yaw, ptP);
            r.doRenderShadowAndFire(e, x, y, z, yaw, ptP);
        });
    }
}
