package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * part of SpecTicks, lab.fast (lab.fast.poiLock, on): the points-of-interest manager under one lock while a run ticks -
 * villagers and iron golems tick on any worker instead of all of a block on one thread. A query's stream is read into
 * a list under the lock (it is lazy: read later it would walk the sections unguarded). Nested calls (findAll reads
 * getInRange) run as they are.
 */
@Mixin(PoiManager.class)
public abstract class ServerParallelItemsPoiLockMixin {
    @WrapMethod(method = "getCountInRange(Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)J")
    private long sleep$poi0(Predicate<Holder<PoiType>> a, BlockPos b, int c, PoiManager.Occupancy d, Operation<Long> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d);
        }
    }

    @WrapMethod(method = "existsAtPosition(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/BlockPos;)Z")
    private boolean sleep$poi1(net.minecraft.resources.ResourceKey<PoiType> a, BlockPos b, Operation<Boolean> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b);
        }
    }

    @WrapMethod(method = "getInSquare(Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;")
    private Stream<PoiRecord> sleep$poi2(Predicate<Holder<PoiType>> a, BlockPos b, int c, PoiManager.Occupancy d, Operation<Stream<PoiRecord>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d).toList().stream();
        }
    }

    @WrapMethod(method = "getInRange(Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;")
    private Stream<PoiRecord> sleep$poi3(Predicate<Holder<PoiType>> a, BlockPos b, int c, PoiManager.Occupancy d, Operation<Stream<PoiRecord>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d).toList().stream();
        }
    }

    @WrapMethod(method = "getInChunk(Ljava/util/function/Predicate;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;")
    private Stream<PoiRecord> sleep$poi4(Predicate<Holder<PoiType>> a, net.minecraft.world.level.ChunkPos b, PoiManager.Occupancy c, Operation<Stream<PoiRecord>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c).toList().stream();
        }
    }

    @WrapMethod(method = "findAll(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;")
    private Stream<BlockPos> sleep$poi5(Predicate<Holder<PoiType>> a, Predicate<BlockPos> b, BlockPos c, int d, PoiManager.Occupancy e, Operation<Stream<BlockPos>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d, e);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d, e).toList().stream();
        }
    }

    @WrapMethod(method = "findAllWithType(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;")
    private Stream<Pair<Holder<PoiType>, BlockPos>> sleep$poi6(Predicate<Holder<PoiType>> a, Predicate<BlockPos> b, BlockPos c, int d, PoiManager.Occupancy e, Operation<Stream<Pair<Holder<PoiType>, BlockPos>>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d, e);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d, e).toList().stream();
        }
    }

    @WrapMethod(method = "findAllClosestFirstWithType(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;")
    private Stream<Pair<Holder<PoiType>, BlockPos>> sleep$poi7(Predicate<Holder<PoiType>> a, Predicate<BlockPos> b, BlockPos c, int d, PoiManager.Occupancy e, Operation<Stream<Pair<Holder<PoiType>, BlockPos>>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d, e);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d, e).toList().stream();
        }
    }

    @WrapMethod(method = "find(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/Optional;")
    private Optional<BlockPos> sleep$poi8(Predicate<Holder<PoiType>> a, Predicate<BlockPos> b, BlockPos c, int d, PoiManager.Occupancy e, Operation<Optional<BlockPos>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d, e);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d, e);
        }
    }

    @WrapMethod(method = "findClosest(Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/Optional;")
    private Optional<BlockPos> sleep$poi9(Predicate<Holder<PoiType>> a, BlockPos b, int c, PoiManager.Occupancy d, Operation<Optional<BlockPos>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d);
        }
    }

    @WrapMethod(method = "findClosestWithType(Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/Optional;")
    private Optional<Pair<Holder<PoiType>, BlockPos>> sleep$poi10(Predicate<Holder<PoiType>> a, BlockPos b, int c, PoiManager.Occupancy d, Operation<Optional<Pair<Holder<PoiType>, BlockPos>>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d);
        }
    }

    @WrapMethod(method = "findClosest(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/Optional;")
    private Optional<BlockPos> sleep$poi11(Predicate<Holder<PoiType>> a, Predicate<BlockPos> b, BlockPos c, int d, PoiManager.Occupancy e, Operation<Optional<BlockPos>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d, e);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d, e);
        }
    }

    @WrapMethod(method = "take(Ljava/util/function/Predicate;Ljava/util/function/BiPredicate;Lnet/minecraft/core/BlockPos;I)Ljava/util/Optional;")
    private Optional<BlockPos> sleep$poi12(Predicate<Holder<PoiType>> a, java.util.function.BiPredicate<Holder<PoiType>, BlockPos> b, BlockPos c, int d, Operation<Optional<BlockPos>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d);
        }
    }

    @WrapMethod(method = "getRandom(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;Lnet/minecraft/core/BlockPos;ILnet/minecraft/util/RandomSource;)Ljava/util/Optional;")
    private Optional<BlockPos> sleep$poi13(Predicate<Holder<PoiType>> a, Predicate<BlockPos> b, PoiManager.Occupancy c, BlockPos d, int e, net.minecraft.util.RandomSource f, Operation<Optional<BlockPos>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b, c, d, e, f);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b, c, d, e, f);
        }
    }

    @WrapMethod(method = "release(Lnet/minecraft/core/BlockPos;)Z")
    private boolean sleep$poi14(BlockPos a, Operation<Boolean> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a);
        }
    }

    @WrapMethod(method = "exists(Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;)Z")
    private boolean sleep$poi15(BlockPos a, Predicate<Holder<PoiType>> b, Operation<Boolean> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a, b);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a, b);
        }
    }

    @WrapMethod(method = "getType(Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;")
    private Optional<Holder<PoiType>> sleep$poi16(BlockPos a, Operation<Optional<Holder<PoiType>>> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a);
        }
    }

    @WrapMethod(method = "getFreeTickets(Lnet/minecraft/core/BlockPos;)I")
    private int sleep$poi17(BlockPos a, Operation<Integer> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a);
        }
    }

    @WrapMethod(method = "sectionsToVillage(Lnet/minecraft/core/SectionPos;)I")
    private int sleep$poiVillage(net.minecraft.core.SectionPos a, Operation<Integer> original) {
        // (runs the village distance tracker's updates: a write - ServerLevel.isVillage from a villager's brain)
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) return original.call(a);
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            return original.call(a);
        }
    }

    @WrapMethod(method = "ensureLoadedAndValid(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;I)V")
    private void sleep$poiEnsure(net.minecraft.world.level.LevelReader a, BlockPos b, int c, Operation<Void> original) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.running() || Thread.holdsLock(dev.lull.optsupport.SpecTicks.POI)) {
            original.call(a, b, c);
            return;
        }
        synchronized (dev.lull.optsupport.SpecTicks.POI) {
            original.call(a, b, c);
        }
    }
}
