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

/** exp/parallel-entities: entity queries on the tail's workers must not see another entity of the tail. */
@Mixin(Level.class)
public abstract class ParallelEntityTicksGuardMixin {
    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("RETURN"))
    private void sleep$q1(Entity except, AABB box, Predicate<? super Entity> p, CallbackInfoReturnable<List<Entity>> cir) {
        if (dev.lull.optsupport.ParticleWorkers.active) dev.lull.optsupport.ParallelEntities.query(cir.getReturnValue());
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V", at = @At("RETURN"))
    private <T extends Entity> void sleep$q2(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> p, List<? super T> out, int max, CallbackInfo ci) {
        if (dev.lull.optsupport.ParticleWorkers.active) dev.lull.optsupport.ParallelEntities.query(out);
    }
}
