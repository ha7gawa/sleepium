package dev.lull.opt;

import dev.lull.optsupport.TaskBudget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TaskBudget: the frame's queued tasks run within a budget, the rest next frame. */
@Mixin(Minecraft.class)
public abstract class TaskBudgetMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void sleep$budgeted(Minecraft mc) {
        TaskBudget.run(mc);
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void sleep$frameEnd(boolean renderLevel, CallbackInfo ci) {
        TaskBudget.frameEnd();
    }
}
