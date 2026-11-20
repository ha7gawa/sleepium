package dev.lull.optsupport.sleep;

import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

/**
 * Write barrier for a mob's parts (notes/47, the event-driven sleep): every PUTFIELD into an object of a root
 * hierarchy (the entity, its goals, controls, navigation, ...) outside constructors gets a call {@code WB.w<root>(obj,
 * id)} in front of it. The call is a static method the JIT inlines into a load of the object's watcher (constant
 * offset) and a null check; the watcher (a sleeping or observed mob's part) hears the write only when it is set.
 * The roots get the watcher as a new field {@code sleep$w}. No branches or locals are added to the method, so its stack
 * map frames stay valid; only the stack grows by two.
 * Run from OptMixinPlugin.postApply for WriteBarrierMixin's targets (applied last, so other mixins' merged handlers
 * are covered too).
 */
public final class WriteBarrier {
    static final String WB = "dev/lull/optsupport/sleep/WB";
    /** the roots, in WB's order (WB.w0 .. ) */
    public static final String[] ROOTS = {"net/minecraft/world/entity/Entity", "net/minecraft/world/entity/ai/goal/Goal",
            "net/minecraft/world/entity/ai/goal/GoalSelector", "net/minecraft/world/entity/ai/control/MoveControl",
            "net/minecraft/world/entity/ai/control/LookControl", "net/minecraft/world/entity/ai/control/JumpControl",
            "net/minecraft/world/entity/ai/control/BodyRotationControl", "net/minecraft/world/entity/ai/navigation/PathNavigation",
            "net/minecraft/world/entity/ai/Brain", "net/minecraft/world/entity/ai/attributes/AttributeInstance",
            "net/minecraft/world/level/pathfinder/Path", "net/minecraft/world/item/ItemStack",
            "net/minecraft/world/entity/ai/attributes/AttributeMap",
            "net/minecraft/world/entity/ai/attributes/AttributeSupplier",
            "net/minecraft/world/damagesource/CombatTracker",
            "net/minecraft/world/level/pathfinder/NodeEvaluator",
            "net/minecraft/network/protocol/game/VecDeltaCodec",
            "net/minecraft/world/entity/ai/sensing/Sensing",
            "net/minecraft/world/entity/WalkAnimationState",
            "net/minecraft/world/entity/ItemBasedSteering"};
    public static final String FIELD = "sleep$w";
    private static final Map<String, Integer> ROOT_OF = new HashMap<>();
    public static int sites, classes, draws;
    /** -Dlab.wb.drawSites=false: no draw site numbers (MobSleep.draw walks the stack) */
    static final boolean DRAW_SITES = !"false".equals(System.getProperty("lab.wb.drawSites"));
    /** -Dlab.wb.writes=false: no write barrier calls (the draw sites only; mob sleep then cannot use the barrier) */
    static final boolean WRITES = !"false".equals(System.getProperty("lab.wb.writes"));
    static final String RANDOM = "net/minecraft/util/RandomSource";
    private static final Map<String, Boolean> IS_RANDOM = new HashMap<>();

    private WriteBarrier() {}

    private static final boolean SERVER = isDedicatedServer();
    private static final Map<String, Boolean> CLIENT_ONLY = new HashMap<>();

    private static boolean isDedicatedServer() {
        try {
            return net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Mixin's class info of a call's or a field's owner - except, on Forge's dedicated server, a client-only class
     * (@OnlyIn(Dist.CLIENT): LocalPlayer, Minecraft, MenuAccess, ... named in entity classes): ClassInfo.forName loads it
     * through the transformers and Forge's RuntimeDistCleaner refuses it (11-20: errors in the server log with the mob
     * sleep flags). Such a class never exists on that server, so no call on it ever runs: outside every root, not random.
     */
    static ClassInfo info(String x) {
        if (SERVER && clientOnly(x)) return null;
        return ClassInfo.forName(x);
    }

    /** the class's raw bytes (no transformers) carry @OnlyIn(Dist.CLIENT) */
    static boolean clientOnly(String x) {
        Boolean r = CLIENT_ONLY.get(x);
        if (r != null) return r;
        boolean found = false;
        try (java.io.InputStream in = WriteBarrier.class.getClassLoader().getResourceAsStream(x + ".class")) {
            if (in != null) {
                ClassNode cn = new ClassNode();
                new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(cn, org.objectweb.asm.ClassReader.SKIP_CODE
                        | org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
                for (java.util.List<org.objectweb.asm.tree.AnnotationNode> l : java.util.Arrays.asList(cn.invisibleAnnotations, cn.visibleAnnotations))
                    if (l != null) for (org.objectweb.asm.tree.AnnotationNode a : l)
                        if (a.desc.equals("Lnet/minecraftforge/api/distmarker/OnlyIn;") && a.values != null)
                            for (int i = 0; i + 1 < a.values.size(); i += 2)
                                if ("value".equals(a.values.get(i)) && a.values.get(i + 1) instanceof String[] e && "CLIENT".equals(e[1])) found = true;
            }
        } catch (Throwable t) {
            // unreadable: the old way (Mixin's lookup)
        }
        if (found) clientOnlySkipped++;
        CLIENT_ONLY.put(x, found);
        return found;
    }

    public static int clientOnlySkipped;

    /** root index of a class (internal name), -1 if outside every root hierarchy */
    static synchronized int rootOf(String owner) {
        Integer r = ROOT_OF.get(owner);
        if (r != null) return r;
        int found = -1;
        for (String x = owner; x != null && found < 0; ) {
            for (int k = 0; k < ROOTS.length; k++) if (ROOTS[k].equals(x)) found = k;
            if (found >= 0) break;
            ClassInfo ci = info(x);
            x = ci == null ? null : ci.getSuperName();
        }
        ROOT_OF.put(owner, found);
        return found;
    }

    /** a class (internal name) that is or implements RandomSource */
    static synchronized boolean isRandom(String owner) {
        Boolean r = IS_RANDOM.get(owner);
        if (r != null) return r;
        boolean found = false;
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
        todo.add(owner);
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!todo.isEmpty() && !found) {
            String x = todo.poll();
            if (x == null || !seen.add(x)) continue;
            if (x.equals(RANDOM)) found = true;
            ClassInfo ci = info(x);
            if (ci == null) continue;
            if (ci.getSuperName() != null) todo.add(ci.getSuperName());
            todo.addAll(ci.getInterfaces());
        }
        IS_RANDOM.put(owner, found);
        return found;
    }

    /**
     * Draw call sites: around every call on a RandomSource, {@code MobSleepHooks.site} holds the site's id (its key as
     * the stack walk would give it: owner.method+desc@line) for the call's duration - the draw hook needs no walk. No
     * branches; the stack grows by one.
     */
    static void drawSites(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) continue;
            int line = -1, here = 0;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof org.objectweb.asm.tree.LineNumberNode ln) line = ln.line;
                if (!(in instanceof MethodInsnNode mi) || mi.getOpcode() == Opcodes.INVOKESTATIC || mi.getOpcode() == Opcodes.INVOKESPECIAL) continue;
                if (!isRandom(mi.owner)) continue;
                int id = DrawSiteIds.id(cn.name + "." + m.name + m.desc + "@" + line);
                InsnList pre = new InsnList();
                pre.add(new LdcInsnNode(id));
                pre.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, "dev/lull/optsupport/sleep/MobSleepHooks", "site", "I"));
                m.instructions.insertBefore(in, pre);
                InsnList post = new InsnList();
                post.add(new InsnNode(Opcodes.ICONST_M1));
                post.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, "dev/lull/optsupport/sleep/MobSleepHooks", "site", "I"));
                m.instructions.insert(in, post);
                here++;
            }
            if (here > 0) {
                m.maxStack += 1;
                draws += here;
            }
        }
    }

    public static void transform(ClassNode cn) {
        for (String r : ROOTS)
            if (r.equals(cn.name)) {
                boolean has = false;
                for (FieldNode f : cn.fields) has |= f.name.equals(FIELD);
                if (!has) cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT, FIELD, "Ljava/lang/Object;", null, null));
            }
        int n = 0;
        for (MethodNode m : cn.methods) {
            if (!WRITES || m.name.equals("<init>") || m.name.equals("<clinit>") || m.instructions == null) continue;
            int here = 0;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.PUTFIELD) continue;
                FieldInsnNode fi = (FieldInsnNode) in;
                if (fi.name.equals(FIELD)) continue;
                int root = rootOf(fi.owner);
                if (root < 0) continue;
                int id = WBIds.id(fi.owner, fi.name, fi.desc);
                // stack: obj, v  ->  obj, v, obj  ->  WB.w<root>(obj, id)  ->  obj, v
                InsnList pre = new InsnList();
                char t = fi.desc.charAt(0);
                if (t == 'J' || t == 'D') {
                    pre.add(new InsnNode(Opcodes.DUP2_X1));   // v obj v
                    pre.add(new InsnNode(Opcodes.POP2));      // v obj
                    pre.add(new InsnNode(Opcodes.DUP_X2));    // obj v obj
                } else {
                    pre.add(new InsnNode(Opcodes.DUP2));      // obj v obj v
                    pre.add(new InsnNode(Opcodes.POP));       // obj v obj
                }
                pre.add(new LdcInsnNode(id));
                pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WB, "w" + root, "(Ljava/lang/Object;I)V", false));
                m.instructions.insertBefore(in, pre);
                here++;
            }
            if (here > 0) {
                m.maxStack += 2;
                n += here;
            }
        }
        sites += n;
        classes++;
        if (DRAW_SITES) drawSites(cn);
        if (WBIds.VERBOSE) System.out.println("[sleep/wb] " + cn.name + ": " + n + " write sites");
    }
}
