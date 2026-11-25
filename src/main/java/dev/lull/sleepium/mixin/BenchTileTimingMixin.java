package dev.lull.sleepium.mixin;

import dev.lull.sleepium.bench.FrameParts;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dsleep.bench.parts (measuring): CPU time of each block entity class's render call. */
@Mixin(TileEntityRendererDispatcher.class)
public abstract class BenchTileTimingMixin {
    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V", at = @At("HEAD"))
    private void sleep$0(TileEntity te, float pt, int stage, CallbackInfo ci) { FrameParts.enter(); }

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V", at = @At("RETURN"))
    private void sleep$1(TileEntity te, float pt, int stage, CallbackInfo ci) { FrameParts.exit("block entity " + te.getClass().getSimpleName()); }
}
