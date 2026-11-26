package dev.lull.sleepium.mixin;

import dev.lull.sleepium.gl.Capture;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The render cache: the entity pass's begin and end (the cached objects are drawn at its end). */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalPassMixin {
    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void sleep$start(EntityLivingBase view, ICamera camera, float pt, CallbackInfo ci) {
        if (!Capture.ON) return;
        Capture.PT = pt;
        Capture.passStart(net.minecraftforge.client.MinecraftForgeClient.getRenderPass());
    }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void sleep$end(EntityLivingBase view, ICamera camera, float pt, CallbackInfo ci) {
        if (!Capture.ON) return;
        Capture.passEnd(net.minecraftforge.client.MinecraftForgeClient.getRenderPass(), TileEntityRendererDispatcher.staticPlayerX,
                TileEntityRendererDispatcher.staticPlayerY, TileEntityRendererDispatcher.staticPlayerZ);
    }
}
