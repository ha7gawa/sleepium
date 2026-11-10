package testmods.ticksplit;

import net.minecraft.client.Minecraft;
import testmods.meter.TickSplit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test only (-Dsleep.meter.tickSplit=true): TickSplit's timers around Minecraft.tick's large calls. */
@Mixin(Minecraft.class)
public abstract class TickSplitMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V"))
    private void sleep$beginEntities(CallbackInfo ci) {
        TickSplit.begin(0);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V", shift = At.Shift.AFTER))
    private void sleep$endEntities(CallbackInfo ci) {
        TickSplit.end(0);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void sleep$beginLevel(CallbackInfo ci) {
        TickSplit.begin(1);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V", shift = At.Shift.AFTER))
    private void sleep$endLevel(CallbackInfo ci) {
        TickSplit.end(1);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V"))
    private void sleep$beginAnimate(CallbackInfo ci) {
        TickSplit.begin(2);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V", shift = At.Shift.AFTER))
    private void sleep$endAnimate(CallbackInfo ci) {
        TickSplit.end(2);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V"))
    private void sleep$beginParticles(CallbackInfo ci) {
        TickSplit.begin(3);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V", shift = At.Shift.AFTER))
    private void sleep$endParticles(CallbackInfo ci) {
        TickSplit.end(3);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundManager;tick(Z)V"))
    private void sleep$beginSound(CallbackInfo ci) {
        TickSplit.begin(4);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundManager;tick(Z)V", shift = At.Shift.AFTER))
    private void sleep$endSound(CallbackInfo ci) {
        TickSplit.end(4);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;tick()V"))
    private void sleep$beginLevelrenderer(CallbackInfo ci) {
        TickSplit.begin(5);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;tick()V", shift = At.Shift.AFTER))
    private void sleep$endLevelrenderer(CallbackInfo ci) {
        TickSplit.end(5);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V"))
    private void sleep$beginGamerenderer(CallbackInfo ci) {
        TickSplit.begin(6);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V", shift = At.Shift.AFTER))
    private void sleep$endGamerenderer(CallbackInfo ci) {
        TickSplit.end(6);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
    private void sleep$beginTextures(CallbackInfo ci) {
        TickSplit.begin(7);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V", shift = At.Shift.AFTER))
    private void sleep$endTextures(CallbackInfo ci) {
        TickSplit.end(7);
    }
}
