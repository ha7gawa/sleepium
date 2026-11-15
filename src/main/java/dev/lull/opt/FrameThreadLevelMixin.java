package dev.lull.opt;

import dev.lull.optsupport.FrameThread;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dlab.opt.FrameThreadMixin with lab.frameThread.overlap: a frame drawn while the main thread ticks walks the entity
 * list as it was when the frame was handed over (FrameThread's snapshot) - the live one changes under it.
 */
@Mixin(LevelRenderer.class)
public abstract class FrameThreadLevelMixin {
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private Iterable<net.minecraft.world.entity.Entity> sleep$entitiesHeld(ClientLevel level) {
        Iterable<net.minecraft.world.entity.Entity> held = FrameThread.heldEntities();
        return held != null ? held : level.entitiesForRendering();
    }

    /** a section marked dirty by the main thread while a frame draws: queued, marked when the frame is done (FrameThread) */
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void sleep$dirtyLater(int x, int y, int z, boolean mainThread, CallbackInfo ci) {
        if (FrameThread.deferDirty(x, y, z, mainThread)) ci.cancel();
    }
}
