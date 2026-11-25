package dev.lull.sleepium.mixin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dsleep.debug.skipRender=SimpleClassName,... for block entities (measuring only). */
@Mixin(TileEntityRendererDispatcher.class)
public abstract class DebugSkipTileMixin {
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(System.getProperty("sleep.debug.skipRender", "").split(",")));

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V", at = @At("HEAD"), cancellable = true)
    private void sleep$skip(TileEntity te, double x, double y, double z, float pt, int stage, float alpha, CallbackInfo ci) {
        if (SKIP.contains(te.getClass().getSimpleName())) ci.cancel();
    }
}
