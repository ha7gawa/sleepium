package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.EntityCache;
import dev.lull.sleepium.render.PartBatch;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PartBatch / EntityCache: an entity's drawing is a scope (for now the game's own renderers only, not in the outline
 * pass or with the debug boxes); a cached entity is not drawn here (EntityCache draws it at the end of the pass).
 */
@Mixin(RenderManager.class)
public abstract class PartBatchScopeMixin {
    @Shadow private boolean renderOutlines;
    @Shadow private double renderPosX;
    @Shadow private double renderPosY;
    @Shadow private double renderPosZ;

    @Inject(method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V", at = @At("HEAD"), cancellable = true)
    private void sleep$begin(Entity e, double x, double y, double z, float yaw, float pt, boolean d, CallbackInfo ci) {
        if (!PartBatch.ON && !EntityCache.ON) return;
        RenderManager rm = (RenderManager) (Object) this;
        Object r = rm.getEntityRenderObject(e);
        boolean allowed = !renderOutlines && r != null && r.getClass().getName().startsWith("net.minecraft.");
        if (EntityCache.begin(e, allowed && !rm.isDebugBoundingBox(), x, y, z, renderPosX, renderPosY, renderPosZ, net.minecraftforge.client.MinecraftForgeClient.getRenderPass())) {
            ci.cancel();
            return;
        }
        PartBatch.begin(allowed);
    }

    @Inject(method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V", at = @At("RETURN"))
    private void sleep$end(Entity e, double x, double y, double z, float yaw, float pt, boolean d, CallbackInfo ci) {
        if (!PartBatch.ON && !EntityCache.ON) return;
        EntityCache.end(e.getClass().getSimpleName());
        PartBatch.end();
    }
}
