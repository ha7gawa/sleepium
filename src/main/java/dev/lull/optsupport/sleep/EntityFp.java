package dev.lull.optsupport.sleep;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Value fingerprints for the entity sleep snapshot. An entity replaces its vectors and boxes with new, equal objects
 * every tick, so they are compared by value; its random generator by seed (a draw changes the state); synched data by
 * the values and dirty flags of its items. Everything else as {@link dev.lull.FieldDiff#fingerprint}.
 */
public final class EntityFp {
    private static final VarHandle ITEMS, DIRTY, ITEM_DIRTY, LEGACY_SEED, SINGLE_SEED;

    static {
        VarHandle items = null, dirty = null, itemDirty = null, legacy = null, single = null;
        try {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(SynchedEntityData.class, MethodHandles.lookup());
            items = l.findVarHandle(SynchedEntityData.class, "itemsById", SynchedEntityData.DataItem[].class);
            dirty = l.findVarHandle(SynchedEntityData.class, "isDirty", boolean.class);
            itemDirty = MethodHandles.privateLookupIn(SynchedEntityData.DataItem.class, MethodHandles.lookup())
                    .findVarHandle(SynchedEntityData.DataItem.class, "dirty", boolean.class);
            Class<?> lr = net.minecraft.world.level.levelgen.LegacyRandomSource.class;
            legacy = MethodHandles.privateLookupIn(lr, MethodHandles.lookup()).findVarHandle(lr, "seed", java.util.concurrent.atomic.AtomicLong.class);
            Class<?> sr = net.minecraft.world.level.levelgen.SingleThreadedRandomSource.class;
            single = MethodHandles.privateLookupIn(sr, MethodHandles.lookup()).findVarHandle(sr, "seed", long.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[sleep/entsleep] fingerprint handles unavailable: " + e);
        }
        ITEMS = items;
        DIRTY = dirty;
        ITEM_DIRTY = itemDirty;
        LEGACY_SEED = legacy;
        SINGLE_SEED = single;
    }

    private EntityFp() {}

    /** EntSleepDataMixin: synched data's count of changes (values replaced, dirty flags cleared) and whether a value can change in place */
    public interface DataEpoch {
        int sleep$dataEpoch();

        boolean sleep$mutableValues();
    }

    /** a synched data value that cannot change while it stays the same object */
    public static boolean immutableValue(Object v) {
        if (v == null || v instanceof Boolean || v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long || v instanceof Float
                || v instanceof Double || v instanceof Character || v instanceof String || v instanceof Enum<?> || v instanceof java.util.UUID
                || v instanceof net.minecraft.world.level.block.state.BlockState || v instanceof net.minecraft.core.Holder<?>
                || v instanceof net.minecraft.core.Rotations || v instanceof net.minecraft.world.entity.npc.VillagerData) return true;
        if (v instanceof BlockPos p) return !(p instanceof BlockPos.MutableBlockPos);
        if (v instanceof java.util.Optional<?> o) return o.isEmpty() || immutableValue(o.get());
        if (v instanceof java.util.OptionalInt) return true;
        if (immutableCollection(v)) {
            for (Object x : (Iterable<?>) v) if (!immutableValue(x) && !(x instanceof net.minecraft.core.particles.ParticleOptions)) return false;
            return true;
        }
        return false;
    }

    /** a collection or map that cannot change at all: Guava's immutable ones, the JDK's List.of / Set.of / Map.of and empty ones */
    static boolean immutableCollection(Object r) {
        if (r instanceof com.google.common.collect.ImmutableCollection<?> || r instanceof com.google.common.collect.ImmutableMap<?, ?>) return true;
        Class<?> c = r.getClass();
        return c.getName().startsWith("java.util.ImmutableCollections$") || c == EMPTY_LIST || c == EMPTY_SET || c == EMPTY_MAP;
    }

    private static final Class<?> EMPTY_LIST = java.util.Collections.emptyList().getClass(), EMPTY_SET = java.util.Collections.emptySet().getClass(),
            EMPTY_MAP = java.util.Collections.emptyMap().getClass();

    /**
     * fp for a mob's observed snapshots: synched data whose values cannot change in place by its identity and change
     * count (its items are not touched: a snapshot's cost is its cache misses); everything else as fp.
     */
    static long observedFp(Object r) {
        if (r instanceof SynchedEntityData && r instanceof DataEpoch d && !d.sleep$mutableValues())
            return mix(System.identityHashCode(r) * 31L + 13, d.sleep$dataEpoch());
        return fp(r);
    }

    static boolean available() {
        return ITEMS != null && DIRTY != null && ITEM_DIRTY != null && LEGACY_SEED != null && SINGLE_SEED != null;
    }

    public static long fp(Object r) {
        if (r == null) return 0;
        if (r instanceof Vec3 v) return mix(mix(bits(v.x), bits(v.y)), bits(v.z));
        if (r instanceof AABB b) return mix(mix(mix(mix(mix(bits(b.minX), bits(b.minY)), bits(b.minZ)), bits(b.maxX)), bits(b.maxY)), bits(b.maxZ));
        if (r instanceof BlockPos p) return p.asLong() * 0x9E3779B97F4A7C15L + 1;
        // an Optional of an immutable value (mainSupportingBlockPos, made anew whenever the support is looked up): by value
        if (r instanceof java.util.Optional<?> o && immutableValue(o)) return o.isEmpty() ? 5 : mix(fp(o.get()), 17);
        if (r instanceof SynchedEntityData d) return synched(d);
        if (r instanceof net.minecraft.world.level.levelgen.LegacyRandomSource) return ((java.util.concurrent.atomic.AtomicLong) LEGACY_SEED.get(r)).get() * 31 + 7;
        if (r instanceof net.minecraft.world.level.levelgen.SingleThreadedRandomSource) return (long) SINGLE_SEED.get(r) * 31 + 11;
        if (r instanceof Boolean || r instanceof Number || r instanceof String || r instanceof Enum<?>) return r.hashCode() * 31L + 3;
        if (r instanceof net.minecraft.world.level.block.entity.ChestLidController lid)
            return mix(Float.floatToRawIntBits(lid.getOpenness(0f)), Float.floatToRawIntBits(lid.getOpenness(1f)));
        if (r instanceof net.minecraft.world.level.BaseSpawner sp)
            return mix(bits(sp.getoSpin()), bits(sp.getSpin()));
        if (r instanceof net.minecraft.world.level.block.entity.trialspawner.TrialSpawner ts) {
            var d = ts.getData();
            return mix(bits(d.getOSpin()), bits(d.getSpin()));
        }
        if (r instanceof net.minecraft.world.level.block.entity.vault.VaultClientData cd)
            return mix(Float.floatToRawIntBits(cd.previousSpin()), Float.floatToRawIntBits(cd.currentSpin()));
        return dev.lull.FieldDiff.fingerprint(r);
    }

    /**
     * Whether fp(r) depends on state inside r that can change while r stays the same object (synched data, a random's
     * seed, containers, arrays, item stacks); otherwise fp(r) is fixed for the object (its identity or an immutable value).
     */
    static boolean content(Object r) {
        if (r instanceof java.util.Collection<?> || r instanceof java.util.Map<?, ?>) return !immutableCollection(r);
        return r instanceof SynchedEntityData || r instanceof net.minecraft.util.RandomSource || r instanceof java.util.Map<?, ?>
                || r instanceof java.util.Collection<?> || r instanceof ItemStack || r instanceof BlockPos.MutableBlockPos
                || r instanceof Number && !(r instanceof Integer || r instanceof Long || r instanceof Double || r instanceof Float
                        || r instanceof Short || r instanceof Byte)
                || r != null && r.getClass().isArray();
    }

    private static long synched(SynchedEntityData d) {
        long h = (boolean) DIRTY.get(d) ? 1 : 0;
        for (SynchedEntityData.DataItem<?> it : (SynchedEntityData.DataItem<?>[]) ITEMS.get(d)) {
            if (it == null) continue;
            Object v = it.getValue();
            long x = v instanceof ItemStack || v instanceof Vec3 || v instanceof BlockPos ? fp(v) : java.util.Objects.hashCode(v);
            h = mix(h, x * 2 + ((boolean) ITEM_DIRTY.get(it) ? 1 : 0));
        }
        return h;
    }

    private static long bits(double d) {
        return Double.doubleToRawLongBits(d);
    }

    private static long mix(long h, long x) {
        return (h ^ x) * 0x100000001B3L + (x >>> 29);
    }
}
