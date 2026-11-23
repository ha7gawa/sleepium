package dev.lull.opt;

import java.util.Map;
import java.util.function.Function;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * part of SpecTicks, lab.fast: ClassInstanceMultiMap.find (an entity section's typed query) fills its class lists
 * lazily (computeIfAbsent) - a write inside a read; two threads' queries of one section met in it
 * (ConcurrentModificationException, the farm, 11-23). During a run the fill holds the map's lock; the run's moves are
 * deferred, so no other write reaches the map meanwhile.
 */
@Mixin(ClassInstanceMultiMap.class)
public abstract class ServerParallelItemsMultiMapMixin {
    @Redirect(method = "find", at = @At(value = "INVOKE", target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object sleep$fastFill(Map<Object, Object> map, Object key, Function<Object, Object> f) {
        if (dev.lull.optsupport.SpecTicks.FAST && dev.lull.optsupport.SpecTicks.running()) {
            synchronized (map) {
                return map.computeIfAbsent(key, f);
            }
        }
        return map.computeIfAbsent(key, f);
    }
}
