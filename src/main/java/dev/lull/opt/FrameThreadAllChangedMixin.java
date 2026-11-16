package dev.lull.opt;

import dev.lull.optsupport.FrameThread;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FrameThread: LevelRenderer.allChanged on the main thread (options changed, F3+A, a resource reload, mods' own calls
 * from their tick) drops every section's buffers and our memos (BlockMeshMemo's marks, ChunkMultiDraw's arenas, light
 * volumes) - frames are held off while it runs (the frame thread audit, 11-16).
 */
@Mixin(LevelRenderer.class)
public abstract class FrameThreadAllChangedMixin {
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void sleep$holdFrames(CallbackInfo ci) {
        FrameThread.holdForMain();
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void sleep$releaseFrames(CallbackInfo ci) {
        FrameThread.releaseForMain();
    }
}
