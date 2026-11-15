package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * exp/parallel-entities: entity queries on the tail's workers must not see another entity of the tail. While a parallel
 * walk runs (ParticleWorkers.active) every entity query goes under one lock: an entity section builds its per-class lists
 * lazily (ClassInstanceMultiMap.find: computeIfAbsent) - two workers asking for players at once broke its map (11-15,
 * TickAsync: ConcurrentModificationException in pushEntities).
 */
@Mixin(Level.class)
public abstract class ParallelEntityTicksGuardMixin {
    @Unique private static final Object sleep$QUERY_LOCK = new Object();

    @WrapMethod(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;")
    private List<Entity> sleep$q1Locked(Entity except, AABB box, Predicate<? super Entity> p, Operation<List<Entity>> original) {
        if (!dev.lull.optsupport.ParticleWorkers.active) return original.call(except, box, p);
        synchronized (sleep$QUERY_LOCK) {
            return original.call(except, box, p);
        }
    }

    @WrapMethod(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V")
    private <T extends Entity> void sleep$q2Locked(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> p, List<? super T> out, int max, Operation<Void> original) {
        if (!dev.lull.optsupport.ParticleWorkers.active) {
            original.call(test, box, p, out, max);
            return;
        }
        synchronized (sleep$QUERY_LOCK) {
            original.call(test, box, p, out, max);
        }
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("RETURN"))
    private void sleep$q1(Entity except, AABB box, Predicate<? super Entity> p, CallbackInfoReturnable<List<Entity>> cir) {
        if (dev.lull.optsupport.ParticleWorkers.active) dev.lull.optsupport.ParallelEntities.query(cir.getReturnValue());
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V", at = @At("RETURN"))
    private <T extends Entity> void sleep$q2(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> p, List<? super T> out, int max, CallbackInfo ci) {
        if (dev.lull.optsupport.ParticleWorkers.active) dev.lull.optsupport.ParallelEntities.query(out);
    }
}
