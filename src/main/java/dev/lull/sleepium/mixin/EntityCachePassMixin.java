package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.EntityCache;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** EntityCache: the entity pass's begin and end (the cached objects are drawn at its end). */
@Mixin(RenderGlobal.class)
public abstract class EntityCachePassMixin {
    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void sleep$start(Entity view, ICamera camera, float pt, CallbackInfo ci) {
        EntityCache.passStart(net.minecraftforge.client.MinecraftForgeClient.getRenderPass());
    }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void sleep$end(Entity view, ICamera camera, float pt, CallbackInfo ci) {
        EntityCache.passEnd(net.minecraftforge.client.MinecraftForgeClient.getRenderPass(), TileEntityRendererDispatcher.staticPlayerX,
                TileEntityRendererDispatcher.staticPlayerY, TileEntityRendererDispatcher.staticPlayerZ);
    }
}
