package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.EntityCache;
import dev.lull.sleepium.render.PartBatch;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PartBatch / EntityCache: a block entity's drawing is a scope (the game's own renderers, not the breaking overlay). */
@Mixin(TileEntityRendererDispatcher.class)
public abstract class PartBatchTileScopeMixin {
    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V", at = @At("HEAD"), cancellable = true)
    private void sleep$begin(TileEntity te, double x, double y, double z, float pt, int stage, float alpha, CallbackInfo ci) {
        if (!PartBatch.ON && !EntityCache.ON) return;
        Object r = ((TileEntityRendererDispatcher) (Object) this).getRenderer(te);
        boolean allowed = stage < 0 && r != null && r.getClass().getName().startsWith("net.minecraft.");
        if (EntityCache.begin(te, allowed, x, y, z, TileEntityRendererDispatcher.staticPlayerX, TileEntityRendererDispatcher.staticPlayerY,
                TileEntityRendererDispatcher.staticPlayerZ, net.minecraftforge.client.MinecraftForgeClient.getRenderPass())) {
            ci.cancel();
            return;
        }
        PartBatch.begin(allowed);
    }

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V", at = @At("RETURN"))
    private void sleep$end(TileEntity te, double x, double y, double z, float pt, int stage, float alpha, CallbackInfo ci) {
        if (!PartBatch.ON && !EntityCache.ON) return;
        EntityCache.end(te.getClass().getSimpleName());
        PartBatch.end();
    }
}
