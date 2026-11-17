package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FrameProfile;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/** FrameProfile (measuring): a section's new mesh set (setCompiled and every hook on it) as a section where it runs. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class FrameProfileCompiledMixin {
    @WrapMethod(method = "setCompiled")
    private void sleep$setCompiled(SectionRenderDispatcher.CompiledSection compiled, Operation<Void> original) {
        if (!FrameThread.drawingHere()) {
            original.call(compiled);
            return;
        }
        FrameProfile.INSTANCE.push("set_compiled");
        try {
            original.call(compiled);
        } finally {
            FrameProfile.INSTANCE.pop();
        }
    }
}
