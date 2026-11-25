package dev.lull.optsupport.sleep;

import dev.lull.FieldDiff;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Compiles, once per block-entity class, a snapshot routine for the fields the sleep engine watches.
 *
 * <p>Field layouts cannot change after classes are loaded (game and mods included), so instead of reflection on every
 * tick a hidden class is generated per block-entity class. It lives in this package and receives the fields'
 * VarHandles as class data, stored in static final fields: for the JIT these are constants, so each read folds to a
 * plain field load. (A nestmate hidden class inside the Minecraft module is not allowed: no full-privilege lookup
 * there.)
 */
final class SnapCompiler {
    /** Writes the watched fields of {@code be} into {@code out} (primitive bits, or a reference/content fingerprint). */
    interface Snapper {
        void snap(Object be, long[] out);

        /** The references of the reference slots (primitive slots left untouched). */
        void refs(Object be, Object[] out);

        /** The primitive slots only, as snap writes them (reference slots left untouched). */
        default void prims(Object be, long[] out) {
            snap(be, out);
        }

        /** prims and refs in one pass: the primitive slots into {@code out}, the reference slots into {@code refs} */
        default void both(Object be, long[] out, Object[] refs) {
            prims(be, out);
            refs(be, refs);
        }

        /**
         * Cheap check for a sleeping block entity: primitive slots equal {@code prims} (same encoding as snap) and
         * reference slots are the very same objects as {@code refs}. Reads only the block entity's own fields.
         */
        boolean same(Object be, long[] prims, Object[] refs);

        /** Write slot i: a primitive from its snapshot encoding ({@code bits}), a reference from {@code ref}. */
        void put(Object be, int i, long bits, Object ref);

        /** Primitive slot i in the snapshot encoding (no fingerprinting). */
        long bits(Object be, int i);

        /** Hash of the primitive slots not skipped (same formula as {@link SnapCompiler#hashPrims(long[], boolean[], boolean[])}). */
        long hashPrims(Object be);

        /** The non-final, non-skipped reference slots, in slot order, are the very objects in {@code refs} (compact). */
        boolean sameRefs(Object be, Object[] refs);
    }

    static final long H0 = 0x9E3779B97F4A7C15L, HM = 0xC2B2AE3D27D4EB4FL;

    /** Java twin of the generated hashPrims: {@code v} in the snapshot encoding. */
    static long hashPrims(long[] v, boolean[] prim, boolean[] skip) {
        long h = H0;
        for (int i = 0; i < v.length && i < prim.length; i++) {
            if (!prim[i] || skip != null && skip[i]) continue;
            h = (h + v[i]) * HM;
            h ^= h >>> 31;
        }
        return h;
    }

    /** Slots sameRefs compares, in order. */
    static int[] refSlots(List<Field> fields, boolean[] skip) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isFinal(f.getModifiers()) || skip != null && skip[i]) continue;
            out.add(i);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final String SNAPPER = Type.getInternalName(Snapper.class);
    private static final String FP_OWNER = Type.getInternalName(FieldDiff.class);
    /** slots per generated method (see generate) */
    private static final int CHUNK = 32;
    private static int counter;
    static String failure;

    private SnapCompiler() {}

    /**
     * One hidden class per block-entity class, defined in this package (full privilege here) with the fields'
     * VarHandles as class data, loaded into {@code static final} fields in {@code <clinit>}. A static final VarHandle
     * is a JIT constant, so every read folds to a plain field load, like hand-written GETFIELD code.
     * Falls back to reflection on failure.
     */
    static Snapper compile(List<Field> fields) {
        return compile(fields, false);
    }

    /** {@code values}: references fingerprinted by value where that is their state (EntityFp: vectors, boxes, synched data, random seeds). */
    static Snapper compile(List<Field> fields, boolean values) {
        return compile(fields, values, null);
    }

    /** {@code skip}: slots same() ignores (checked another way, e.g. clocks). */
    static Snapper compile(List<Field> fields, boolean values, boolean[] skip) {
        return compile(fields, values, skip, null);
    }

    /**
     * {@code partOf} (mob sleep's composite state): the object passed is an Object[] and slot i is a field of element
     * partOf[i] - one generated class over the entity and all its contexts. Null if the generation fails.
     */
    static Snapper compileComposite(List<Field> fields, boolean[] skip, int[] partOf) {
        try {
            List<java.lang.invoke.VarHandle> vhs = new ArrayList<>();
            for (Field f : fields) vhs.add(MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup()).unreflectVarHandle(f));
            return generate(fields, vhs, true, skip, partOf);
        } catch (Throwable t) {
            if (failure == null) failure = t.toString();
            return null;
        }
    }

    private static Snapper compile(List<Field> fields, boolean values, boolean[] skip, int[] partOf) {
        try {
            List<java.lang.invoke.VarHandle> vhs = new ArrayList<>();
            for (Field f : fields) vhs.add(MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup()).unreflectVarHandle(f));
            return generate(fields, vhs, values, skip, partOf);
        } catch (Throwable t) {
            if (failure == null) failure = t.toString();
            return reflective(fields, values, skip);
        }
    }

    private static Snapper generate(List<Field> fields, List<java.lang.invoke.VarHandle> vhs, boolean values, boolean[] skip, int[] partOf) throws Throwable {
        String name = "dev/lull/optsupport/sleep/ProbeSnap" + (counter++);
        String VH = "java/lang/invoke/VarHandle", VHD = "Ljava/lang/invoke/VarHandle;";
        // frames are needed for the branches in same(); every merged type here is Object, so no class loading
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, name, null, "java/lang/Object", new String[] {SNAPPER});
        for (int i = 0; i < fields.size(); i++) cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "VH" + i, VHD, null, null).visitEnd();
        MethodVisitor cl = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        cl.visitCode();
        for (int i = 0; i < fields.size(); i++) {
            cl.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
            cl.visitLdcInsn("_");
            cl.visitLdcInsn(Type.getType(VHD));
            pushInt(cl, i);
            cl.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "classDataAt",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;I)Ljava/lang/Object;", false);
            cl.visitTypeInsn(Opcodes.CHECKCAST, VH);
            cl.visitFieldInsn(Opcodes.PUTSTATIC, name, "VH" + i, VHD);
        }
        cl.visitInsn(Opcodes.RETURN);
        cl.visitMaxs(0, 0);
        cl.visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        // Every routine is split into methods of CHUNK slots, called in turn by the interface method: one method over
        // hundreds of slots (a mob's composite state) passes the JIT's huge-method limit (8000 bytes of bytecode: never
        // compiled) and its inlining budget (each VarHandle read inlines a chain; past the budget the reads stay calls).
        int nf = fields.size(), chunks = Math.max(1, (nf + CHUNK - 1) / CHUNK);
        // snap(Object be, long[] out)
        callChunks(cw, name, "snap", "(Ljava/lang/Object;[J)V", chunks);
        for (int ch = 0; ch < chunks; ch++) {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "snap" + ch, "(Ljava/lang/Object;[J)V", null, null);
            m.visitCode();
            for (int i = ch * CHUNK; i < Math.min(nf, ch * CHUNK + CHUNK); i++) {
                Field f = fields.get(i);
                Class<?> t = f.getType();
                m.visitVarInsn(Opcodes.ALOAD, 2);
                pushInt(m, i);
                vhGet(m, name, i, f, partOf);
                if (t == long.class) {
                    // already a long
                } else if (t == int.class || t == short.class || t == byte.class || t == char.class || t == boolean.class) {
                    m.visitInsn(Opcodes.I2L);
                } else if (t == float.class) {
                    m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
                    m.visitInsn(Opcodes.I2L);
                } else if (t == double.class) {
                    m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
                } else {
                    if (values) m.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/lull/optsupport/sleep/EntityFp", "fp", "(Ljava/lang/Object;)J", false);
                    else m.visitMethodInsn(Opcodes.INVOKESTATIC, FP_OWNER, "fingerprint", "(Ljava/lang/Object;)J", false);
                }
                m.visitInsn(Opcodes.LASTORE);
            }
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        // both(Object be, long[] out, Object[] refs): one method for a mob's probe ticks and observed ticks - called
        // on every probe, so the JIT compiles it fully (the observation alone calls it too rarely: ~5x slower code)
        String bothDesc = "(Ljava/lang/Object;[J[Ljava/lang/Object;)V";
        callChunks(cw, name, "both", bothDesc, chunks);
        for (int ch = 0; ch < chunks; ch++) {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "both" + ch, bothDesc, null, null);
            m.visitCode();
            for (int i = ch * CHUNK; i < Math.min(nf, ch * CHUNK + CHUNK); i++) {
                Field f = fields.get(i);
                Class<?> t = f.getType();
                if (t.isPrimitive()) {
                    m.visitVarInsn(Opcodes.ALOAD, 2);
                    pushInt(m, i);
                    vhGet(m, name, i, f, partOf);
                    toBits(m, t);
                    m.visitInsn(Opcodes.LASTORE);
                } else {
                    m.visitVarInsn(Opcodes.ALOAD, 3);
                    pushInt(m, i);
                    vhGet(m, name, i, f, partOf);
                    m.visitInsn(Opcodes.AASTORE);
                }
            }
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        // prims(Object be, long[] out)
        callChunks(cw, name, "prims", "(Ljava/lang/Object;[J)V", chunks);
        for (int ch = 0; ch < chunks; ch++) {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "prims" + ch, "(Ljava/lang/Object;[J)V", null, null);
            m.visitCode();
            for (int i = ch * CHUNK; i < Math.min(nf, ch * CHUNK + CHUNK); i++) {
                Field f = fields.get(i);
                Class<?> t = f.getType();
                if (!t.isPrimitive()) continue;
                m.visitVarInsn(Opcodes.ALOAD, 2);
                pushInt(m, i);
                vhGet(m, name, i, f, partOf);
                toBits(m, t);
                m.visitInsn(Opcodes.LASTORE);
            }
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        // refs(Object be, Object[] out)
        callChunks(cw, name, "refs", "(Ljava/lang/Object;[Ljava/lang/Object;)V", chunks);
        for (int ch = 0; ch < chunks; ch++) {
            MethodVisitor r = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "refs" + ch, "(Ljava/lang/Object;[Ljava/lang/Object;)V", null, null);
            r.visitCode();
            for (int i = ch * CHUNK; i < Math.min(nf, ch * CHUNK + CHUNK); i++) {
                Field f = fields.get(i);
                if (f.getType().isPrimitive()) continue;
                r.visitVarInsn(Opcodes.ALOAD, 2);
                pushInt(r, i);
                vhGet(r, name, i, f, partOf);
                r.visitInsn(Opcodes.AASTORE);
            }
            r.visitInsn(Opcodes.RETURN);
            r.visitMaxs(0, 0);
            r.visitEnd();
        }
        // same(Object be, long[] prims, Object[] refs): every chunk must agree
        String sameDesc = "(Ljava/lang/Object;[J[Ljava/lang/Object;)Z";
        MethodVisitor qa = cw.visitMethod(Opcodes.ACC_PUBLIC, "same", sameDesc, null, null);
        qa.visitCode();
        org.objectweb.asm.Label qno = new org.objectweb.asm.Label();
        for (int ch = 0; ch < chunks; ch++) {
            qa.visitVarInsn(Opcodes.ALOAD, 0);
            qa.visitVarInsn(Opcodes.ALOAD, 1);
            qa.visitVarInsn(Opcodes.ALOAD, 2);
            qa.visitVarInsn(Opcodes.ALOAD, 3);
            qa.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "same" + ch, sameDesc, false);
            qa.visitJumpInsn(Opcodes.IFEQ, qno);
        }
        qa.visitInsn(Opcodes.ICONST_1);
        qa.visitInsn(Opcodes.IRETURN);
        qa.visitLabel(qno);
        qa.visitInsn(Opcodes.ICONST_0);
        qa.visitInsn(Opcodes.IRETURN);
        qa.visitMaxs(0, 0);
        qa.visitEnd();
        for (int ch = 0; ch < chunks; ch++) {
            MethodVisitor q = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "same" + ch, sameDesc, null, null);
            q.visitCode();
            org.objectweb.asm.Label differ = new org.objectweb.asm.Label();
            for (int i = ch * CHUNK; i < Math.min(nf, ch * CHUNK + CHUNK); i++) {
                Field f = fields.get(i);
                Class<?> t = f.getType();
                if (skip != null && skip[i]) continue;
                if (t.isPrimitive()) {
                    vhGet(q, name, i, f, partOf);
                    toBits(q, t);
                    q.visitVarInsn(Opcodes.ALOAD, 2);
                    pushInt(q, i);
                    q.visitInsn(Opcodes.LALOAD);
                    q.visitInsn(Opcodes.LCMP);
                    q.visitJumpInsn(Opcodes.IFNE, differ);
                } else {
                    vhGet(q, name, i, f, partOf);
                    q.visitVarInsn(Opcodes.ALOAD, 3);
                    pushInt(q, i);
                    q.visitInsn(Opcodes.AALOAD);
                    q.visitJumpInsn(Opcodes.IF_ACMPNE, differ);
                }
            }
            q.visitInsn(Opcodes.ICONST_1);
            q.visitInsn(Opcodes.IRETURN);
            q.visitLabel(differ);
            q.visitInsn(Opcodes.ICONST_0);
            q.visitInsn(Opcodes.IRETURN);
            q.visitMaxs(0, 0);
            q.visitEnd();
        }
        // put(Object be, int i, long bits, Object ref): a tableswitch over the chunks, then over the chunk's slots
        String putDesc = "(Ljava/lang/Object;IJLjava/lang/Object;)V";
        MethodVisitor wa = cw.visitMethod(Opcodes.ACC_PUBLIC, "put", putDesc, null, null);
        wa.visitCode();
        org.objectweb.asm.Label wend = new org.objectweb.asm.Label();
        org.objectweb.asm.Label[] wcs = new org.objectweb.asm.Label[chunks];
        for (int ch = 0; ch < chunks; ch++) wcs[ch] = new org.objectweb.asm.Label();
        wa.visitVarInsn(Opcodes.ILOAD, 2);
        pushInt(wa, CHUNK);
        wa.visitInsn(Opcodes.IDIV);
        wa.visitTableSwitchInsn(0, chunks - 1, wend, wcs);
        for (int ch = 0; ch < chunks; ch++) {
            wa.visitLabel(wcs[ch]);
            wa.visitVarInsn(Opcodes.ALOAD, 0);
            wa.visitVarInsn(Opcodes.ALOAD, 1);
            wa.visitVarInsn(Opcodes.ILOAD, 2);
            wa.visitVarInsn(Opcodes.LLOAD, 3);
            wa.visitVarInsn(Opcodes.ALOAD, 5);
            wa.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "put" + ch, putDesc, false);
            wa.visitInsn(Opcodes.RETURN);
        }
        wa.visitLabel(wend);
        wa.visitInsn(Opcodes.RETURN);
        wa.visitMaxs(0, 0);
        wa.visitEnd();
        for (int ch = 0; ch < chunks; ch++) {
            int lo = ch * CHUNK, hi = Math.min(nf, lo + CHUNK);
            MethodVisitor w = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "put" + ch, putDesc, null, null);
            w.visitCode();
            org.objectweb.asm.Label end = new org.objectweb.asm.Label();
            org.objectweb.asm.Label[] cases = new org.objectweb.asm.Label[Math.max(1, hi - lo)];
            for (int i = 0; i < cases.length; i++) cases[i] = new org.objectweb.asm.Label();
            w.visitVarInsn(Opcodes.ILOAD, 2);
            w.visitTableSwitchInsn(lo, lo + cases.length - 1, end, cases);
            if (hi == lo) w.visitLabel(cases[0]);
            for (int i = lo; i < hi; i++) {
                Field f = fields.get(i);
                Class<?> t = f.getType();
                Class<?> owner = f.getDeclaringClass();
                boolean publicOwner = java.lang.reflect.Modifier.isPublic(owner.getModifiers()) && owner.getEnclosingClass() == null;
                String coord = publicOwner ? Type.getDescriptor(owner) : "Ljava/lang/Object;";
                w.visitLabel(cases[i - lo]);
                if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) {   // never written by a replay
                    w.visitJumpInsn(Opcodes.GOTO, end);
                    continue;
                }
                if (partOf != null && U != null) {
                    unsafeAccess(w, partOf, i, f, true);
                    w.visitJumpInsn(Opcodes.GOTO, end);
                    continue;
                }
                w.visitFieldInsn(Opcodes.GETSTATIC, name, "VH" + i, "Ljava/lang/invoke/VarHandle;");
                loadRecv(w, partOf, i);
                if (publicOwner) w.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(owner));
                String valueDesc;
                if (t.isPrimitive()) {
                    w.visitVarInsn(Opcodes.LLOAD, 3);
                    if (t == long.class) {
                        valueDesc = "J";
                    } else if (t == double.class) {
                        w.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
                        valueDesc = "D";
                    } else {
                        w.visitInsn(Opcodes.L2I);
                        if (t == float.class) {
                            w.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false);
                        } else if (t == short.class) {
                            w.visitInsn(Opcodes.I2S);
                        } else if (t == byte.class) {
                            w.visitInsn(Opcodes.I2B);
                        } else if (t == char.class) {
                            w.visitInsn(Opcodes.I2C);
                        }
                        valueDesc = Type.getDescriptor(t);
                    }
                } else {
                    w.visitVarInsn(Opcodes.ALOAD, 5);
                    valueDesc = "Ljava/lang/Object;";
                }
                w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "set", "(" + coord + valueDesc + ")V", false);
                w.visitJumpInsn(Opcodes.GOTO, end);
            }
            w.visitLabel(end);
            w.visitInsn(Opcodes.RETURN);
            w.visitMaxs(0, 0);
            w.visitEnd();
        }
        // bits(Object be, int i)
        String bitsDesc = "(Ljava/lang/Object;I)J";
        MethodVisitor ga = cw.visitMethod(Opcodes.ACC_PUBLIC, "bits", bitsDesc, null, null);
        ga.visitCode();
        org.objectweb.asm.Label gnone = new org.objectweb.asm.Label();
        org.objectweb.asm.Label[] gcs = new org.objectweb.asm.Label[chunks];
        for (int ch = 0; ch < chunks; ch++) gcs[ch] = new org.objectweb.asm.Label();
        ga.visitVarInsn(Opcodes.ILOAD, 2);
        pushInt(ga, CHUNK);
        ga.visitInsn(Opcodes.IDIV);
        ga.visitTableSwitchInsn(0, chunks - 1, gnone, gcs);
        for (int ch = 0; ch < chunks; ch++) {
            ga.visitLabel(gcs[ch]);
            ga.visitVarInsn(Opcodes.ALOAD, 0);
            ga.visitVarInsn(Opcodes.ALOAD, 1);
            ga.visitVarInsn(Opcodes.ILOAD, 2);
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "bits" + ch, bitsDesc, false);
            ga.visitInsn(Opcodes.LRETURN);
        }
        ga.visitLabel(gnone);
        ga.visitInsn(Opcodes.LCONST_0);
        ga.visitInsn(Opcodes.LRETURN);
        ga.visitMaxs(0, 0);
        ga.visitEnd();
        for (int ch = 0; ch < chunks; ch++) {
            int lo = ch * CHUNK, hi = Math.min(nf, lo + CHUNK);
            MethodVisitor g = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "bits" + ch, bitsDesc, null, null);
            g.visitCode();
            org.objectweb.asm.Label none = new org.objectweb.asm.Label();
            org.objectweb.asm.Label[] gc = new org.objectweb.asm.Label[Math.max(1, hi - lo)];
            for (int i = 0; i < gc.length; i++) gc[i] = new org.objectweb.asm.Label();
            g.visitVarInsn(Opcodes.ILOAD, 2);
            g.visitTableSwitchInsn(lo, lo + gc.length - 1, none, gc);
            if (hi == lo) g.visitLabel(gc[0]);
            for (int i = lo; i < hi; i++) {
                Field f = fields.get(i);
                g.visitLabel(gc[i - lo]);
                if (!f.getType().isPrimitive()) {
                    g.visitJumpInsn(Opcodes.GOTO, none);
                    continue;
                }
                vhGet(g, name, i, f, partOf);
                toBits(g, f.getType());
                g.visitInsn(Opcodes.LRETURN);
            }
            g.visitLabel(none);
            g.visitInsn(Opcodes.LCONST_0);
            g.visitInsn(Opcodes.LRETURN);
            g.visitMaxs(0, 0);
            g.visitEnd();
        }
        // hashPrims(Object be): h = (h + bits) * HM; h ^= h >>> 31 over primitive, non-skipped slots; h passes through the chunks
        String hashDesc = "(Ljava/lang/Object;J)J";
        MethodVisitor ha = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashPrims", "(Ljava/lang/Object;)J", null, null);
        ha.visitCode();
        ha.visitLdcInsn(H0);
        for (int ch = 0; ch < chunks; ch++) {
            ha.visitVarInsn(Opcodes.LSTORE, 2);
            ha.visitVarInsn(Opcodes.ALOAD, 0);
            ha.visitVarInsn(Opcodes.ALOAD, 1);
            ha.visitVarInsn(Opcodes.LLOAD, 2);
            ha.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "hashPrims" + ch, hashDesc, false);
        }
        ha.visitInsn(Opcodes.LRETURN);
        ha.visitMaxs(0, 0);
        ha.visitEnd();
        for (int ch = 0; ch < chunks; ch++) {
            MethodVisitor hp = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "hashPrims" + ch, hashDesc, null, null);
            hp.visitCode();
            for (int i = ch * CHUNK; i < Math.min(nf, ch * CHUNK + CHUNK); i++) {
                Field f = fields.get(i);
                if (!f.getType().isPrimitive() || skip != null && skip[i]) continue;
                hp.visitVarInsn(Opcodes.LLOAD, 2);
                vhGet(hp, name, i, f, partOf);
                toBits(hp, f.getType());
                hp.visitInsn(Opcodes.LADD);
                hp.visitLdcInsn(HM);
                hp.visitInsn(Opcodes.LMUL);
                hp.visitInsn(Opcodes.DUP2);
                hp.visitIntInsn(Opcodes.BIPUSH, 31);
                hp.visitInsn(Opcodes.LUSHR);
                hp.visitInsn(Opcodes.LXOR);
                hp.visitVarInsn(Opcodes.LSTORE, 2);
            }
            hp.visitVarInsn(Opcodes.LLOAD, 2);
            hp.visitInsn(Opcodes.LRETURN);
            hp.visitMaxs(0, 0);
            hp.visitEnd();
        }
        // sameRefs(Object be, Object[] refs): refs compact (k over refSlots), split by slot like the rest
        String srDesc = "(Ljava/lang/Object;[Ljava/lang/Object;)Z";
        int[] rs = refSlots(fields, skip);
        MethodVisitor sa = cw.visitMethod(Opcodes.ACC_PUBLIC, "sameRefs", srDesc, null, null);
        sa.visitCode();
        org.objectweb.asm.Label sno = new org.objectweb.asm.Label();
        for (int ch = 0; ch < chunks; ch++) {
            sa.visitVarInsn(Opcodes.ALOAD, 0);
            sa.visitVarInsn(Opcodes.ALOAD, 1);
            sa.visitVarInsn(Opcodes.ALOAD, 2);
            sa.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "sameRefs" + ch, srDesc, false);
            sa.visitJumpInsn(Opcodes.IFEQ, sno);
        }
        sa.visitInsn(Opcodes.ICONST_1);
        sa.visitInsn(Opcodes.IRETURN);
        sa.visitLabel(sno);
        sa.visitInsn(Opcodes.ICONST_0);
        sa.visitInsn(Opcodes.IRETURN);
        sa.visitMaxs(0, 0);
        sa.visitEnd();
        for (int ch = 0; ch < chunks; ch++) {
            MethodVisitor sr = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "sameRefs" + ch, srDesc, null, null);
            sr.visitCode();
            org.objectweb.asm.Label rdiff = new org.objectweb.asm.Label();
            for (int k = 0; k < rs.length; k++) {
                if (rs[k] / CHUNK != ch) continue;
                vhGet(sr, name, rs[k], fields.get(rs[k]), partOf);
                sr.visitVarInsn(Opcodes.ALOAD, 2);
                pushInt(sr, k);
                sr.visitInsn(Opcodes.AALOAD);
                sr.visitJumpInsn(Opcodes.IF_ACMPNE, rdiff);
            }
            sr.visitInsn(Opcodes.ICONST_1);
            sr.visitInsn(Opcodes.IRETURN);
            sr.visitLabel(rdiff);
            sr.visitInsn(Opcodes.ICONST_0);
            sr.visitInsn(Opcodes.IRETURN);
            sr.visitMaxs(0, 0);
            sr.visitEnd();
        }
        cw.visitEnd();
        Class<?> c = MethodHandles.lookup().defineHiddenClassWithClassData(cw.toByteArray(), vhs, true).lookupClass();
        return (Snapper) c.getDeclaredConstructor().newInstance();
    }

    /** The interface method of a void routine: calls its chunk methods method0, method1, ... with its own (object) arguments. */
    private static void callChunks(ClassWriter cw, String owner, String method, String desc, int chunks) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null);
        m.visitCode();
        int args = Type.getArgumentTypes(desc).length;
        for (int ch = 0; ch < chunks; ch++) {
            for (int a = 0; a <= args; a++) m.visitVarInsn(Opcodes.ALOAD, a);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, method + ch, desc, false);
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Push the value of field i of the block entity in local 1 (via the static final VarHandle VHi). */
    private static void vhGet(MethodVisitor m, String name, int i, Field f, int[] partOf) {
        if (partOf != null && U != null) {
            unsafeAccess(m, partOf, i, f, false);
            return;
        }
        Class<?> t = f.getType();
        Class<?> owner = f.getDeclaringClass();
        boolean publicOwner = java.lang.reflect.Modifier.isPublic(owner.getModifiers()) && owner.getEnclosingClass() == null;
        String coord = publicOwner ? Type.getDescriptor(owner) : "Ljava/lang/Object;";
        String valueDesc = t.isPrimitive() ? Type.getDescriptor(t) : "Ljava/lang/Object;";
        m.visitFieldInsn(Opcodes.GETSTATIC, name, "VH" + i, "Ljava/lang/invoke/VarHandle;");
        loadRecv(m, partOf, i);
        if (publicOwner) m.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(owner));
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "get", "(" + coord + ")" + valueDesc, false);
    }

    /**
     * The composite (hundreds of slots over the entity and its AI objects): sun.misc.Unsafe with the field's offset as
     * a constant - an intrinsic, one load or store per slot whatever the method's size (VarHandles whose coordinate is
     * not the exact owner type, nested classes' fields, go through an adapting invoker the JIT does not fold: ~21 us
     * per replay check on the N100). Read (value on the stack) or write (put's bits in locals 3-4 / ref in 5).
     */
    private static void unsafeAccess(MethodVisitor m, int[] partOf, int i, Field f, boolean write) {
        Class<?> t = f.getType();
        m.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(SnapCompiler.class), "U", "Lsun/misc/Unsafe;");
        loadRecv(m, partOf, i);
        m.visitLdcInsn(U.objectFieldOffset(f));
        String kind = t == int.class ? "Int" : t == long.class ? "Long" : t == float.class ? "Float" : t == double.class ? "Double"
                : t == short.class ? "Short" : t == byte.class ? "Byte" : t == char.class ? "Char" : t == boolean.class ? "Boolean" : "Object";
        String desc = t.isPrimitive() ? Type.getDescriptor(t) : "Ljava/lang/Object;";
        if (!write) {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", "get" + kind, "(Ljava/lang/Object;J)" + desc, false);
            return;
        }
        if (t.isPrimitive()) {
            m.visitVarInsn(Opcodes.LLOAD, 3);
            if (t == double.class) {
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
            } else if (t != long.class) {
                m.visitInsn(Opcodes.L2I);
                if (t == float.class) m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false);
                else if (t == short.class) m.visitInsn(Opcodes.I2S);
                else if (t == byte.class) m.visitInsn(Opcodes.I2B);
                else if (t == char.class) m.visitInsn(Opcodes.I2C);
                else if (t == boolean.class) {
                    m.visitInsn(Opcodes.ICONST_1);
                    m.visitInsn(Opcodes.IAND);
                }
            }
        } else {
            m.visitVarInsn(Opcodes.ALOAD, 5);
        }
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", "put" + kind, "(Ljava/lang/Object;J" + desc + ")V", false);
    }

    /** sun.misc.Unsafe for the composite's generated accessors (null: unavailable, VarHandles then) */
    static final sun.misc.Unsafe U;

    static {
        sun.misc.Unsafe u = null;
        try {
            Field f = dev.lull.Names.fieldOf(sun.misc.Unsafe.class, "theUnsafe");
            f.setAccessible(true);
            u = (sun.misc.Unsafe) f.get(null);
        } catch (Throwable t) {
            System.out.println("[sleep/entsleep] no sun.misc.Unsafe (" + t + "): VarHandle accessors");
        }
        U = u;
    }

    /** The object slot i belongs to: local 1, or element partOf[i] of the Object[] in local 1. */
    private static void loadRecv(MethodVisitor m, int[] partOf, int i) {
        m.visitVarInsn(Opcodes.ALOAD, 1);
        if (partOf == null) return;
        m.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        pushInt(m, partOf[i]);
        m.visitInsn(Opcodes.AALOAD);
    }

    /** Primitive on the stack -> long bits, the encoding snap uses. */
    private static void toBits(MethodVisitor m, Class<?> t) {
        if (t == long.class) return;
        if (t == float.class) {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
            m.visitInsn(Opcodes.I2L);
        } else if (t == double.class) {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
        } else {
            m.visitInsn(Opcodes.I2L);
        }
    }

    private static void pushInt(MethodVisitor m, int v) {
        if (v <= 5) m.visitInsn(Opcodes.ICONST_0 + v);
        else if (v <= 127) m.visitIntInsn(Opcodes.BIPUSH, v);
        else m.visitIntInsn(Opcodes.SIPUSH, v);
    }

    /** Fallback: reflection (slow, but always works). */
    private static Snapper reflective(List<Field> fields, boolean values, boolean[] skip) {
        Field[] fs = fields.toArray(new Field[0]);
        return new Snapper() {
            @Override
            public void snap(Object be, long[] out) {
                for (int i = 0; i < fs.length; i++) out[i] = SnapCompiler.bits(fs[i], be, values);
            }

            @Override
            public void refs(Object be, Object[] out) {
                for (int i = 0; i < fs.length; i++) {
                    if (fs[i].getType().isPrimitive()) continue;
                    try {
                        out[i] = fs[i].get(be);
                    } catch (IllegalAccessException e) {
                        out[i] = null;
                    }
                }
            }

            @Override
            public void put(Object be, int i, long b, Object ref) {
                Field f = fs[i];
                try {
                    Class<?> t = f.getType();
                    if (t == int.class) f.setInt(be, (int) b);
                    else if (t == long.class) f.setLong(be, b);
                    else if (t == boolean.class) f.setBoolean(be, b != 0);
                    else if (t == short.class) f.setShort(be, (short) b);
                    else if (t == byte.class) f.setByte(be, (byte) b);
                    else if (t == char.class) f.setChar(be, (char) b);
                    else if (t == float.class) f.setFloat(be, Float.intBitsToFloat((int) b));
                    else if (t == double.class) f.setDouble(be, Double.longBitsToDouble(b));
                    else f.set(be, ref);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public long bits(Object be, int i) {
                return fs[i].getType().isPrimitive() ? SnapCompiler.bits(fs[i], be, false) : 0;
            }

            @Override
            public long hashPrims(Object be) {
                long[] v = new long[fs.length];
                boolean[] prim = new boolean[fs.length];
                for (int i = 0; i < fs.length; i++) {
                    prim[i] = fs[i].getType().isPrimitive();
                    if (prim[i]) v[i] = SnapCompiler.bits(fs[i], be, false);
                }
                return SnapCompiler.hashPrims(v, prim, skip);
            }

            @Override
            public boolean sameRefs(Object be, Object[] refs) {
                int[] rs = refSlots(fields, skip);
                for (int k = 0; k < rs.length; k++) {
                    try {
                        if (fs[rs[k]].get(be) != refs[k]) return false;
                    } catch (IllegalAccessException e) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean same(Object be, long[] prims, Object[] refs) {
                for (int i = 0; i < fs.length; i++) {
                    if (skip != null && skip[i]) continue;
                    try {
                        if (fs[i].getType().isPrimitive() ? SnapCompiler.bits(fs[i], be, false) != prims[i] : fs[i].get(be) != refs[i]) return false;
                    } catch (IllegalAccessException e) {
                        return false;
                    }
                }
                return true;
            }
        };
    }

    private static long bits(Field f, Object be, boolean values) {
        try {
            Class<?> t = f.getType();
            if (t == int.class) return f.getInt(be);
            if (t == long.class) return f.getLong(be);
            if (t == boolean.class) return f.getBoolean(be) ? 1 : 0;
            if (t == short.class) return f.getShort(be);
            if (t == byte.class) return f.getByte(be);
            if (t == char.class) return f.getChar(be);
            if (t == float.class) return Float.floatToRawIntBits(f.getFloat(be));
            if (t == double.class) return Double.doubleToRawLongBits(f.getDouble(be));
            return values ? EntityFp.fp(f.get(be)) : FieldDiff.fingerprint(f.get(be));
        } catch (IllegalAccessException e) {
            return 0;
        }
    }
}
