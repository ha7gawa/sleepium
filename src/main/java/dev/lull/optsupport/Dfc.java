package dev.lull.optsupport;

import static org.objectweb.asm.Opcodes.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * DFC (-Dlab.opt.DfcNoiseChunkMixin, 11-21, lab): a NoiseChunk's density-function trees compiled to straight-line
 * bytecode for the two places doFill evaluates them in bulk - the interpolators' corner columns (fillSlice) and the
 * cell caches (selectCellYZ: cacheAllInCell(final density + beardifier) at every block of a cell).
 *
 * <p>Vanilla evaluates a tree by fillArray: each node fills an array from its children's arrays, some children per
 * point through compute(forIndex(i)) - megamorphic calls, a temporary array per ADD, a context hop per point. The
 * values are a function of the point except for the stateful nodes, so the compiled code evaluates the tree point by
 * point, each node doing what its compute does, with vanilla's arithmetic in vanilla's order (the same doubles):
 * Ap2 (ADD a + b; MUL a == 0 ? 0 : a * b; MIN / MAX with the argument's bound short-cut), MulOrAdd, Mapped, Clamp,
 * RangeChoice, Constant, YClampedGradient, Noise, ShiftedNoise (the noise holders called directly). Every other node
 * stays its own object, called per point exactly as vanilla's fillAllDirectly calls it (compute with the NoiseChunk as
 * context, positioned as vanilla positions it: inCellX / Y / Z and arrayIndex for a cell; cellStartBlockY, a counter
 * step, inCellY = 0 and arrayIndex for a corner) - FlatCache, Cache2D, Spline, Shift*, BlendedNoise, Beardifier, End
 * islands, a mod's SimpleFunction; BlendDensity / WeirdScaledSampler call their own transform(context, value).
 *
 * <p>Stateful nodes, where fill and compute can differ: a NoiseInterpolator in a cell is the lerp3 of its corners (its
 * compute and its fill agree); inside a corner tree it is refused (its compute would read the running value). A
 * CacheOnce in a corner tree is the per-point value: vanilla bumps interpolationCounter for every corner point, so its
 * cache only answers for the same point; here once per point (shared by all interpolators, as vanilla shares it by
 * identity). In a cell, vanilla's fill does not step the counter, so a CacheOnce reached by compute would answer a
 * stale value - such cell trees are refused. A Cache2D is called by its compute (cached per column) only when it wraps
 * a function that ignores y (SliceColumns.cache2dAreFlat): then cached and uncached agree; otherwise refused. A node
 * of an unknown kind whose fillArray is not SimpleFunction's fillAllDirectly is refused. A refused tree runs as vanilla.
 *
 * <p>One hidden class per tree shape (node kinds, constants, sharing), a nestmate of NoiseChunk defined with the
 * NoiseChunk's own lookup (it reads the NoiseChunk's and the interpolators' fields directly); each NoiseChunk gets an
 * instance holding its own node objects. -Dlab.dfc.verify: every compiled cell / column is compared bit for bit with
 * vanilla's fillArray on the same NoiseChunk.
 */
public final class Dfc {
    private Dfc() {}

    public static final boolean VERIFY = Boolean.getBoolean("lab.dfc.verify");
    public static final boolean DUMP = Boolean.getBoolean("lab.dfc.dump");
    /** -Dlab.dfc.cells=false / corners=false: one half off */
    public static final boolean CELLS = !"false".equals(System.getProperty("lab.dfc.cells"));
    public static final boolean CORNERS = !"false".equals(System.getProperty("lab.dfc.corners"));
    private static final int SPLIT = Integer.getInteger("lab.dfc.split", 40);

    private static final AtomicLong VALUES = new AtomicLong(), BAD = new AtomicLong();
    public static final AtomicLong BUILT = new AtomicLong(), REFUSED = new AtomicLong();
    private static final Map<String, Boolean> REASONS = new ConcurrentHashMap<>();
    private static final Map<String, Constructor<?>> CLASSES = new ConcurrentHashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger();

    /** NoiseChunk (DfcNoiseChunkMixin): its full-privilege lookup and the corner hook */
    public interface Host {
        MethodHandles.Lookup sleep$dfcLookup();

        /** the column i of the slice by compiled code; false: not compiled (do vanilla's loop) */
        boolean sleep$dfcCorners(boolean slice0, int i);
    }

    /** Base of the generated classes */
    public abstract static class Code {
        protected Code() {}

        public void cell(NoiseChunk nc, double[] out) {
            throw new UnsupportedOperationException();
        }

        public void corners(NoiseChunk nc, boolean slice0, int i) {
            throw new UnsupportedOperationException();
        }
    }

    // ---------------------------------------------------------------- verify

    public static void check(double[] got, double[] want) {
        int bad = 0;
        for (int k = 0; k < want.length; k++) if (Double.doubleToRawLongBits(got[k]) != Double.doubleToRawLongBits(want[k])) bad++;
        long b = bad == 0 ? BAD.get() : BAD.addAndGet(bad);
        long before = VALUES.getAndAdd(want.length), n = before + want.length;
        if ((before >>> 20) != (n >>> 20) || (bad != 0 && b - bad < 20))
            System.out.println("[sleep/opt] dfc verify: " + n + " values, " + b + " differ");
    }

    public static String report() {
        return "dfc: built " + BUILT.get() + ", refused " + REFUSED.get() + ", classes " + CLASSES.size()
                + (VERIFY ? ", verified " + VALUES.get() + " values, " + BAD.get() + " differ" : "");
    }

    private static void refused(String why) {
        REFUSED.incrementAndGet();
        if (REASONS.putIfAbsent(why, Boolean.TRUE) == null) System.out.println("[sleep/opt] dfc: tree left to vanilla: " + why);
    }

    // ---------------------------------------------------------------- IR

    private static final int CONST = 0, YGRAD = 1, NOISE = 2, SHIFTED = 3, AP2 = 4, MULADD = 5, MAPPED = 6, CLAMP = 7, RANGE = 8,
            TRANSFORM = 9, INTERP = 10, ONCE = 11, LEAF = 12;

    private static final class N {
        final int kind;
        int op;            // Ap2 type / MulOrAdd type / Mapped type
        double[] p = NONE; // constants
        N[] kids = NO_KIDS;
        int slot = -1;     // node object field
        int once = -1;     // ONCE id
        int size;          // nodes in the subtree (ONCE counts 1)
        int method = -1;   // outlined into this method

        N(int kind) {
            this.kind = kind;
        }
    }

    private static final double[] NONE = new double[0];
    private static final N[] NO_KIDS = new N[0];

    private static String simple(Object f) {
        String cn = f.getClass().getName();
        return cn.startsWith("net.minecraft.world.level.levelgen.") ? cn.substring("net.minecraft.world.level.levelgen.".length()) : cn;
    }

    private static Object get(Object o, String name) {
        return Reflect.get(o, name);
    }

    private static double d(Object o, String name) {
        return ((Number) get(o, name)).doubleValue();
    }

    /** a tree walk for one NoiseChunk: IR, the node objects (slots), the shape key */
    private static final class Walk {
        final boolean cell;
        final List<Object> slots = new ArrayList<>();
        final List<String> slotTypes = new ArrayList<>();
        final IdentityHashMap<Object, Integer> slotOf = new IdentityHashMap<>();
        final IdentityHashMap<Object, N> onceOf = new IdentityHashMap<>();
        final List<N> onces = new ArrayList<>();
        int[] islots;
        String why;

        Walk(boolean cell) {
            this.cell = cell;
        }

        int slot(Object o, String type) {
            Integer s = slotOf.get(o);
            if (s != null) {
                if (!slotTypes.get(s).equals(type)) slotTypes.set(s, "net/minecraft/world/level/levelgen/DensityFunction");
                return s;
            }
            slotOf.put(o, slots.size());
            slots.add(o);
            slotTypes.add(type);
            return slots.size() - 1;
        }

        N fail(String w) {
            if (why == null) why = w;
            return null;
        }

        N leaf(Object o) {
            N n = new N(LEAF);
            n.slot = slot(o, leafType(o.getClass()));
            n.size = 1;
            return n;
        }

        N walk(Object o) {
            if (why != null) return null;
            String s = simple(o);
            N n;
            switch (s) {
                case "DensityFunctions$Constant" -> {
                    n = new N(CONST);
                    n.p = new double[] {d(o, "value")};
                }
                case "DensityFunctions$BeardifierMarker", "DensityFunctions$BlendOffset" -> {
                    n = new N(CONST);   // compute 0.0, fillArray fills 0.0
                    n.p = new double[] {0.0};
                }
                case "DensityFunctions$BlendAlpha" -> {
                    n = new N(CONST);
                    n.p = new double[] {1.0};
                }
                case "DensityFunctions$YClampedGradient" -> {
                    n = new N(YGRAD);
                    n.p = new double[] {(double) (Integer) get(o, "fromY"), (double) (Integer) get(o, "toY"), d(o, "fromValue"), d(o, "toValue")};
                }
                case "DensityFunctions$Noise" -> {
                    n = new N(NOISE);
                    n.slot = slot(get(o, "noise"), NH);
                    n.p = new double[] {d(o, "xzScale"), d(o, "yScale")};
                }
                case "DensityFunctions$ShiftedNoise" -> {
                    n = new N(SHIFTED);
                    n.slot = slot(get(o, "noise"), NH);
                    n.p = new double[] {d(o, "xzScale"), d(o, "yScale")};
                    n.kids = new N[] {walk(get(o, "shiftX")), walk(get(o, "shiftY")), walk(get(o, "shiftZ"))};
                }
                case "DensityFunctions$Ap2" -> {
                    n = new N(AP2);
                    n.op = ((Enum<?>) get(o, "type")).ordinal();   // ADD, MUL, MIN, MAX
                    DensityFunction a2 = (DensityFunction) get(o, "argument2");
                    n.p = new double[] {n.op == 2 ? a2.minValue() : n.op == 3 ? a2.maxValue() : 0.0};
                    n.kids = new N[] {walk(get(o, "argument1")), walk(a2)};
                }
                case "DensityFunctions$MulOrAdd" -> {
                    n = new N(MULADD);
                    n.op = ((Enum<?>) get(o, "specificType")).ordinal();   // MUL, ADD
                    n.p = new double[] {d(o, "argument")};
                    n.kids = new N[] {walk(get(o, "input"))};
                }
                case "DensityFunctions$Mapped" -> {
                    n = new N(MAPPED);
                    String t = ((Enum<?>) get(o, "type")).name();
                    n.op = switch (t) {
                        case "ABS" -> 0;
                        case "SQUARE" -> 1;
                        case "CUBE" -> 2;
                        case "HALF_NEGATIVE" -> 3;
                        case "QUARTER_NEGATIVE" -> 4;
                        case "SQUEEZE" -> 5;
                        default -> -1;
                    };
                    if (n.op < 0) return fail("mapped type " + t);
                    n.kids = new N[] {walk(get(o, "input"))};
                }
                case "DensityFunctions$Clamp" -> {
                    n = new N(CLAMP);
                    n.p = new double[] {d(o, "minValue"), d(o, "maxValue")};
                    n.kids = new N[] {walk(get(o, "input"))};
                }
                case "DensityFunctions$RangeChoice" -> {
                    n = new N(RANGE);
                    n.p = new double[] {d(o, "minInclusive"), d(o, "maxExclusive")};
                    n.kids = new N[] {walk(get(o, "input")), walk(get(o, "whenInRange")), walk(get(o, "whenOutOfRange"))};
                }
                case "DensityFunctions$BlendDensity", "DensityFunctions$WeirdScaledSampler" -> {
                    n = new N(TRANSFORM);
                    n.slot = slot(o, "net/minecraft/world/level/levelgen/" + s);
                    n.kids = new N[] {walk(get(o, "input"))};
                }
                case "DensityFunctions$HolderHolder" -> {
                    return walk(((Holder<?>) get(o, "function")).value());
                }
                case "DensityFunctions$Marker" -> {
                    return walk(get(o, "wrapped"));
                }
                case "NoiseChunk$NoiseInterpolator" -> {
                    if (!cell) return fail("an interpolator inside a corner tree");
                    n = new N(INTERP);
                    n.slot = slot(o, NI);
                }
                case "NoiseChunk$CacheOnce" -> {
                    if (cell) return fail("cache_once in a cell tree");
                    N had = onceOf.get(o);
                    if (had != null) return had;
                    n = new N(ONCE);
                    n.once = onces.size();
                    onceOf.put(o, n);
                    onces.add(n);
                    n.kids = new N[] {walk(get(o, "function"))};
                    if (why != null) return null;
                    n.size = 1;
                    return n;
                }
                case "NoiseChunk$CacheAllInCell" -> {
                    return fail("a cell cache inside a tree");
                }
                case "NoiseChunk$Cache2D" -> {
                    if (!SliceColumns.cache2dAreFlat(List.of((DensityFunction) o))) return fail("cache_2d over a function of y");
                    if (cell && hasOnce(get(o, "function"), new IdentityHashMap<>())) return fail("cache_once under a cache_2d in a cell tree");
                    return leaf(o);
                }
                case "NoiseChunk$FlatCache", "NoiseChunk$BlendAlpha", "NoiseChunk$BlendOffset", "DensityFunctions$Spline", "DensityFunctions$Shift",
                     "DensityFunctions$ShiftA", "DensityFunctions$ShiftB", "synth.BlendedNoise", "Beardifier",
                     "DensityFunctions$EndIslandDensityFunction", "dev.lull.optsupport.ColumnMemo$Memo" -> {
                    // vanilla's fillArray for these is fillAllDirectly: compute per point
                    return leaf(o);
                }
                default -> {
                    if (o instanceof DensityFunction && simpleFill(o.getClass())) return leaf(o);
                    return fail("node kind " + o.getClass().getName());
                }
            }
            if (why != null) return null;
            int size = 1;
            for (N k : n.kids) size += k.size;
            n.size = size;
            return n;
        }
    }

    private static final String NC = "net/minecraft/world/level/levelgen/NoiseChunk";
    private static final String NI = "net/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator";
    private static final String NH = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String DF = "net/minecraft/world/level/levelgen/DensityFunction";
    private static final String FC = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String MTH = "net/minecraft/util/Mth";
    private static final String CODE = "dev/lull/optsupport/Dfc$Code";
    private static final String EVAL = "(L" + NC + ";IIIDDD)D";

    private static boolean simpleFill(Class<?> c) {
        try {
            Method m = c.getMethod("fillArray", double[].class, DensityFunction.ContextProvider.class);
            return m.getDeclaringClass() == DensityFunction.SimpleFunction.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasOnce(Object o, IdentityHashMap<Object, Boolean> seen) {
        if (o == null || seen.put(o, Boolean.TRUE) != null) return false;
        String s = simple(o);
        if (s.equals("NoiseChunk$CacheOnce")) return true;
        if (s.equals("DensityFunctions$Spline")) return true;   // not looked into: refuse
        for (String f : new String[] {"input", "argument1", "argument2", "whenInRange", "whenOutOfRange", "shiftX", "shiftY", "shiftZ", "function",
                "wrapped", "noiseFiller"}) {
            Object c;
            try {
                c = get(o, f);
            } catch (RuntimeException e) {
                continue;
            }
            if (c instanceof Holder<?> h) c = h.value();
            if (c instanceof DensityFunction && hasOnce(c, seen)) return true;
        }
        return false;
    }

    /** the field type for a leaf node: its class when the generated class (package levelgen) can name it */
    private static String leafType(Class<?> c) {
        if (c.isHidden() || c.isAnonymousClass() || c.isLocalClass()) return DF;
        if (c.getPackageName().equals("net.minecraft.world.level.levelgen")) return c.getName().replace('.', '/');
        for (Class<?> k = c; k != null; k = k.getEnclosingClass()) if (!Modifier.isPublic(k.getModifiers())) return DF;
        return c.getName().replace('.', '/');
    }

    private static String key(Walk w, List<N> roots, String head) {
        StringBuilder sb = new StringBuilder(head).append('|');
        for (int i = 0; i < w.slotTypes.size(); i++) sb.append(w.slotTypes.get(i)).append(',');
        sb.append('|');
        IdentityHashMap<N, Boolean> printed = new IdentityHashMap<>();
        for (N r : roots) {
            print(r, sb, printed);
            sb.append(';');
        }
        return sb.toString();
    }

    private static void print(N n, StringBuilder sb, IdentityHashMap<N, Boolean> printed) {
        sb.append((char) ('a' + n.kind));
        if (n.op != 0) sb.append(n.op);
        for (double v : n.p) sb.append('_').append(Long.toHexString(Double.doubleToRawLongBits(v)));
        if (n.slot >= 0) sb.append('#').append(n.slot);
        if (n.kind == ONCE) {
            sb.append('@').append(n.once);
            if (printed.put(n, Boolean.TRUE) != null) return;
        }
        if (n.kids.length > 0) {
            sb.append('(');
            for (N k : n.kids) {
                print(k, sb, printed);
                sb.append(',');
            }
            sb.append(')');
        }
    }

    // ---------------------------------------------------------------- entry points

    /** compiled cell fill for this NoiseChunk's cell cache function, or null (vanilla) */
    public static Code cell(MethodHandles.Lookup lookup, Object cellFiller, int cellWidth, int cellHeight) {
        if (!CELLS) return null;
        try {
            Walk w = new Walk(true);
            N r = w.walk(cellFiller);
            if (r == null) {
                refused("cell: " + w.why);
                return null;
            }
            String key = key(w, List.of(r), "C" + cellWidth + "x" + cellHeight);
            Constructor<?> k = CLASSES.computeIfAbsent(key, x -> define(lookup, w, List.of(r), true, cellWidth, cellHeight, 0, 0, key));
            BUILT.incrementAndGet();
            return (Code) k.newInstance((Object) w.slots.toArray());
        } catch (Throwable e) {
            refused("cell: " + e);
            return null;
        }
    }

    /** compiled corner columns for these interpolators (in order), or null (vanilla) */
    public static Code corners(MethodHandles.Lookup lookup, List<?> interpolators, int cellHeight, int cellNoiseMinY, int cellCountY) {
        if (!CORNERS) return null;
        try {
            Walk w = new Walk(false);
            List<N> roots = new ArrayList<>();
            int[] islots = new int[interpolators.size()];
            for (int i = 0; i < interpolators.size(); i++) {
                Object it = interpolators.get(i);
                if (!simple(it).equals("NoiseChunk$NoiseInterpolator")) {
                    refused("corners: interpolator kind " + it.getClass().getName());
                    return null;
                }
                islots[i] = w.slot(it, NI);
                N r = w.walk(get(it, "noiseFiller"));
                if (r == null) {
                    refused("corners: " + w.why);
                    return null;
                }
                roots.add(r);
            }
            StringBuilder head = new StringBuilder("K" + cellHeight + "_" + cellNoiseMinY + "_" + cellCountY + ":");
            for (int s : islots) head.append(s).append(',');
            String key = key(w, roots, head.toString());
            Constructor<?> k = CLASSES.computeIfAbsent(key, x -> {
                w.islots = islots;
                return define(lookup, w, roots, false, 0, cellHeight, cellNoiseMinY, cellCountY, key);
            });
            BUILT.incrementAndGet();
            return (Code) k.newInstance((Object) w.slots.toArray());
        } catch (Throwable e) {
            refused("corners: " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------- code generation

    private static Constructor<?> define(MethodHandles.Lookup lookup, Walk w, List<N> roots, boolean cell, int cellWidth, int cellHeight,
                                         int cellNoiseMinY, int cellCountY, String key) {
        try {
            String name = "net/minecraft/world/level/levelgen/Dfc$" + (cell ? "Cell" : "Corners") + IDS.incrementAndGet();
            Gen g = new Gen(name, w);
            byte[] bytes = g.build(roots, cell, cellWidth, cellHeight, cellNoiseMinY, cellCountY);
            MethodHandles.Lookup h = lookup.defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE);
            if (DUMP) System.out.println("[sleep/opt] dfc: new class " + name + " (" + bytes.length + " bytes, " + g.methods + " methods, "
                    + w.slots.size() + " objects): " + key);
            else System.out.println("[sleep/opt] dfc: new class " + name + " (" + bytes.length + " bytes, " + g.methods + " methods, "
                    + w.slots.size() + " objects)");
            Constructor<?> k = h.lookupClass().getDeclaredConstructor(Object[].class);
            k.setAccessible(true);
            return k;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Gen {
        final String name;
        final Walk w;
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        final List<N> pending = new ArrayList<>();
        int methods;

        Gen(String name, Walk w) {
            this.name = name;
            this.w = w;
        }

        byte[] build(List<N> roots, boolean cell, int cellWidth, int cellHeight, int cellNoiseMinY, int cellCountY) {
            cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, CODE, null);
            for (int i = 0; i < w.slots.size(); i++) cw.visitField(ACC_PRIVATE | ACC_FINAL, "f" + i, "L" + w.slotTypes.get(i) + ";", null, null).visitEnd();
            for (N o : w.onces) {
                cw.visitField(ACC_PRIVATE, "o" + o.once, "D", null, null).visitEnd();
                cw.visitField(ACC_PRIVATE, "s" + o.once, "I", null, null).visitEnd();
            }
            cw.visitField(ACC_PRIVATE, "stamp", "I", null, null).visitEnd();
            // constructor
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, CODE, "<init>", "()V", false);
            for (int i = 0; i < w.slots.size(); i++) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                push(mv, i);
                mv.visitInsn(AALOAD);
                mv.visitTypeInsn(CHECKCAST, w.slotTypes.get(i));
                mv.visitFieldInsn(PUTFIELD, name, "f" + i, "L" + w.slotTypes.get(i) + ";");
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            // the roots and once nodes as methods; others outlined as they come
            for (N r : roots) outline(r);
            for (N o : w.onces) outline(o);
            if (cell) cellLoop(roots.get(0), cellWidth, cellHeight);
            else cornerLoop(roots, cellHeight, cellNoiseMinY, cellCountY);
            while (!pending.isEmpty()) {
                N n = pending.remove(pending.size() - 1);
                evalMethod(n);
            }
            cw.visitEnd();
            return cw.toByteArray();
        }

        int outline(N n) {
            if (n.method < 0) {
                n.method = methods++;
                pending.add(n);
            }
            return n.method;
        }

        void call(MethodVisitor mv, N n) {
            int m = outline(n);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitVarInsn(DLOAD, 5);
            mv.visitVarInsn(DLOAD, 7);
            mv.visitVarInsn(DLOAD, 9);
            mv.visitMethodInsn(INVOKEVIRTUAL, name, "m" + m, EVAL, false);
        }

        void callAt(MethodVisitor mv, N n, int nc, int x, int y, int z, int dx, int dy, int dz) {
            int m = outline(n);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, nc);
            mv.visitVarInsn(ILOAD, x);
            mv.visitVarInsn(ILOAD, y);
            mv.visitVarInsn(ILOAD, z);
            if (dx < 0) {
                mv.visitInsn(DCONST_0);
                mv.visitInsn(DCONST_0);
                mv.visitInsn(DCONST_0);
            } else {
                mv.visitVarInsn(DLOAD, dx);
                mv.visitVarInsn(DLOAD, dy);
                mv.visitVarInsn(DLOAD, dz);
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, name, "m" + m, EVAL, false);
        }

        /** double m<k>(NoiseChunk nc, int x, int y, int z, double dx, double dy, double dz): the node's value at the point */
        void evalMethod(N n) {
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "m" + n.method, EVAL, null, null);
            mv.visitCode();
            Em e = new Em(mv);
            if (n.kind == ONCE) {
                Label miss = new Label(), end = new Label();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, "s" + n.once, "I");
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, "stamp", "I");
                mv.visitJumpInsn(IF_ICMPNE, miss);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, "o" + n.once, "D");
                mv.visitInsn(DRETURN);
                mv.visitLabel(miss);
                e.emit(n.kids[0], true);
                int t = e.temp();
                mv.visitVarInsn(DSTORE, t);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(DLOAD, t);
                mv.visitFieldInsn(PUTFIELD, name, "o" + n.once, "D");
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, "stamp", "I");
                mv.visitFieldInsn(PUTFIELD, name, "s" + n.once, "I");
                mv.visitVarInsn(DLOAD, t);
                mv.visitLabel(end);
            } else {
                e.emit(n, true);
            }
            mv.visitInsn(DRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        final class Em {
            final MethodVisitor mv;
            int next = 11;

            Em(MethodVisitor mv) {
                this.mv = mv;
            }

            int temp() {
                int t = next;
                next += 2;
                return t;
            }

            void slot(int s) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, "f" + s, "L" + w.slotTypes.get(s) + ";");
            }

            void ldc(double v) {
                mv.visitLdcInsn(v);
            }

            void emit(N n, boolean top) {
                if (n.kind == ONCE || (!top && n.size > SPLIT)) {
                    call(mv, n);
                    return;
                }
                switch (n.kind) {
                    case CONST -> ldc(n.p[0]);
                    case YGRAD -> {
                        mv.visitVarInsn(ILOAD, 3);
                        mv.visitInsn(I2D);
                        ldc(n.p[0]);
                        ldc(n.p[1]);
                        ldc(n.p[2]);
                        ldc(n.p[3]);
                        mv.visitMethodInsn(INVOKESTATIC, MTH, "clampedMap", "(DDDDD)D", false);
                    }
                    case NOISE -> {
                        slot(n.slot);
                        mv.visitVarInsn(ILOAD, 2);
                        mv.visitInsn(I2D);
                        ldc(n.p[0]);
                        mv.visitInsn(DMUL);
                        mv.visitVarInsn(ILOAD, 3);
                        mv.visitInsn(I2D);
                        ldc(n.p[1]);
                        mv.visitInsn(DMUL);
                        mv.visitVarInsn(ILOAD, 4);
                        mv.visitInsn(I2D);
                        ldc(n.p[0]);
                        mv.visitInsn(DMUL);
                        mv.visitMethodInsn(INVOKEVIRTUAL, NH, "getValue", "(DDD)D", false);
                    }
                    case SHIFTED -> {
                        slot(n.slot);
                        mv.visitVarInsn(ILOAD, 2);
                        mv.visitInsn(I2D);
                        ldc(n.p[0]);
                        mv.visitInsn(DMUL);
                        emit(n.kids[0], false);
                        mv.visitInsn(DADD);
                        mv.visitVarInsn(ILOAD, 3);
                        mv.visitInsn(I2D);
                        ldc(n.p[1]);
                        mv.visitInsn(DMUL);
                        emit(n.kids[1], false);
                        mv.visitInsn(DADD);
                        mv.visitVarInsn(ILOAD, 4);
                        mv.visitInsn(I2D);
                        ldc(n.p[0]);
                        mv.visitInsn(DMUL);
                        emit(n.kids[2], false);
                        mv.visitInsn(DADD);
                        mv.visitMethodInsn(INVOKEVIRTUAL, NH, "getValue", "(DDD)D", false);
                    }
                    case AP2 -> ap2(n);
                    case MULADD -> {
                        emit(n.kids[0], false);
                        ldc(n.p[0]);
                        mv.visitInsn(n.op == 0 ? DMUL : DADD);
                    }
                    case MAPPED -> mapped(n);
                    case CLAMP -> {
                        emit(n.kids[0], false);
                        ldc(n.p[0]);
                        ldc(n.p[1]);
                        mv.visitMethodInsn(INVOKESTATIC, MTH, "clamp", "(DDD)D", false);
                    }
                    case RANGE -> {
                        Label out = new Label(), end = new Label();
                        emit(n.kids[0], false);
                        int t = temp();
                        mv.visitVarInsn(DSTORE, t);
                        mv.visitVarInsn(DLOAD, t);
                        ldc(n.p[0]);
                        mv.visitInsn(DCMPL);
                        mv.visitJumpInsn(IFLT, out);
                        mv.visitVarInsn(DLOAD, t);
                        ldc(n.p[1]);
                        mv.visitInsn(DCMPG);
                        mv.visitJumpInsn(IFGE, out);
                        emit(n.kids[1], false);
                        mv.visitJumpInsn(GOTO, end);
                        mv.visitLabel(out);
                        emit(n.kids[2], false);
                        mv.visitLabel(end);
                    }
                    case TRANSFORM -> {
                        emit(n.kids[0], false);
                        int t = temp();
                        mv.visitVarInsn(DSTORE, t);
                        slot(n.slot);
                        mv.visitVarInsn(ALOAD, 1);
                        mv.visitVarInsn(DLOAD, t);
                        mv.visitMethodInsn(INVOKEVIRTUAL, w.slotTypes.get(n.slot), "transform", "(L" + FC + ";D)D", false);
                    }
                    case INTERP -> {
                        mv.visitVarInsn(DLOAD, 5);
                        mv.visitVarInsn(DLOAD, 7);
                        mv.visitVarInsn(DLOAD, 9);
                        for (String f : new String[] {"noise000", "noise100", "noise010", "noise110", "noise001", "noise101", "noise011", "noise111"}) {
                            slot(n.slot);
                            mv.visitFieldInsn(GETFIELD, NI, f, "D");
                        }
                        mv.visitMethodInsn(INVOKESTATIC, MTH, "lerp3", "(DDDDDDDDDDD)D", false);
                    }
                    case LEAF -> {
                        slot(n.slot);
                        mv.visitVarInsn(ALOAD, 1);
                        String t = w.slotTypes.get(n.slot);
                        if (t.equals(DF)) mv.visitMethodInsn(INVOKEINTERFACE, DF, "compute", "(L" + FC + ";)D", true);
                        else mv.visitMethodInsn(INVOKEVIRTUAL, t, "compute", "(L" + FC + ";)D", false);
                    }
                    default -> throw new IllegalStateException("kind " + n.kind);
                }
            }

            void ap2(N n) {
                emit(n.kids[0], false);
                if (n.op == 0) {   // ADD
                    emit(n.kids[1], false);
                    mv.visitInsn(DADD);
                    return;
                }
                int t = temp();
                Label l1 = new Label(), end = new Label();
                mv.visitVarInsn(DSTORE, t);
                mv.visitVarInsn(DLOAD, t);
                switch (n.op) {
                    case 1 -> {   // MUL: d == 0.0 ? 0.0 : d * b
                        mv.visitInsn(DCONST_0);
                        mv.visitInsn(DCMPL);
                        mv.visitJumpInsn(IFNE, l1);
                        mv.visitInsn(DCONST_0);
                        mv.visitJumpInsn(GOTO, end);
                        mv.visitLabel(l1);
                        mv.visitVarInsn(DLOAD, t);
                        emit(n.kids[1], false);
                        mv.visitInsn(DMUL);
                    }
                    case 2 -> {   // MIN: d < min(b) ? d : Math.min(d, b)
                        ldc(n.p[0]);
                        mv.visitInsn(DCMPG);
                        mv.visitJumpInsn(IFGE, l1);
                        mv.visitVarInsn(DLOAD, t);
                        mv.visitJumpInsn(GOTO, end);
                        mv.visitLabel(l1);
                        mv.visitVarInsn(DLOAD, t);
                        emit(n.kids[1], false);
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
                    }
                    default -> {   // MAX: d > max(b) ? d : Math.max(d, b)
                        ldc(n.p[0]);
                        mv.visitInsn(DCMPL);
                        mv.visitJumpInsn(IFLE, l1);
                        mv.visitVarInsn(DLOAD, t);
                        mv.visitJumpInsn(GOTO, end);
                        mv.visitLabel(l1);
                        mv.visitVarInsn(DLOAD, t);
                        emit(n.kids[1], false);
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
                    }
                }
                mv.visitLabel(end);
            }

            void mapped(N n) {
                emit(n.kids[0], false);
                if (n.op == 0) {
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
                    return;
                }
                int t = temp();
                if (n.op == 5) {   // SQUEEZE: c = clamp(v, -1, 1); c / 2.0 - c * c * c / 24.0
                    ldc(-1.0);
                    ldc(1.0);
                    mv.visitMethodInsn(INVOKESTATIC, MTH, "clamp", "(DDD)D", false);
                    mv.visitVarInsn(DSTORE, t);
                    mv.visitVarInsn(DLOAD, t);
                    ldc(2.0);
                    mv.visitInsn(DDIV);
                    mv.visitVarInsn(DLOAD, t);
                    mv.visitVarInsn(DLOAD, t);
                    mv.visitInsn(DMUL);
                    mv.visitVarInsn(DLOAD, t);
                    mv.visitInsn(DMUL);
                    ldc(24.0);
                    mv.visitInsn(DDIV);
                    mv.visitInsn(DSUB);
                    return;
                }
                mv.visitVarInsn(DSTORE, t);
                if (n.op == 1 || n.op == 2) {   // SQUARE v * v, CUBE v * v * v
                    mv.visitVarInsn(DLOAD, t);
                    mv.visitVarInsn(DLOAD, t);
                    mv.visitInsn(DMUL);
                    if (n.op == 2) {
                        mv.visitVarInsn(DLOAD, t);
                        mv.visitInsn(DMUL);
                    }
                    return;
                }
                // HALF_NEGATIVE / QUARTER_NEGATIVE: v > 0.0 ? v : v * k
                Label l1 = new Label(), end = new Label();
                mv.visitVarInsn(DLOAD, t);
                mv.visitInsn(DCONST_0);
                mv.visitInsn(DCMPL);
                mv.visitJumpInsn(IFLE, l1);
                mv.visitVarInsn(DLOAD, t);
                mv.visitJumpInsn(GOTO, end);
                mv.visitLabel(l1);
                mv.visitVarInsn(DLOAD, t);
                ldc(n.op == 3 ? 0.5 : 0.25);
                mv.visitInsn(DMUL);
                mv.visitLabel(end);
            }
        }

        /**
         * cell(nc, out): vanilla's NoiseChunk.fillAllDirectly order and context - y from the top, then x, then z;
         * inCellY / X / Z set as it sets them, arrayIndex as during its compute (already stepped).
         */
        void cellLoop(N root, int cw_, int ch) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "cell", "(L" + NC + ";[D)V", null, null);
            mv.visitCode();
            // locals: 0 this, 1 nc, 2 out, 3 bx, 4 by, 5 bz, 6 iy, 7 ix, 8 iz, 9 idx, 10 dy, 12 dx, 14 dz, 16 x, 17 y, 18 z
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, NC, "cellStartBlockX", "I");
            mv.visitVarInsn(ISTORE, 3);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, NC, "cellStartBlockY", "I");
            mv.visitVarInsn(ISTORE, 4);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, NC, "cellStartBlockZ", "I");
            mv.visitVarInsn(ISTORE, 5);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 9);
            push(mv, ch - 1);
            mv.visitVarInsn(ISTORE, 6);
            Label yTop = new Label(), yEnd = new Label();
            mv.visitLabel(yTop);
            mv.visitVarInsn(ILOAD, 6);
            mv.visitJumpInsn(IFLT, yEnd);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 6);
            mv.visitFieldInsn(PUTFIELD, NC, "inCellY", "I");
            mv.visitVarInsn(ILOAD, 6);
            mv.visitInsn(I2D);
            mv.visitLdcInsn((double) ch);
            mv.visitInsn(DDIV);
            mv.visitVarInsn(DSTORE, 10);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitVarInsn(ILOAD, 6);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, 17);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 7);
            Label xTop = new Label(), xEnd = new Label();
            mv.visitLabel(xTop);
            mv.visitVarInsn(ILOAD, 7);
            push(mv, cw_);
            mv.visitJumpInsn(IF_ICMPGE, xEnd);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 7);
            mv.visitFieldInsn(PUTFIELD, NC, "inCellX", "I");
            mv.visitVarInsn(ILOAD, 7);
            mv.visitInsn(I2D);
            mv.visitLdcInsn((double) cw_);
            mv.visitInsn(DDIV);
            mv.visitVarInsn(DSTORE, 12);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitVarInsn(ILOAD, 7);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, 16);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 8);
            Label zTop = new Label(), zEnd = new Label();
            mv.visitLabel(zTop);
            mv.visitVarInsn(ILOAD, 8);
            push(mv, cw_);
            mv.visitJumpInsn(IF_ICMPGE, zEnd);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 8);
            mv.visitFieldInsn(PUTFIELD, NC, "inCellZ", "I");
            mv.visitVarInsn(ILOAD, 8);
            mv.visitInsn(I2D);
            mv.visitLdcInsn((double) cw_);
            mv.visitInsn(DDIV);
            mv.visitVarInsn(DSTORE, 14);
            mv.visitVarInsn(ILOAD, 5);
            mv.visitVarInsn(ILOAD, 8);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, 18);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 9);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IADD);
            mv.visitFieldInsn(PUTFIELD, NC, "arrayIndex", "I");
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ILOAD, 9);
            callAt(mv, root, 1, 16, 17, 18, 12, 10, 14);
            mv.visitInsn(DASTORE);
            mv.visitIincInsn(9, 1);
            mv.visitIincInsn(8, 1);
            mv.visitJumpInsn(GOTO, zTop);
            mv.visitLabel(zEnd);
            mv.visitIincInsn(7, 1);
            mv.visitJumpInsn(GOTO, xTop);
            mv.visitLabel(xEnd);
            mv.visitIincInsn(6, -1);
            mv.visitJumpInsn(GOTO, yTop);
            mv.visitLabel(yEnd);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        /**
         * corners(nc, slice0, i): column i of every interpolator's slice, point by point as vanilla's slice provider
         * positions the NoiseChunk for each point (cellStartBlockY, one interpolationCounter step, inCellY 0, arrayIndex).
         */
        void cornerLoop(List<N> roots, int ch, int minY, int countY) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "corners", "(L" + NC + ";ZI)V", null, null);
            mv.visitCode();
            // locals: 0 this, 1 nc, 2 slice0, 3 i, 4 x, 5 z, 6 p, 7 y, 8.. columns
            int[] isl = w.islots;
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, NC, "cellStartBlockX", "I");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, NC, "inCellX", "I");
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, 4);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, NC, "cellStartBlockZ", "I");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, NC, "inCellZ", "I");
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, 5);
            for (int r = 0; r < isl.length; r++) {
                Label s1 = new Label(), got = new Label();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, "f" + isl[r], "L" + NI + ";");
                mv.visitVarInsn(ILOAD, 2);
                mv.visitJumpInsn(IFEQ, s1);
                mv.visitFieldInsn(GETFIELD, NI, "slice0", "[[D");
                mv.visitJumpInsn(GOTO, got);
                mv.visitLabel(s1);
                mv.visitFieldInsn(GETFIELD, NI, "slice1", "[[D");
                mv.visitLabel(got);
                mv.visitVarInsn(ILOAD, 3);
                mv.visitInsn(AALOAD);
                mv.visitVarInsn(ASTORE, 8 + r);
            }
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 6);
            Label top = new Label(), end = new Label();
            mv.visitLabel(top);
            mv.visitVarInsn(ILOAD, 6);
            push(mv, countY + 1);
            mv.visitJumpInsn(IF_ICMPGE, end);
            // y = (p + cellNoiseMinY) * cellHeight; nc.cellStartBlockY = y; nc.interpolationCounter++; nc.inCellY = 0; nc.arrayIndex = p
            mv.visitVarInsn(ILOAD, 6);
            push(mv, minY);
            mv.visitInsn(IADD);
            push(mv, ch);
            mv.visitInsn(IMUL);
            mv.visitVarInsn(ISTORE, 7);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 7);
            mv.visitFieldInsn(PUTFIELD, NC, "cellStartBlockY", "I");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(DUP);
            mv.visitFieldInsn(GETFIELD, NC, "interpolationCounter", "J");
            mv.visitInsn(LCONST_1);
            mv.visitInsn(LADD);
            mv.visitFieldInsn(PUTFIELD, NC, "interpolationCounter", "J");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(ICONST_0);
            mv.visitFieldInsn(PUTFIELD, NC, "inCellY", "I");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 6);
            mv.visitFieldInsn(PUTFIELD, NC, "arrayIndex", "I");
            if (!w.onces.isEmpty()) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitInsn(DUP);
                mv.visitFieldInsn(GETFIELD, name, "stamp", "I");
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IADD);
                mv.visitFieldInsn(PUTFIELD, name, "stamp", "I");
            }
            for (int r = 0; r < roots.size(); r++) {
                mv.visitVarInsn(ALOAD, 8 + r);
                mv.visitVarInsn(ILOAD, 6);
                callAt(mv, roots.get(r), 1, 4, 7, 5, -1, -1, -1);
                mv.visitInsn(DASTORE);
            }
            mv.visitIincInsn(6, 1);
            mv.visitJumpInsn(GOTO, top);
            mv.visitLabel(end);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    private static void push(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(SIPUSH, v);
        else mv.visitLdcInsn(v);
    }
}
