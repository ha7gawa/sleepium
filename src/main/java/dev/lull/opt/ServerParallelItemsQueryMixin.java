package dev.lull.opt;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** part of SpecTicks (lab): the boxes of a speculative tick's entity queries (checked against the run's moves after it). */
@Mixin(Level.class)
public abstract class ServerParallelItemsQueryMixin {
    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("HEAD"))
    private void sleep$specQuery(Entity except, AABB box, Predicate<? super Entity> p, CallbackInfoReturnable<List<Entity>> cir) {
        if (dev.lull.optsupport.SpecTicks.EXACT && dev.lull.optsupport.sleep.WB.TX) dev.lull.optsupport.sleep.SpecTx.queried(box);
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V", at = @At("HEAD"))
    private <T extends Entity> void sleep$specQueryTyped(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> p, List<? super T> out, int max, CallbackInfo ci) {
        if (dev.lull.optsupport.SpecTicks.EXACT && dev.lull.optsupport.sleep.WB.TX) dev.lull.optsupport.sleep.SpecTx.queried(box);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void sleep$specSetBlock(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags, int left, CallbackInfoReturnable<Boolean> cir) {
        if (dev.lull.optsupport.SpecTicks.FAST) {   // (lab.fast: the write at the run's end, in walk order - reported as done)
            Level self = (Level) (Object) this;
            net.minecraft.core.BlockPos p = pos.immutable();
            if (dev.lull.optsupport.SpecTicks.deferWorld(() -> self.setBlock(p, state, flags, left))) cir.setReturnValue(true);
            return;
        }
        if (dev.lull.optsupport.SpecTicks.EXACT && dev.lull.optsupport.sleep.WB.TX) dev.lull.optsupport.SpecTicks.abortIfSpeculative("sets a block");
    }
}
