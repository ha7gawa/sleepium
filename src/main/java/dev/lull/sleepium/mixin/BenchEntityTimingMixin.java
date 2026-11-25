package dev.lull.sleepium.mixin;

import dev.lull.sleepium.bench.FrameParts;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dsleep.bench.parts (measuring): CPU time of each entity class's render call. */
@Mixin(RenderManager.class)
public abstract class BenchEntityTimingMixin {
    @Inject(method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V", at = @At("HEAD"))
    private void sleep$0(Entity e, double x, double y, double z, float yaw, float pt, boolean d, CallbackInfo ci) { FrameParts.enter(); }

    @Inject(method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V", at = @At("RETURN"))
    private void sleep$1(Entity e, double x, double y, double z, float yaw, float pt, boolean d, CallbackInfo ci) { FrameParts.exit("entity " + e.getClass().getSimpleName()); }
}
