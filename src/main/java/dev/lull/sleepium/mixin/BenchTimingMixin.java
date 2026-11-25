package dev.lull.sleepium.mixin;

import dev.lull.sleepium.bench.FrameParts;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dsleep.bench.parts (measuring): CPU time of RenderGlobal's frame parts, summed while the bench measures. */
@Mixin(RenderGlobal.class)
public abstract class BenchTimingMixin {
    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void sleep$e0(Entity e, ICamera c, float pt, CallbackInfo ci) { FrameParts.start(0); }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void sleep$e1(Entity e, ICamera c, float pt, CallbackInfo ci) { FrameParts.end(0); }

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void sleep$t0(Entity e, double pt, ICamera c, int f, boolean s, CallbackInfo ci) { FrameParts.start(1); }

    @Inject(method = "setupTerrain", at = @At("RETURN"))
    private void sleep$t1(Entity e, double pt, ICamera c, int f, boolean s, CallbackInfo ci) { FrameParts.end(1); }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("HEAD"))
    private void sleep$l0(BlockRenderLayer l, double pt, int pass, Entity e, CallbackInfoReturnable<Integer> cir) { FrameParts.start(2); }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("RETURN"))
    private void sleep$l1(BlockRenderLayer l, double pt, int pass, Entity e, CallbackInfoReturnable<Integer> cir) { FrameParts.end(2); }
}
