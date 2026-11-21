package dev.lull.optsupport;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

/**
 * SurfacePlan: the top of a surface rule tree decided from the block's height where its conditions are certain.
 *
 * <p>The surface step runs the root rule for every stone block of every column (~130 per column in the overworld). The
 * overworld's root is a sequence of three tests: bedrock (vertical gradient y -64..-59), "above the preliminary
 * surface" (everything that makes grass, sand, snow...) and deepslate (vertical gradient y 0..8). Below the
 * preliminary surface each block walks the three tests to get null (y 8 and above) or deepslate (y 0 and below).
 *
 * <p>Here the root's tests are read from its sources once per chunk; for a block the plan goes through them in order
 * while their outcome is certain from y alone: a vertical gradient is true at or below its lower anchor and false at or
 * above its upper one (VerticalGradientCondition.compute decides exactly so before its random); "above the preliminary
 * surface" is y >= the context's min surface level (AbovePreliminarySurfaceCondition, computed and cached per column by
 * the context as vanilla does); "not" inverts. A test that is certainly false gives null in vanilla's sequence too, so
 * the sequence goes on; a certainly true test whose result is a plain block gives that block. At the first test that is
 * not certain (or of another kind) the vanilla sequence continues from that test, so every rule vanilla would evaluate
 * is still evaluated in the same order with the same context. The skipped tests are lazy conditions or plain ones
 * without side effects (a vertical gradient's random is a fresh positional one per call).
 *
 * <p>Only for the root rule SurfaceSystem.buildSurface builds for its chunk, whose blocks are passed with the context's
 * own y (updateY precedes every call).
 */
public final class SurfacePlan {
    /** a SurfaceRules.SequenceRule that can carry a plan (the root of a chunk's surface) */
    public interface Holder {
        void sleep$plan(SurfacePlan plan);

        int sleep$size();

        /** the plan set on this rule, or null */
        SurfacePlan sleep$planOrNull();
    }

    /** SurfaceRules.Context: its min surface level (the per-column cached one) and its WorldGenerationContext */
    public interface ContextAccess {
        int sleep$minSurfaceLevel();

        WorldGenerationContext sleep$genContext();
    }

    public static final int NULL = -1, STATE = -2;
    private static final int GRADIENT = 0, ABOVE = 1, OTHER = 2;
    private static final String P = "net.minecraft.world.level.levelgen.SurfaceRules$";
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();

    private final ContextAccess ctx;
    private final int n;
    private final int[] kind, lo, hi;
    private final boolean[] neg;
    private final BlockState[] states;
    private BlockState state;
    private static volatile boolean logged;

    private SurfacePlan(ContextAccess ctx, int n) {
        this.ctx = ctx;
        this.n = n;
        kind = new int[n];
        lo = new int[n];
        hi = new int[n];
        neg = new boolean[n];
        states = new BlockState[n];
    }

    private static Object get(Object record, String component) throws ReflectiveOperationException {
        // 1.20.1: the record's field through Reflect (SRG names in production), not its accessor by name
        return Reflect.get(record, component);
    }

    /** the plan for the rule built from this root source with this context, or null when the root is not a test sequence */
    @Nullable
    public static SurfacePlan of(Object rootSource, Object context, Object rule) {
        try {
            if (!rootSource.getClass().getName().equals(P + "SequenceRuleSource") || !(rule instanceof Holder h)
                    || !(context instanceof ContextAccess ca)) return null;
            List<?> seq = (List<?>) get(rootSource, "sequence");
            if (seq.size() < 2 || h.sleep$size() != seq.size()) return null;
            SurfacePlan p = new SurfacePlan(ca, seq.size());
            WorldGenerationContext wgc = ca.sleep$genContext();
            boolean any = false;
            for (int k = 0; k < seq.size(); k++) {
                Object e = seq.get(k);
                p.kind[k] = OTHER;
                if (!e.getClass().getName().equals(P + "TestRuleSource")) continue;
                Object cond = get(e, "ifTrue");
                boolean neg = false;
                while (cond.getClass().getName().equals(P + "NotConditionSource")) {
                    neg = !neg;
                    cond = get(cond, "target");
                }
                String cn = cond.getClass().getName();
                if (cn.equals(P + "VerticalGradientConditionSource")) {
                    p.kind[k] = GRADIENT;
                    p.lo[k] = ((VerticalAnchor) get(cond, "trueAtAndBelow")).resolveY(wgc);
                    p.hi[k] = ((VerticalAnchor) get(cond, "falseAtAndAbove")).resolveY(wgc);
                } else if (cn.equals(P + "AbovePreliminarySurface")) {
                    p.kind[k] = ABOVE;
                } else {
                    continue;
                }
                any = true;
                p.neg[k] = neg;
                Object then = get(e, "thenRun");
                if (then.getClass().getName().equals(P + "BlockRuleSource")) p.states[k] = (BlockState) get(then, "resultState");
            }
            if (!any) return null;
            h.sleep$plan(p);
            if (!logged) {
                logged = true;
                System.out.println("[sleep/opt] SurfacePlan: root of " + seq.size() + " rules, kinds " + java.util.Arrays.toString(p.kind)
                        + " lo " + java.util.Arrays.toString(p.lo) + " hi " + java.util.Arrays.toString(p.hi));
            }
            return p;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * For a block at y: NULL (every test certainly false), STATE (a certainly true test gives state()), or the index of
     * the first test the vanilla sequence must evaluate.
     */
    public int eval(int y) {
        for (int k = 0; k < n; k++) {
            int c;
            switch (kind[k]) {
                case GRADIENT -> c = y <= lo[k] ? 1 : y >= hi[k] ? 0 : 2;
                case ABOVE -> c = y >= ctx.sleep$minSurfaceLevel() ? 1 : 0;
                default -> c = 2;
            }
            if (c == 2) return k;
            if (neg[k]) c ^= 1;
            if (c == 0) continue;
            BlockState s = states[k];
            if (s == null) return k;
            state = s;
            return STATE;
        }
        return NULL;
    }

    public BlockState state() {
        return state;
    }
}
