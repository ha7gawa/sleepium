package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * -Dlab.feat.verify=true (measurement only): every modifier step PlacementWalk takes by its own code is checked
 * against vanilla's getPositions on the same input in the same world. Before the walk's own code runs, vanilla's
 * getPositions runs with the same random (its stream collected: for the modifiers the walk handles, all of
 * getPositions' random draws and world reads happen in the call), the random's whole state (every field of the
 * random object graph: the Xoroshiro halves, WorldgenRandom's count, the legacy seed and both gaussian caches) is
 * kept, then put back to what it was before; the walk's code then runs for real and its positions and the random's
 * state after its draws must equal vanilla's. Modifiers read the world but do not write it, so running vanilla's
 * first changes nothing the walk sees. Counts printed every 2^20 checks and at the first mismatches.
 */
public final class PlacementVerify {
    public static final boolean ON = Boolean.getBoolean("lab.feat.verify");
    private static final AtomicLong CHECKS = new AtomicLong(), BAD = new AtomicLong();

    private final List<BlockPos> vanilla;
    private final Object[] after;
    private final PlacementModifier m;
    private final BlockPos in;

    private PlacementVerify(PlacementModifier m, BlockPos in, List<BlockPos> vanilla, Object[] after) {
        this.m = m;
        this.in = in;
        this.vanilla = vanilla;
        this.after = after;
    }

    /** vanilla's answer for this step; the random is back in its state before */
    public static PlacementVerify before(PlacementModifier m, PlacementContext ctx, RandomSource r, BlockPos pos) {
        List<Field[]> layout = new ArrayList<>();
        List<Object> owners = new ArrayList<>();
        collect(r, layout, owners, new IdentityHashMap<>());
        Object[] s0 = save(layout, owners);
        List<BlockPos> v;
        try (Stream<BlockPos> s = m.getPositions(ctx, r, pos)) {
            v = s == null ? List.of() : s.map(BlockPos::immutable).toList();
        }
        Object[] s1 = save(layout, owners);
        restore(layout, owners, s0);
        return new PlacementVerify(m, pos.immutable(), v, s1);
    }

    /** the walk produced these positions (count copies of p; none when p is null), after its own draws */
    public void produced(RandomSource r, BlockPos p, int count) {
        List<Field[]> layout = new ArrayList<>();
        List<Object> owners = new ArrayList<>();
        collect(r, layout, owners, new IdentityHashMap<>());
        Object[] now = save(layout, owners);
        boolean ok = Arrays.equals(now, after);
        int n = p == null ? 0 : Math.max(0, count);   // IntStream.range(0, negative) is empty, as the walk's loop
        ok &= vanilla.size() == n;
        for (int i = 0; ok && i < n; i++) ok = vanilla.get(i).equals(p);
        long c = CHECKS.incrementAndGet();
        if (!ok) {
            long b = BAD.incrementAndGet();
            if (b <= 20)
                System.out.println("[sleep/featverify] MISMATCH " + m.getClass().getSimpleName() + " at " + in + ": vanilla " + vanilla
                        + " walk " + (p == null ? "[]" : count + " x " + p) + ", random " + (Arrays.equals(now, after) ? "same" : "differs "
                        + Arrays.toString(after) + " vs " + Arrays.toString(now)));
        }
        if ((c & ((1 << 20) - 1)) == 0 || !ok && BAD.get() <= 20)
            System.out.println("[sleep/featverify] " + c + " placement steps checked, " + BAD.get() + " mismatches");
    }

    public static String summary() {
        return CHECKS.get() + " placement steps checked, " + BAD.get() + " mismatches";
    }

    // ---- the random's state, by reflection over its object graph ----

    private static boolean randomish(Class<?> c) {
        String n = c.getName();
        return n.startsWith("net.minecraft.") && (n.contains("Random") || n.contains("Xoroshiro") || n.contains("Gaussian"));
    }

    private static void collect(Object o, List<Field[]> layout, List<Object> owners, IdentityHashMap<Object, Boolean> seen) {
        if (seen.put(o, Boolean.TRUE) != null) return;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Class<?> t = f.getType();
                if (t.isPrimitive() || t == AtomicLong.class) {
                    layout.add(new Field[]{f});
                    owners.add(o);
                    continue;
                }
                try {
                    Object v = f.get(o);
                    if (v != null && randomish(v.getClass())) collect(v, layout, owners, seen);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static Object[] save(List<Field[]> layout, List<Object> owners) {
        Object[] out = new Object[layout.size()];
        try {
            for (int i = 0; i < out.length; i++) {
                Object v = layout.get(i)[0].get(owners.get(i));
                out[i] = v instanceof AtomicLong a ? (Object) a.get() : v;
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static void restore(List<Field[]> layout, List<Object> owners, Object[] values) {
        try {
            for (int i = 0; i < values.length; i++) {
                Field f = layout.get(i)[0];
                if (f.getType() == AtomicLong.class) ((AtomicLong) f.get(owners.get(i))).set((Long) values[i]);
                else f.set(owners.get(i), values[i]);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
