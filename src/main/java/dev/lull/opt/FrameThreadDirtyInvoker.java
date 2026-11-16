package dev.lull.opt;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** FrameThread: LevelRenderer's private setSectionDirty for the marks queued while a frame drew. */
@Mixin(LevelRenderer.class)
public interface FrameThreadDirtyInvoker {
    @Invoker("setSectionDirty")
    void sleep$setSectionDirty(int x, int y, int z, boolean reRenderOnMainThread);
}
