package dev.lull.sleepium.mixin;

import dev.lull.sleepium.gl.Capture;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The render cache for block entities (the game's own renderers). */
@Mixin(TileEntityRendererDispatcher.class)
public abstract class TileRendererCacheMixin {
    @Inject(method = "renderTileEntityAt", at = @At("HEAD"), cancellable = true)
    private void sleep$begin(TileEntity te, double x, double y, double z, float pt, CallbackInfo ci) {
        if (!Capture.ON) return;
        TileEntitySpecialRenderer r = ((TileEntityRendererDispatcher) (Object) this).getSpecialRenderer(te);
        boolean allowed = r != null && r.getClass().getName().startsWith("net.minecraft.");
        if (Capture.begin(te, allowed, x, y, z, TileEntityRendererDispatcher.staticPlayerX, TileEntityRendererDispatcher.staticPlayerY,
                TileEntityRendererDispatcher.staticPlayerZ, net.minecraftforge.client.MinecraftForgeClient.getRenderPass())) ci.cancel();
    }

    @Inject(method = "renderTileEntityAt", at = @At("RETURN"))
    private void sleep$end(TileEntity te, double x, double y, double z, float pt, CallbackInfo ci) {
        if (!Capture.ON) return;
        Capture.end(te.getClass().getSimpleName(), !Capture.endingRecording() ? null : ptP -> {
            TileEntitySpecialRenderer r = ((TileEntityRendererDispatcher) (Object) this).getSpecialRenderer(te);
            if (r != null) r.renderTileEntityAt(te, x, y, z, ptP);
        });
    }
}
