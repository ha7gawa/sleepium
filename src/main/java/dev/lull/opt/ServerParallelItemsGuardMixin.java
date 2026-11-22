package dev.lull.opt;

import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * part of SpecTicks (lab): a speculative parallel tick that throws (a SpecAbort before a world write, a spawn, a draw
 * from the level's random - or anything else) is a conflict, not a crash: it is undone and run again in walk order,
 * where vanilla's own guard sees what that run throws.
 */
@Mixin(Level.class)
public abstract class ServerParallelItemsGuardMixin {
    @Inject(method = "guardEntityTick", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void sleep$specGuard(Consumer<T> consumer, T entity, CallbackInfo ci) {
        if (!dev.lull.optsupport.SpecTicks.running()) return;
        dev.lull.optsupport.sleep.SpecTx tx = dev.lull.optsupport.sleep.SpecTx.current();
        if (tx == null || !tx.active || tx.serial) return;
        try {
            consumer.accept(entity);
        } catch (Throwable t) {
            tx.conflict(t instanceof dev.lull.optsupport.sleep.SpecTx.SpecAbort ? "aborted" : "threw " + t);
        }
        ci.cancel();
    }
}
