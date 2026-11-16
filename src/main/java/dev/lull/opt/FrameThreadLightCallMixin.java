package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * -Dlab.opt.FrameThreadMixin: a light update's callback on the main thread goes whole (with every mod's code in it:
 * Flywheel notes the section for its light-updated visuals, walked by its workers during the frame) to the frame
 * thread's queue - made at the next frame's start.
 */
@Mixin(ClientChunkCache.class)
public abstract class FrameThreadLightCallMixin {
    @WrapMethod(method = "onLightUpdate")
    private void sleep$lightLater(LightLayer type, SectionPos pos, Operation<Void> original) {
        if (!FrameThread.deferCall(original, type, pos)) original.call(type, pos);
    }
}
