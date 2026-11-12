package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Speculated reads of another mod's fields (notes/71 step 2): fields a mod writes around an entity's draw (on the
 * render thread, in the dispatcher) and reads in the code a render worker runs for the entity - Shoulder Surfing's
 * "the camera entity is being drawn" flag. A worker reads them as they are at that moment and logs each value it saw
 * (per field and object; a repeat of the same value is not logged again). At the merge, inside the dispatcher where
 * the mod's injections have just run for this entity, every logged field is read again: all the same - the worker
 * saw what the vanilla order would have shown it, the job is taken; any difference (the flag was up for the player
 * while the worker drew a zombie) - the entity is drawn in place, as vanilla does.
 * <p>
 * The reads are rewritten in every class passing LateEventSites (RenderFieldFlow adds the mod's own classes holding
 * them to the flow carrier's targets): {@code GETFIELD f} -> {@code DUP; GETFIELD f; push slot; FieldLog.readX(obj,
 * value, slot)}, {@code GETSTATIC f} -> {@code ACONST_NULL; GETSTATIC f; push slot; FieldLog.readX}. Straight-line, no
 * frames to recompute. Outside a job a read costs a thread-local lookup.
 */
public final class FieldLog {
    private FieldLog() {}

    record Slot(String owner, String name, String desc, boolean isStatic, int index) {}

    private static final Map<String, Slot> BY_KEY = new HashMap<>();
    private static final List<Slot> ALL = new ArrayList<>();
    private static volatile Field[] fields;
    static volatile boolean frozen;

    /** a field to log (at mixin time, before any class reading it is transformed) */
    static synchronized boolean register(String owner, String name, String desc, boolean isStatic) {
        if (frozen) return false;
        if ("ZBCSIJFDL[".indexOf(desc.charAt(0)) < 0) return false;
        String key = owner + "." + name;
        if (BY_KEY.containsKey(key)) return true;
        Slot s = new Slot(owner, name, desc, isStatic, ALL.size());
        BY_KEY.put(key, s);
        ALL.add(s);
        return true;
    }

    public static boolean any() {
        return !ALL.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** a job's log: the values its worker read (primitive bits or a reference), per field and object */
    public static final class Log {
        int n;
        int[] slot = new int[8];
        Object[] obj = new Object[8];
        long[] bits = new long[8];
        Object[] ref = new Object[8];
        boolean broken;

        void clear() {
            for (int i = 0; i < n; i++) {
                obj[i] = null;
                ref[i] = null;
            }
            n = 0;
            broken = false;
        }

        private void add(int s, Object o, long b, Object r) {
            for (int i = n - 1; i >= 0; i--)   // the same read again: nothing new to check
                if (slot[i] == s && obj[i] == o) {
                    if (bits[i] == b && ref[i] == r) return;
                    break;
                }
            if (n == slot.length) {
                int m = n * 2;
                slot = java.util.Arrays.copyOf(slot, m);
                obj = java.util.Arrays.copyOf(obj, m);
                bits = java.util.Arrays.copyOf(bits, m);
                ref = java.util.Arrays.copyOf(ref, m);
            }
            if (n > 4096) {   // a runaway: give the job up rather than grow
                broken = true;
                return;
            }
            slot[n] = s;
            obj[n] = o;
            bits[n] = b;
            ref[n] = r;
            n++;
        }

        /**
         * Render thread, at the merge: true when every logged field still holds what the worker read (references by
         * identity, primitives bit for bit)
         */
        public boolean verify() {
            if (broken) return false;
            if (n == 0) return true;
            Field[] fs = fields();
            try {
                for (int i = 0; i < n; i++) {
                    Slot s = ALL.get(slot[i]);
                    Field f = fs[slot[i]];
                    if (f == null) return false;
                    Object now = f.get(s.isStatic ? null : obj[i]);
                    switch (s.desc.charAt(0)) {
                        case 'L', '[' -> { if (now != ref[i]) return false; }
                        case 'F' -> { if (Float.floatToRawIntBits((Float) now) != (int) bits[i]) return false; }
                        case 'D' -> { if (Double.doubleToRawLongBits((Double) now) != bits[i]) return false; }
                        case 'J' -> { if ((Long) now != bits[i]) return false; }
                        case 'Z' -> { if (((Boolean) now ? 1 : 0) != bits[i]) return false; }
                        case 'C' -> { if ((Character) now != bits[i]) return false; }
                        default -> { if (((Number) now).longValue() != bits[i]) return false; }
                    }
                }
            } catch (Throwable t) {
                return false;
            }
            return true;
        }
    }

    private static final ThreadLocal<Log> CUR = new ThreadLocal<>();

    /** a job starts / ends on its thread (ParallelRender.Job.run) */
    public static void begin(Log log) {
        if (ALL.isEmpty()) return;
        log.clear();
        CUR.set(log);
    }

    public static void end() {
        if (!ALL.isEmpty()) CUR.remove();
    }

    static volatile long fallbacks, verified;

    // the rewritten reads (public: called from other mods' classes)
    public static boolean readZ(Object o, boolean v, int s) { Log l = CUR.get(); if (l != null) l.add(s, o, v ? 1 : 0, null); return v; }
    public static byte readB(Object o, byte v, int s) { Log l = CUR.get(); if (l != null) l.add(s, o, v, null); return v; }
    public static char readC(Object o, char v, int s) { Log l = CUR.get(); if (l != null) l.add(s, o, v, null); return v; }
    public static short readS(Object o, short v, int s) { Log l = CUR.get(); if (l != null) l.add(s, o, v, null); return v; }
    public static int readI(Object o, int v, int s) { Log l = CUR.get(); if (l != null) l.add(s, o, v, null); return v; }
    public static long readJ(Object o, long v, int s) { Log l = CUR.get(); if (l != null) l.add(s, o, v, null); return v; }
    public static float readF(Object o, float v, int s) { Log l = CUR.get(); if (l != null) l.add(s, o, Float.floatToRawIntBits(v), null); return v; }
    public static double readD(Object o, double v, int s) { Log l = CUR.get(); if (l != null) l.add(s, o, Double.doubleToRawLongBits(v), null); return v; }
    public static Object readL(Object o, Object v, int s) { Log l = CUR.get(); if (l != null) l.add(s, o, 0, v); return v; }

    private static Field[] fields() {
        Field[] fs = fields;
        if (fs != null) return fs;
        synchronized (FieldLog.class) {
            if (fields != null) return fields;
            frozen = true;
            fs = new Field[ALL.size()];
            for (Slot s : ALL) {
                try {
                    Class<?> c = Class.forName(s.owner.replace('/', '.'), false, FieldLog.class.getClassLoader());
                    Field f = c.getDeclaredField(s.name);
                    f.setAccessible(true);
                    fs[s.index] = f;
                } catch (Throwable t) {
                    System.out.println("[sleep/opt] field log: " + s.owner + "." + s.name + " not reachable (" + t + "): its entities drawn in place");
                }
            }
            fields = fs;
            return fs;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    static final Set<String> CHANGED = ConcurrentHashMap.newKeySet();
    static volatile int sites;

    /** LateEventSites.postApply: the logged fields' reads in any class (the owners' static initialisers left alone) */
    public static void rewrite(ClassNode cn) {
        if (ALL.isEmpty()) return;
        boolean changed = false;
        for (MethodNode m : cn.methods) {
            boolean mc = false;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; ) {
                AbstractInsnNode next = in.getNext();
                if (in instanceof FieldInsnNode fi && (fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.GETSTATIC)) {
                    Slot s = BY_KEY.get(fi.owner + "." + fi.name);
                    if (s != null && s.desc.equals(fi.desc)) {
                        rewrite(m.instructions, fi, s);
                        mc = true;
                        sites++;
                    }
                }
                in = next;
            }
            if (mc) {
                m.maxStack += 4;
                changed = true;
            }
        }
        if (changed) CHANGED.add(cn.name);
    }

    private static void rewrite(InsnList list, FieldInsnNode fi, Slot s) {
        char k = s.desc.charAt(0);
        String kind = k == '[' ? "L" : String.valueOf(k);
        String t = k == 'L' || k == '[' ? "Ljava/lang/Object;" : kind;
        InsnList before = new InsnList();
        before.add(fi.getOpcode() == Opcodes.GETFIELD ? new InsnNode(Opcodes.DUP) : new InsnNode(Opcodes.ACONST_NULL));
        list.insertBefore(fi, before);
        InsnList after = new InsnList();
        after.add(s.index <= 5 ? new InsnNode(Opcodes.ICONST_0 + s.index) : s.index <= 127 ? new IntInsnNode(Opcodes.BIPUSH, s.index) : new LdcInsnNode(s.index));
        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/lull/optsupport/FieldLog", "read" + kind, "(Ljava/lang/Object;" + t + "I)" + t, false));
        if (k == 'L' || k == '[') after.add(new TypeInsnNode(Opcodes.CHECKCAST, k == '[' ? s.desc : Type.getType(s.desc).getInternalName()));
        list.insert(fi, after);
    }

    public static String report() {
        return ALL.isEmpty() ? "" : String.format("field log: %d fields, %d reads in %d classes, %d jobs checked, %d drawn in place",
                ALL.size(), sites, CHANGED.size(), verified, fallbacks);
    }

    static List<Slot> slots() {
        return ALL;
    }
}
