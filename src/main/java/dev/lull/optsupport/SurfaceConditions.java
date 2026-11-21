package dev.lull.optsupport;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * SurfaceConditions: one condition object per distinct condition of a surface rule tree. Vanilla makes a new lazy
 * condition for every place a condition source appears in the tree (the overworld's ON_FLOOR appears 6 times, some biome
 * tests 2-3 times), and each computes its value again for the same block. Equal sources of the vanilla kinds compute the
 * same pure function of the context (its block position, depths, water height, surface depth, the biome at the block,
 * the seed's noises and positional randoms), cached by the same update counter: one shared instance gives every place
 * the same value, computed once.
 *
 * <p>Only vanilla's own condition sources are shared (records and enums with value equality; BiomeConditionSource
 * compares its biome lists): a mod's source may keep state, so it keeps its own instances. "not" is shared when what it
 * inverts is.
 */
public final class SurfaceConditions {
    /** a SurfaceRules.Context with its table of shared conditions */
    public interface Table {
        Map<Object, Object> sleep$conditions();
    }

    private static final String P = "net.minecraft.world.level.levelgen.SurfaceRules$";
    private static final Set<String> PURE = Set.of(P + "BiomeConditionSource", P + "NoiseThresholdConditionSource",
            P + "VerticalGradientConditionSource", P + "YConditionSource", P + "WaterConditionSource", P + "StoneDepthCheck",
            P + "Temperature", P + "Steep", P + "Hole", P + "AbovePreliminarySurface");
    private static final String NOT = P + "NotConditionSource";
    private static volatile Method notTarget;

    private SurfaceConditions() {}

    static boolean pure(Object source) {
        String n = source.getClass().getName();
        if (PURE.contains(n)) return true;
        if (!n.equals(NOT)) return false;
        try {
            Method m = notTarget;
            if (m == null) {
                m = source.getClass().getDeclaredMethod("target");
                m.setAccessible(true);
                notTarget = m;
            }
            return pure(m.invoke(source));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** source.apply(context), shared with an equal source's condition made earlier for the same context */
    public static <S> Object shared(S source, Object context, BiFunction<S, Object, Object> apply) {
        if (!(context instanceof Table t) || !pure(source)) return apply.apply(source, context);
        Map<Object, Object> m = t.sleep$conditions();
        Object c = m.get(source);
        if (c == null) {
            c = apply.apply(source, context);
            m.put(source, c);
        }
        return c;
    }

    public static Map<Object, Object> newTable() {
        return new HashMap<>();
    }
}
