package dev.lull.optsupport;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-job copies of another mod's render state kept in fields (the field localizer, for mod adapters such as
 * EmfAdapter). The mod's code reads and writes the fields it always did; LocalizeTransformer passes each access
 * through here. Outside a ParallelRender job (the render thread, every other thread) the field itself; inside one the
 * copy of the job's renderer set - so two workers drawing two entities never share the entity being drawn, its phase
 * flags or its model-part depth. A copy starts every job from the render thread's values taken when the frame's
 * entity loop began (between entities: nothing mid-entity), its containers (the mod's stacks) emptied; a slot marked
 * keep (a counter) runs on in its copy.
 * <p>
 * Slots per kind (boolean, int, long, float, reference); the rewritten bytecode passes the slot's index in its kind.
 * Straight-line code only (no branches in the mod's methods: no frames to recompute): a read keeps the field read
 * and adds a call that returns it or the copy's value; a write becomes a call (the field written through a VarHandle
 * outside jobs).
 */
public final class ModLocals {
    private ModLocals() {}

    /** a slot: a static field of the mod's (or an instance field of a singleton), localized */
    static final class Slot {
        final String owner, name, desc;
        final boolean instance, keep, container;
        final char kind;
        final int index;
        volatile VarHandle vh;
        volatile MethodHandle factory;

        Slot(String owner, String name, String desc, boolean instance, boolean keep, boolean container, char kind, int index) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.instance = instance;
            this.keep = keep;
            this.container = container;
            this.kind = kind;
            this.index = index;
        }
    }

    private static final Map<String, Slot> BY_KEY = new HashMap<>();
    private static final List<Slot> ALL = new ArrayList<>();
    private static final Slot[][] BY_KIND = new Slot[5][];
    private static int nz, ni, nj, nf, nl;
    static volatile boolean frozen;

    /** the copies of one renderer set */
    public static final class Copy {
        final boolean[] z;
        final int[] i;
        final long[] j;
        final float[] f;
        final Object[] l;
        boolean started;

        public Copy() {
            z = new boolean[nz];
            i = new int[ni];
            j = new long[nj];
            f = new float[nf];
            l = new Object[nl];
        }
    }

    private static final ThreadLocal<Copy> CUR = new ThreadLocal<>();
    /** the render thread's values at the frame's start (written on the render thread before the frame's jobs go out) */
    private static boolean[] sz = new boolean[0];
    private static int[] si = new int[0];
    private static long[] sj = new long[0];
    private static float[] sf = new float[0];
    private static Object[] sl = new Object[0];

    /** any slot registered (an adapter on) */
    public static boolean active() {
        return !ALL.isEmpty();
    }

    private static int kindOf(char k) {
        return switch (k) { case 'Z' -> 0; case 'I' -> 1; case 'J' -> 2; case 'F' -> 3; default -> 4; };
    }

    /**
     * A field to localize (at mixin time, before any class that uses it is transformed). keep: the copy's value runs
     * on between jobs (a counter); a final reference field is a container: each copy gets its own instance (the field's
     * class' no-argument constructor), emptied at every job. false: a kind the localizer does not carry (byte, short,
     * char, double).
     */
    public static synchronized boolean register(String owner, String name, String desc, boolean instance, boolean keep, boolean container) {
        if (frozen) throw new IllegalStateException("ModLocals: slots registered after the first copy");
        char k = desc.charAt(0);
        if ("ZIJFL[".indexOf(k) < 0) return false;
        if (k == '[') k = 'L';
        String key = owner + "." + name;
        if (BY_KEY.containsKey(key)) return true;
        int index = switch (k) { case 'Z' -> nz++; case 'I' -> ni++; case 'J' -> nj++; case 'F' -> nf++; default -> nl++; };
        Slot s = new Slot(owner, name, desc, instance, keep, container, k, index);
        BY_KEY.put(key, s);
        ALL.add(s);
        return true;
    }

    /** the slot of owner.name, or null (LocalizeTransformer) */
    static Slot slot(String owner, String name) {
        return BY_KEY.get(owner + "." + name);
    }

    static boolean any() {
        return !BY_KEY.isEmpty();
    }

    private static synchronized void freeze() {
        if (frozen) return;
        for (int k = 0; k < 5; k++) BY_KIND[k] = new Slot[switch (k) { case 0 -> nz; case 1 -> ni; case 2 -> nj; case 3 -> nf; default -> nl; }];
        for (Slot s : ALL) BY_KIND[kindOf(s.kind)][s.index] = s;
        sz = new boolean[nz];
        si = new int[ni];
        sj = new long[nj];
        sf = new float[nf];
        sl = new Object[nl];
        frozen = true;
    }

    private static VarHandle vh(char kind, int index) {
        if (!frozen) freeze();   // a write outside a job before the first frame (the mod's own setup)
        Slot s = BY_KIND[kindOf(kind)][index];
        VarHandle v = s.vh;
        if (v != null) return v;
        try {
            Class<?> c = Class.forName(s.owner.replace('/', '.'), false, ModLocals.class.getClassLoader());
            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
            Class<?> type = switch (s.kind) {
                case 'Z' -> boolean.class; case 'I' -> int.class; case 'J' -> long.class; case 'F' -> float.class;
                default -> Class.forName(org.objectweb.asm.Type.getType(s.desc).getClassName(), false, c.getClassLoader());
            };
            v = s.instance ? lk.findVarHandle(c, s.name, type) : lk.findStaticVarHandle(c, s.name, type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ModLocals: " + s.owner + "." + s.name + " not reachable", e);
        }
        s.vh = v;
        return v;
    }

    private static Object newContainer(Slot s, Object like) {
        try {
            MethodHandle f = s.factory;
            if (f == null) {
                Class<?> c = like.getClass();
                MethodHandles.Lookup lk = java.lang.reflect.Modifier.isPublic(c.getModifiers()) && c.getModule().isExported(c.getPackageName())
                        ? MethodHandles.publicLookup()   // java.util.ArrayDeque: java.base is not open to us
                        : MethodHandles.privateLookupIn(c, MethodHandles.lookup());   // ETF's anonymous NULL-sentinel deque
                f = lk.findConstructor(c, MethodType.methodType(void.class));
                s.factory = f;
            }
            return f.invoke();
        } catch (Throwable e) {
            throw new IllegalStateException("ModLocals: no copy of " + s.owner + "." + s.name + " (" + like.getClass().getName() + ")", e);
        }
    }

    // ------------------------------------------------------------------ the frame and the job

    /** Render thread, before the frame's jobs go out: the values the copies start from. */
    public static void frameStart() {
        if (ALL.isEmpty()) return;
        if (!frozen) freeze();
        for (Slot s : ALL) {
            if (s.keep || s.container || s.instance) continue;
            VarHandle v = vh(s.kind, s.index);
            switch (s.kind) {
                case 'Z' -> sz[s.index] = (boolean) v.get();
                case 'I' -> si[s.index] = (int) v.get();
                case 'J' -> sj[s.index] = (long) v.get();
                case 'F' -> sf[s.index] = (float) v.get();
                default -> sl[s.index] = v.get();
            }
        }
    }

    /** A job starts on this thread with this set's copy. */
    public static void enter(Copy c) {
        if (!c.started) {
            if (!frozen) freeze();
            for (Slot s : ALL) {
                if (s.container) {
                    Object like = vh(s.kind, s.index).get();
                    c.l[s.index] = like == null ? null : newContainer(s, like);
                } else if (s.keep && s.kind == 'J') {
                    c.j[s.index] = 1_000_000_000L;   // a counter of its own (compared with values stored in this set's objects only)
                }
            }
            c.started = true;
        }
        for (Slot s : ALL) {
            if (s.keep || s.instance) continue;
            if (s.container) {
                if (c.l[s.index] instanceof Collection<?> col) col.clear();
                else if (c.l[s.index] instanceof Map<?, ?> m) m.clear();
                continue;
            }
            switch (s.kind) {
                case 'Z' -> c.z[s.index] = sz[s.index];
                case 'I' -> c.i[s.index] = si[s.index];
                case 'J' -> c.j[s.index] = sj[s.index];
                case 'F' -> c.f[s.index] = sf[s.index];
                default -> c.l[s.index] = sl[s.index];
            }
        }
        CUR.set(c);
    }

    public static void exit() {
        CUR.remove();
    }

    /** the copy of this thread's job, or null */
    public static Copy current() {
        return CUR.get();
    }

    /** in a job with copies (this thread) */
    public static boolean inJob() {
        return CUR.get() != null;
    }

    /** RenderSystem.isOnRenderThread() in the mod's state code: true in a job too (the job's copies are its own) */
    public static boolean renderThreadOrJob() {
        return CUR.get() != null || com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();
    }

    // ------------------------------------------------------------------ static fields (index: the slot's index in its kind)

    public static boolean getZ(boolean field, int index) {
        Copy c = CUR.get();
        return c == null ? field : c.z[index];
    }

    public static void setZ(boolean v, int index) {
        Copy c = CUR.get();
        if (c == null) vh('Z', index).set(v);
        else c.z[index] = v;
    }

    public static int getI(int field, int index) {
        Copy c = CUR.get();
        return c == null ? field : c.i[index];
    }

    public static void setI(int v, int index) {
        Copy c = CUR.get();
        if (c == null) vh('I', index).set(v);
        else c.i[index] = v;
    }

    public static long getJ(long field, int index) {
        Copy c = CUR.get();
        return c == null ? field : c.j[index];
    }

    public static void setJ(long v, int index) {
        Copy c = CUR.get();
        if (c == null) vh('J', index).set(v);
        else c.j[index] = v;
    }

    public static float getF(float field, int index) {
        Copy c = CUR.get();
        return c == null ? field : c.f[index];
    }

    public static void setF(float v, int index) {
        Copy c = CUR.get();
        if (c == null) vh('F', index).set(v);
        else c.f[index] = v;
    }

    public static Object getL(Object field, int index) {
        Copy c = CUR.get();
        return c == null ? field : c.l[index];
    }

    public static void setL(Object v, int index) {
        Copy c = CUR.get();
        if (c == null) vh('L', index).set(v);
        else c.l[index] = v;
    }

    // ------------------------------------------------------------------ instance fields (one object: a singleton's)

    public static long getFieldJ(Object owner, int index) {
        Copy c = CUR.get();
        return c == null ? (long) vh('J', index).get(owner) : c.j[index];
    }

    public static void setFieldJ(Object owner, long v, int index) {
        Copy c = CUR.get();
        if (c == null) vh('J', index).set(owner, v);
        else c.j[index] = v;
    }

    public static int getFieldI(Object owner, int index) {
        Copy c = CUR.get();
        return c == null ? (int) vh('I', index).get(owner) : c.i[index];
    }

    public static void setFieldI(Object owner, int v, int index) {
        Copy c = CUR.get();
        if (c == null) vh('I', index).set(owner, v);
        else c.i[index] = v;
    }

    /** for the log */
    public static String describe() {
        StringBuilder b = new StringBuilder();
        for (Slot s : ALL) b.append(b.length() == 0 ? "" : ", ").append(s.owner.substring(s.owner.lastIndexOf('/') + 1)).append('.').append(s.name)
                .append(s.keep ? " (own counter)" : s.container ? " (own stack)" : "");
        return b.toString();
    }
}
