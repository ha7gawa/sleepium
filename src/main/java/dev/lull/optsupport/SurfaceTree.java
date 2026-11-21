package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

/**
 * SurfaceTree (-Dlab.opt.SurfaceTree, with SurfaceFastMixin; 11-21, lab): the chunk's surface rule tree with its stone
 * depth tests decided per block.
 *
 * <p>A stone depth test (StoneDepthCheck's condition: ON_FLOOR, UNDER_FLOOR, DEEP_UNDER_FLOOR, ... in the overworld) is
 * {@code depth <= 1 + offset + (surface depth if added) + (int) Mth.map(surface secondary, -1, 1, 0, range)} with depth
 * the context's stone depth above (floor) or below (ceiling): the per-column part is the context's surface depth and
 * surface secondary (both per column, cached per column by the context), the per-block part is the depth our loop passes
 * to updateY. Per column the threshold of every distinct test is computed with vanilla's formula (calling
 * getSurfaceSecondary, a per-column pure noise value the context caches, possibly earlier than vanilla would); per block
 * the tests' values are a bit set (the signature). For a signature the tree is pruned once per chunk: a test rule whose
 * stone depth test is false is dropped (vanilla returns null for it), one whose test is true is replaced by its followup,
 * a test rule whose followup was pruned to nothing is dropped (its condition is not evaluated - conditions are lazy
 * caches of pure values, nothing observes whether they ran), a sequence keeps its other members in order. The pruned
 * tree evaluates the remaining conditions (the original condition objects, same context) and leaves (state rules as
 * constants, other rules through their own tryApply) in vanilla's order. Deep below the surface (stone depth above
 * larger than every floor test's threshold) the overworld tree becomes the badlands bands branch only.
 *
 * <p>Built from the rule objects the chunk's context made (record classes made public by our access transformer); a tree
 * with more than 8 distinct stone depth tests is not taken (vanilla's tree runs).
 */
public final class SurfaceTree {
    public static final boolean ON = Boolean.getBoolean("lab.opt.SurfaceTree");
    private static final ConcurrentHashMap<Class<?>, Field> OUTER = new ConcurrentHashMap<>();
    private static final int MAX = 8;
    private static volatile boolean logged;

    private final SurfaceRules.Context ctx;
    private final SurfaceRules.SurfaceRule[] roots;
    private final IdentityHashMap<SurfaceRules.Condition, Integer> depthIndex = new IdentityHashMap<>();
    private int nc;
    private final int[] off = new int[MAX], range = new int[MAX], t = new int[MAX];
    private final boolean[] add = new boolean[MAX], ceil = new boolean[MAX];
    private final Node[][] cache = new Node[1 << MAX][];

    private SurfaceTree(SurfaceRules.Context ctx, SurfaceRules.SurfaceRule[] roots) {
        this.ctx = ctx;
        this.roots = roots;
    }

    /** the tree for this chunk's root rule (built by ctx), or null */
    @Nullable
    public static SurfaceTree of(SurfaceRules.SurfaceRule root, SurfaceRules.Context ctx) {
        SurfaceRules.SurfaceRule[] roots = root instanceof SurfaceRules.SequenceRule s
                ? s.rules().toArray(new SurfaceRules.SurfaceRule[0]) : new SurfaceRules.SurfaceRule[] {root};
        SurfaceTree st = new SurfaceTree(ctx, roots);
        try {
            for (SurfaceRules.SurfaceRule r : roots) if (!st.scan(r)) return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!logged) {
                logged = true;
                System.out.println("[sleep/opt] SurfaceTree: not taken, " + e);
            }
            return null;
        }
        if (!logged) {
            logged = true;
            System.out.println("[sleep/opt] SurfaceTree: " + roots.length + " root rules, " + st.nc + " distinct stone depth tests");
        }
        return st;
    }

    private boolean scan(SurfaceRules.SurfaceRule r) throws ReflectiveOperationException {
        if (r instanceof SurfaceRules.SequenceRule s) {
            for (SurfaceRules.SurfaceRule x : s.rules()) if (!scan(x)) return false;
            return true;
        }
        if (r instanceof SurfaceRules.TestRule t) return scanCondition(t.condition()) && scan(t.followup());
        return true;
    }

    private boolean scanCondition(SurfaceRules.Condition c) throws ReflectiveOperationException {
        while (c instanceof SurfaceRules.NotCondition n) c = n.target();
        if (depthIndex.containsKey(c)) return true;
        SurfaceRules.StoneDepthCheck p = depthSource(c);
        if (p == null) return true;
        boolean cl = p.surfaceType() == CaveSurface.CEILING;
        int idx = -1;
        for (int i = 0; i < nc; i++)
            if (off[i] == p.offset() && add[i] == p.addSurfaceDepth() && range[i] == p.secondaryDepthRange() && ceil[i] == cl) idx = i;
        if (idx < 0) {
            if (nc == MAX) return false;
            idx = nc++;
            off[idx] = p.offset();
            add[idx] = p.addSurfaceDepth();
            range[idx] = p.secondaryDepthRange();
            ceil[idx] = cl;
        }
        depthIndex.put(c, idx);
        return true;
    }

    /** the StoneDepthCheck a condition was made by (vanilla's local class StoneDepthCondition), else null */
    @Nullable
    private static SurfaceRules.StoneDepthCheck depthSource(SurfaceRules.Condition c) throws ReflectiveOperationException {
        Class<?> k = c.getClass();
        if (k.getEnclosingClass() != SurfaceRules.StoneDepthCheck.class || !k.getSimpleName().equals("StoneDepthCondition")) return null;
        Field f = OUTER.get(k);
        if (f == null) {
            for (Field g : k.getDeclaredFields()) {
                if (g.getType() == SurfaceRules.StoneDepthCheck.class) {
                    g.setAccessible(true);
                    f = g;
                    break;
                }
            }
            if (f == null) throw new NoSuchFieldException("StoneDepthCondition's StoneDepthCheck");
            OUTER.put(k, f);
        }
        return (SurfaceRules.StoneDepthCheck) f.get(c);
    }

    /** the tests' thresholds for the current column (after the context's updateXZ) */
    public void column() {
        for (int c = 0; c < nc; c++) {
            int j = add[c] ? ctx.surfaceDepth : 0;
            int k = range[c] == 0 ? 0 : (int) Mth.map(ctx.getSurfaceSecondary(), -1.0, 1.0, 0.0, (double) range[c]);
            t[c] = 1 + off[c] + j + k;
        }
    }

    /** vanilla's root sequence from rule index start for a block with these stone depths (after updateY) */
    @Nullable
    public BlockState apply(int start, int above, int below, int x, int y, int z) {
        int sig = 0;
        for (int c = 0; c < nc; c++) if ((ceil[c] ? below : above) <= t[c]) sig |= 1 << c;
        Node[] p = cache[sig];
        if (p == null) {
            p = new Node[roots.length];
            for (int i = 0; i < roots.length; i++) p[i] = prune(roots[i], sig);
            cache[sig] = p;
        }
        for (int i = start; i < p.length; i++) {
            Node n = p[i];
            if (n != null) {
                BlockState s = n.eval(x, y, z);
                if (s != null) return s;
            }
        }
        return null;
    }

    @Nullable
    private Node prune(SurfaceRules.SurfaceRule r, int sig) {
        if (r instanceof SurfaceRules.SequenceRule s) {
            List<SurfaceRules.SurfaceRule> l = s.rules();
            ArrayList<Node> out = new ArrayList<>(l.size());
            for (SurfaceRules.SurfaceRule x : l) {
                Node n = prune(x, sig);
                if (n != null) out.add(n);
            }
            if (out.isEmpty()) return null;
            if (out.size() == 1) return out.get(0);
            return new Seq(out.toArray(new Node[0]));
        }
        if (r instanceof SurfaceRules.TestRule t) {
            int v = value(t.condition(), sig);
            if (v == 0) return null;
            Node f = prune(t.followup(), sig);
            if (v == 1 || f == null) return f;
            return new Test(t.condition(), f);
        }
        if (r instanceof SurfaceRules.StateRule s) return new Const(s.state());
        return new Leaf(r);
    }

    /** 0 false, 1 true (a stone depth test, or "not" of one), 2 to be evaluated */
    private int value(SurfaceRules.Condition c, int sig) {
        boolean neg = false;
        while (c instanceof SurfaceRules.NotCondition n) {
            neg = !neg;
            c = n.target();
        }
        Integer i = depthIndex.get(c);
        if (i == null) return 2;
        boolean v = ((sig >>> i) & 1) != 0;
        return v != neg ? 1 : 0;
    }

    abstract static class Node {
        @Nullable
        abstract BlockState eval(int x, int y, int z);
    }

    static final class Seq extends Node {
        private final Node[] n;

        Seq(Node[] n) {
            this.n = n;
        }

        @Override
        BlockState eval(int x, int y, int z) {
            for (Node c : n) {
                BlockState s = c.eval(x, y, z);
                if (s != null) return s;
            }
            return null;
        }
    }

    static final class Test extends Node {
        private final SurfaceRules.Condition c;
        private final Node f;

        Test(SurfaceRules.Condition c, Node f) {
            this.c = c;
            this.f = f;
        }

        @Override
        BlockState eval(int x, int y, int z) {
            return c.test() ? f.eval(x, y, z) : null;
        }
    }

    static final class Const extends Node {
        private final BlockState s;

        Const(BlockState s) {
            this.s = s;
        }

        @Override
        BlockState eval(int x, int y, int z) {
            return s;
        }
    }

    static final class Leaf extends Node {
        private final SurfaceRules.SurfaceRule r;

        Leaf(SurfaceRules.SurfaceRule r) {
            this.r = r;
        }

        @Override
        BlockState eval(int x, int y, int z) {
            return r.tryApply(x, y, z);
        }
    }
}
