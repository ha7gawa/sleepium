package dev.lull.mixin;

import dev.lull.TickCensus;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Measurement only (-Dsleep.tickCensus=true): block writes and entity queries during a server entity tick (TickCensus). */
@Mixin(Level.class)
public abstract class TickCensusLevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void sleep$censusBlock(BlockPos pos, BlockState state, int flags, int left, CallbackInfoReturnable<Boolean> cir) {
        TickCensus.setBlock((Level) (Object) this);
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("RETURN"))
    private void sleep$censusQuery(Entity except, AABB box, Predicate<? super Entity> p, CallbackInfoReturnable<List<Entity>> cir) {
        TickCensus.queried((Level) (Object) this, cir.getReturnValue());
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V", at = @At("RETURN"))
    private <T extends Entity> void sleep$censusQueryTyped(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> p, List<? super T> out, int max, CallbackInfo ci) {
        TickCensus.queried((Level) (Object) this, out);
    }
}
