package dev.lull.optsupport;

import java.util.ArrayDeque;
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
 * The bytecode side of a mod adapter (EmfAdapter): ModLocals' slots, the mod's collections and its render-thread
 * checks. Runs at the end of a class' transformation (LateEventSites, after every mod's mixins - the accesses another
 * mod's mixins carried into vanilla classes are there by then).
 * <ul>
 * <li>fields (any class): GETSTATIC of a slot keeps the read and passes it through ModLocals.getX (the copy's value in
 * a job), PUTSTATIC becomes ModLocals.setX; instance slots (GETFIELD / PUTFIELD) go to getFieldX / setFieldX. The
 * owner's static initialiser is left alone (it sets the fields themselves).</li>
 * <li>the mod's own classes: a class extending HashMap / LinkedHashMap / HashSet extends the Sync one instead (its super
 * calls retargeted); a {@code new HashMap / LinkedHashMap / HashSet} stored straight into a field becomes the Sync one;
 * RenderSystem.isOnRenderThread() becomes ModLocals.renderThreadOrJob(); listed virtual calls become static ones taking
 * the receiver (EmfAdapter.bufferSource).</li>
 * </ul>
 * Straight-line replacements only: no frames to recompute; max stack raised by two where a method changed.
 */
public final class LocalizeTransformer {
    private LocalizeTransformer() {}

    static final String LOCALS = "dev/lull/optsupport/ModLocals";
    static final Map<String, String> SYNC = Map.of(
            "java/util/HashMap", "dev/lull/optsupport/SyncHashMap",
            "java/util/LinkedHashMap", "dev/lull/optsupport/SyncLinkedHashMap",
            "java/util/HashSet", "dev/lull/optsupport/SyncHashSet");

    /** classes changed, for the log */
    static final Set<String> CHANGED = ConcurrentHashMap.newKeySet();
    /** counts for the log: field accesses, collections, checks */
    static volatile int fieldSites, collections, checks, redirected;

    /** the slots' accesses in any class; true when the class changed */
    public static boolean fields(ClassNode cn) {
        if (!ModLocals.any()) return false;
        boolean changed = false;
        for (MethodNode m : cn.methods) {
            boolean clinit = m.name.equals("<clinit>");
            boolean mc = false;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; ) {
                AbstractInsnNode next = in.getNext();
                if (in instanceof FieldInsnNode fi) {
                    ModLocals.Slot s = ModLocals.slot(fi.owner, fi.name);
                    if (s != null && !(clinit && fi.owner.equals(cn.name)) && s.desc.equals(fi.desc)) {
                        rewriteField(m.instructions, fi, s);
                        mc = true;
                        fieldSites++;
                    }
                }
                in = next;
            }
            if (mc) {
                m.maxStack += 2;
                changed = true;
            }
        }
        if (changed) CHANGED.add(cn.name);
        return changed;
    }

    private static AbstractInsnNode push(int v) {
        if (v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v <= 127) return new IntInsnNode(Opcodes.BIPUSH, v);
        return new LdcInsnNode(v);
    }

    private static void rewriteField(InsnList list, FieldInsnNode fi, ModLocals.Slot s) {
        String k = String.valueOf(s.kind == 'L' ? 'L' : s.kind);
        String t = s.kind == 'L' ? "Ljava/lang/Object;" : k;
        InsnList r = new InsnList();
        switch (fi.getOpcode()) {
            case Opcodes.GETSTATIC -> {
                r.add(push(s.index));
                r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCALS, "get" + k, "(" + t + "I)" + t, false));
                if (s.kind == 'L') r.add(new TypeInsnNode(Opcodes.CHECKCAST, fi.desc.startsWith("[") ? fi.desc : Type.getType(fi.desc).getInternalName()));
                list.insert(fi, r);   // after the read: the field's value stays on the stack as the first argument
                return;
            }
            case Opcodes.PUTSTATIC -> {
                r.add(push(s.index));
                r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCALS, "set" + k, "(" + t + "I)V", false));
            }
            case Opcodes.GETFIELD -> {
                r.add(push(s.index));
                r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCALS, "getField" + k, "(Ljava/lang/Object;I)" + t, false));
            }
            default -> {   // PUTFIELD
                r.add(push(s.index));
                r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOCALS, "setField" + k, "(Ljava/lang/Object;" + t + "I)V", false));
            }
        }
        list.insert(fi, r);
        list.remove(fi);
    }

    /**
     * The adapted mod's own class: collections, render-thread checks, redirects (owner.name.desc of a virtual call ->
     * "owner.name": a static method taking the receiver first, same return). True when the class changed.
     */
    public static boolean own(ClassNode cn, Map<String, String> redirects) {
        boolean changed = fields(cn);
        String oldSuper = cn.superName;
        String newSuper = SYNC.get(oldSuper);
        if (newSuper != null) {
            cn.superName = newSuper;
            collections++;
            changed = true;
        }
        for (MethodNode m : cn.methods) {
            boolean mc = false;
            ArrayDeque<TypeInsnNode> news = new ArrayDeque<>();
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW) {
                    news.push(t);
                } else if (in instanceof MethodInsnNode mi) {
                    if (mi.getOpcode() == Opcodes.INVOKESPECIAL && newSuper != null && mi.owner.equals(oldSuper)
                            && !(mi.name.equals("<init>") && !news.isEmpty() && news.peek().desc.equals(oldSuper))) {
                        mi.owner = newSuper;   // super(...) and super.method(...) of a class now extending the Sync one
                        mc = true;
                    } else if (mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>") && !news.isEmpty() && news.peek().desc.equals(mi.owner)) {
                        TypeInsnNode n = news.pop();
                        String sync = SYNC.get(mi.owner);
                        AbstractInsnNode after = real(mi.getNext());
                        if (sync != null && after != null && (after.getOpcode() == Opcodes.PUTFIELD || after.getOpcode() == Opcodes.PUTSTATIC)) {
                            n.desc = sync;
                            mi.owner = sync;
                            collections++;
                            mc = true;
                        }
                    } else if (mi.getOpcode() == Opcodes.INVOKESTATIC && mi.owner.equals("com/mojang/blaze3d/systems/RenderSystem")
                            && mi.name.equals("isOnRenderThread") && mi.desc.equals("()Z")) {
                        mi.owner = LOCALS;
                        mi.name = "renderThreadOrJob";
                        checks++;
                        mc = true;
                    } else if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL && redirects != null) {
                        String to = redirects.get(mi.owner + "." + mi.name + mi.desc);
                        if (to != null) {
                            int dot = to.lastIndexOf('.');
                            mi.setOpcode(Opcodes.INVOKESTATIC);
                            mi.desc = "(L" + mi.owner + ";" + mi.desc.substring(1);
                            mi.owner = to.substring(0, dot);
                            mi.name = to.substring(dot + 1);
                            mi.itf = false;
                            redirected++;
                            mc = true;
                        }
                    }
                }
            }
            if (mc) changed = true;
        }
        if (changed) CHANGED.add(cn.name);
        return changed;
    }

    /** the next instruction that is one (labels, line numbers and frames skipped) */
    private static AbstractInsnNode real(AbstractInsnNode in) {
        while (in != null && in.getOpcode() < 0) in = in.getNext();
        return in;
    }

    public static String report() {
        return String.format("%d classes, %d field accesses, %d collections, %d render-thread checks, %d redirects",
                CHANGED.size(), fieldSites, collections, checks, redirected);
    }
}
