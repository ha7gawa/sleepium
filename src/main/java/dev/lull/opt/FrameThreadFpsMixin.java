package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** FrameThread: Minecraft's FPS counts the frames the frame thread drew, not the main thread's loops (FrameThread.fpsFrames). */
@Mixin(Minecraft.class)
public abstract class FrameThreadFpsMixin {
    @ModifyExpressionValue(method = "runTick", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;frames:I", opcode = Opcodes.GETFIELD, ordinal = 1))
    private int sleep$fps(int loops) {
        return dev.lull.optsupport.FrameThread.fpsFrames(loops);
    }
}
