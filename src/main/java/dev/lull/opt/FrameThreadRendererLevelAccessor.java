package dev.lull.opt;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** FrameThread: the level renderer's own level (null once it left the world - Embeddium's renderer is gone then too). */
@Mixin(LevelRenderer.class)
public interface FrameThreadRendererLevelAccessor {
    @Accessor("level")
    ClientLevel sleep$rendererLevel();
}
