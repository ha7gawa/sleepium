package dev.lull.sleepium.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The mixin toolchain's first check: a line once the game has started. */
@Mixin(Minecraft.class)
public abstract class MinecraftStartMixin {
    @Inject(method = "startGame", at = @At("TAIL"))
    private void sleep$started(CallbackInfo ci) {
        dev.lull.sleepium.Sleepium.LOGGER.info("[sleep] mixins applied (Minecraft.startGame)");
    }
}
