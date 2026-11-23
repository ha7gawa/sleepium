package dev.lull.opt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * part of SpecTicks, lab.fast: a raid's writes from a run tick (a raider joining, dying, picking up the banner; the boss
 * bar) wait for the run's end on the server thread - raiders (witches, pillagers, vindicators, evokers, ravagers) tick
 * in parallel; their goals' reads of the raid see it as the last run left it.
 */
@Mixin(Raid.class)
public abstract class ServerParallelItemsRaidMixin {
    private boolean sleep$defer(Runnable r) {
        return dev.lull.optsupport.SpecTicks.FAST && dev.lull.optsupport.SpecTicks.inRun() && dev.lull.optsupport.SpecTicks.deferWorld(r);
    }

    @Inject(method = "joinRaid", at = @At("HEAD"), cancellable = true)
    private void sleep$join(int wave, Raider raider, BlockPos pos, boolean recruited, CallbackInfo ci) {
        Raid self = (Raid) (Object) this;
        if (sleep$defer(() -> self.joinRaid(wave, raider, pos, recruited))) ci.cancel();
    }

    @Inject(method = "updateBossbar", at = @At("HEAD"), cancellable = true)
    private void sleep$bossbar(CallbackInfo ci) {
        Raid self = (Raid) (Object) this;
        if (sleep$defer(self::updateBossbar)) ci.cancel();
    }

    @Inject(method = "removeFromRaid", at = @At("HEAD"), cancellable = true)
    private void sleep$remove(Raider raider, boolean wandered, CallbackInfo ci) {
        Raid self = (Raid) (Object) this;
        if (sleep$defer(() -> self.removeFromRaid(raider, wandered))) ci.cancel();
    }

    @Inject(method = "setLeader", at = @At("HEAD"), cancellable = true)
    private void sleep$leader(int wave, Raider raider, CallbackInfo ci) {
        Raid self = (Raid) (Object) this;
        if (sleep$defer(() -> self.setLeader(wave, raider))) ci.cancel();
    }

    @Inject(method = "removeLeader", at = @At("HEAD"), cancellable = true)
    private void sleep$unleader(int wave, CallbackInfo ci) {
        Raid self = (Raid) (Object) this;
        if (sleep$defer(() -> self.removeLeader(wave))) ci.cancel();
    }

    @Inject(method = "addHeroOfTheVillage", at = @At("HEAD"), cancellable = true)
    private void sleep$hero(Entity e, CallbackInfo ci) {
        Raid self = (Raid) (Object) this;
        if (sleep$defer(() -> self.addHeroOfTheVillage(e))) ci.cancel();
    }
}
