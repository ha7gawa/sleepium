package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

/**
 * SurfaceTree (-Dlab.opt.SurfaceTree, with SurfaceFastMixin; 11-21, lab): the chunk's surface rule tree with its depth,
 * height and water tests decided per block.
 *
 * <p>Three kinds of vanilla tests are plain arithmetic on what our loop passes to updateY (the block's y, the stone
 * depth above and below, the water height) and per-column values the context holds (surface depth, surface secondary):
 * <ul>
 * <li>a stone depth test (StoneDepthCheck: ON_FLOOR, UNDER_FLOOR, ...): {@code depth <= 1 + offset + (surface depth if
 * added) + (int) Mth.map(surface secondary, -1, 1, 0, range)}, depth = stone depth above (floor) or below (ceiling);</li>
 * <li>a height test (YConditionSource: yBlockCheck / yStartCheck): {@code y + (stone depth above if added) >=
 * anchor.resolveY(generation context) + surface depth * multiplier};</li>
 * <li>a water test (WaterConditionSource): {@code water height == MIN_VALUE || y + (stone depth above if added) >=
 * water height + offset + surface depth * multiplier}.</li>
 * </ul>
 * Per column the per-column part of every distinct test is computed with vanilla's formula (getSurfaceSecondary is a
 * per-column pure noise value the context caches; it may be asked earlier than vanilla would); per block the tests'
 * values form a bit set (the signature). For a signature the tree is pruned once per chunk: a test rule whose test is
 * false is dropped (vanilla returns null for it), one whose test is true is replaced by its followup, a test rule whose
 * followup was pruned to nothing is dropped (its condition is not evaluated - conditions are lazy caches of pure values,
 * nothing observes whether they ran), a sequence keeps its other members in order. The pruned tree evaluates the
 * remaining conditions (the original objects, same context) and leaves (state rules as constants, other rules through
 * their own tryApply) in vanilla's order. A block whose pruned tree is empty gets no updateY at all (SurfaceWriter): the
 * tree would evaluate nothing, so the context's per-block state is never read before the next updateY overwrites it.
 *
 * <p>Built from the rule objects the chunk's context made (record classes made public by our access transformer); a tree
 * with more than 24 distinct decidable tests is not taken (vanilla's tree runs).
 */
public final class SurfaceTree {
    public static final boolean ON = Boolean.getBoolean("lab.opt.SurfaceTree");
    /**
     * -Dlab.surface.treeHeights: height and water tests decided too. Exact (verified), but slower on .103: overworld
     * surface 3356 / 3262 -> 3810 / 3955 us/call (15 tests per block instead of 5; the overworld's deep blocks keep the
     * badlands biome test anyway), nether 3717 / 3676 -> 3639 / 3876 - off by default
     */
    private static final boolean HEIGHTS = Boolean.getBoolean("lab.surface.treeHeights");
    private static final ConcurrentHashMap<Class<?>, Field> OUTER = new ConcurrentHashMap<>();
    private static final int MAX = 24, DEPTH = 0, Y = 1, WATER = 2;
    private static volatile boolean logged;

    private final SurfaceRules.Context ctx;
    private final SurfaceRules.SurfaceRule[] roots;
    private final IdentityHashMap<SurfaceRules.Condition, Integer> index = new IdentityHashMap<>();
    private int nc;
    /** per test: kind, offset (depth, water) or resolved anchor y (height), secondary range (depth), multiplier (height, water) */
    private final int[] kind = new int[MAX], off = new int[MAX], range = new int[MAX], mult = new int[MAX], t = new int[MAX];
    private final boolean[] add = new boolean[MAX], ceil = new boolean[MAX];
    private final Object[] key = new Object[MAX];
    // signature -> pruned roots (open addressing)
    private int[] sigs = new int[64];
    private Node[][] trees = new Node[64][];
    private int used;
    private static final Node[] EMPTY = new Node[0];

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
            int[] k = new int[3];
            for (int i = 0; i < st.nc; i++) k[st.kind[i]]++;
            System.out.println("[sleep/opt] SurfaceTree: " + roots.length + " root rules, decidable tests: " + k[DEPTH] + " stone depth, "
                    + k[Y] + " height, " + k[WATER] + " water");
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
        if (index.containsKey(c)) return true;
        Object src = source(c);
        if (src == null || !HEIGHTS && !(src instanceof SurfaceRules.StoneDepthCheck)) return true;
        int idx = -1;
        for (int i = 0; i < nc; i++) if (key[i].equals(src)) idx = i;
        if (idx < 0) {
            if (nc == MAX) return false;
            idx = nc++;
            key[idx] = src;
            if (src instanceof SurfaceRules.StoneDepthCheck p) {
                kind[idx] = DEPTH;
                off[idx] = p.offset();
                add[idx] = p.addSurfaceDepth();
                range[idx] = p.secondaryDepthRange();
                ceil[idx] = p.surfaceType() == CaveSurface.CEILING;
            } else if (src instanceof SurfaceRules.YConditionSource p) {
                kind[idx] = Y;
                off[idx] = p.anchor().resolveY(ctx.context);
                mult[idx] = p.surfaceDepthMultiplier();
                add[idx] = p.addStoneDepth();
            } else {
                SurfaceRules.WaterConditionSource p = (SurfaceRules.WaterConditionSource) src;
                kind[idx] = WATER;
                off[idx] = p.offset();
                mult[idx] = p.surfaceDepthMultiplier();
                add[idx] = p.addStoneDepth();
            }
        }
        index.put(c, idx);
        return true;
    }

    /**
     * the source record a condition was made by (vanilla's local classes StoneDepthCondition, YCondition,
     * WaterCondition hold their source as the enclosing instance), else null
     */
    @Nullable
    private static Object source(SurfaceRules.Condition c) throws ReflectiveOperationException {
        Class<?> k = c.getClass(), outer = k.getEnclosingClass();
        String n = k.getSimpleName();
        if (!(outer == SurfaceRules.StoneDepthCheck.class && n.equals("StoneDepthCondition")
                || outer == SurfaceRules.YConditionSource.class && n.equals("YCondition")
                || outer == SurfaceRules.WaterConditionSource.class && n.equals("WaterCondition"))) return null;
        Field f = OUTER.get(k);
        if (f == null) {
            for (Field g : k.getDeclaredFields()) {
                if (g.getType() == outer) {
                    g.setAccessible(true);
                    f = g;
                    break;
                }
            }
            if (f == null) throw new NoSuchFieldException(n + "'s source");
            OUTER.put(k, f);
        }
        return f.get(c);
    }

    /** the tests' per-column parts for the current column (after the context's updateXZ) */
    public void column() {
        int sd = ctx.surfaceDepth;
        for (int c = 0; c < nc; c++) {
            switch (kind[c]) {
                case DEPTH -> {
                    int j = add[c] ? sd : 0;
                    int k = range[c] == 0 ? 0 : (int) Mth.map(ctx.getSurfaceSecondary(), -1.0, 1.0, 0.0, (double) range[c]);
                    t[c] = 1 + off[c] + j + k;
                }
                case Y -> t[c] = off[c] + sd * mult[c];
                default -> t[c] = off[c] + sd * mult[c];
            }
        }
    }

    /**
     * the pruned root rules for a block (y, stone depths, water height as passed to updateY), or null when none of the
     * rules from index start would evaluate anything
     */
    @Nullable
    public Node[] prepare(int start, int y, int above, int below, int water) {
        int sig = 0;
        for (int c = 0; c < nc; c++) {
            boolean v;
            switch (kind[c]) {
                case DEPTH -> v = (ceil[c] ? below : above) <= t[c];
                case Y -> v = y + (add[c] ? above : 0) >= t[c];
                default -> v = water == Integer.MIN_VALUE || y + (add[c] ? above : 0) >= water + t[c];
            }
            if (v) sig |= 1 << c;
        }
        Node[] p = lookup(sig);
        for (int i = start; i < p.length; i++) {
            Node n = p[i];
            if (n != null) {
                constant = n instanceof Const k ? k.s : null;
                return p;
            }
        }
        return null;
    }

    /**
     * after prepare: the state when the first rule left from start is a plain state rule (the result, with nothing
     * evaluated before it - no updateY needed either), else null
     */
    @Nullable
    public BlockState constant;

    /** vanilla's root sequence from rule index start over pruned rules (after updateY) */
    @Nullable
    public static BlockState eval(Node[] p, int start, int x, int y, int z) {
        for (int i = start; i < p.length; i++) {
            Node n = p[i];
            if (n != null) {
                BlockState s = n.eval(x, y, z);
                if (s != null) return s;
            }
        }
        return null;
    }

    private Node[] lookup(int sig) {
        int mask = sigs.length - 1, h = (sig * 0x9E3779B1) >>> 16 & mask;
        while (true) {
            Node[] p = trees[h];
            if (p == null) break;
            if (sigs[h] == sig) return p;
            h = (h + 1) & mask;
        }
        Node[] p = new Node[roots.length];
        boolean any = false;
        for (int i = 0; i < roots.length; i++) any |= (p[i] = prune(roots[i], sig)) != null;
        if (!any) p = roots.length == 0 ? EMPTY : p;
        if ((used + 1) * 2 > sigs.length) grow();
        mask = sigs.length - 1;
        h = (sig * 0x9E3779B1) >>> 16 & mask;
        while (trees[h] != null) h = (h + 1) & mask;
        sigs[h] = sig;
        trees[h] = p;
        used++;
        return p;
    }

    private void grow() {
        int[] os = sigs;
        Node[][] ot = trees;
        sigs = new int[os.length * 2];
        trees = new Node[os.length * 2][];
        int mask = sigs.length - 1;
        for (int i = 0; i < os.length; i++) {
            if (ot[i] == null) continue;
            int h = (os[i] * 0x9E3779B1) >>> 16 & mask;
            while (trees[h] != null) h = (h + 1) & mask;
            sigs[h] = os[i];
            trees[h] = ot[i];
        }
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

    /** 0 false, 1 true (a decidable test, or "not" of one), 2 to be evaluated */
    private int value(SurfaceRules.Condition c, int sig) {
        boolean neg = false;
        while (c instanceof SurfaceRules.NotCondition n) {
            neg = !neg;
            c = n.target();
        }
        Integer i = index.get(c);
        if (i == null) return 2;
        boolean v = ((sig >>> i) & 1) != 0;
        return v != neg ? 1 : 0;
    }

    public abstract static class Node {
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
