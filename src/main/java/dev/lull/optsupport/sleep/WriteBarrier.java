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
            "net/minecraft/world/entity/ItemBasedSteering",
            // SpecTicks (SpecTx): the synched data items and the random's gaussian state are an entity's too
            "net/minecraft/network/syncher/SynchedEntityData", "net/minecraft/network/syncher/SynchedEntityData$DataItem",
            "net/minecraft/world/level/levelgen/MarsagliaPolarGaussian"};
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

    /** root index of a class (internal name), -1 if outside every root hierarchy */
    static synchronized int rootOf(String owner) {
        Integer r = ROOT_OF.get(owner);
        if (r != null) return r;
        int found = -1;
        for (String x = owner; x != null && found < 0; ) {
            for (int k = 0; k < ROOTS.length; k++) if (ROOTS[k].equals(x)) found = k;
            if (found >= 0) break;
            ClassInfo ci = ClassInfo.forName(x);
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
            ClassInfo ci = ClassInfo.forName(x);
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

    /** -Dlab.wb.mutations=false: no barrier before mutating calls on collections (SpecTx then cannot undo them) */
    static final boolean MUTATIONS = !"false".equals(System.getProperty("lab.wb.mutations"));
    public static int mutationSites, mutationMisses;

    /** a JDK / fastutil / Guava collection, map, iterator or atomic type (internal name) */
    static boolean mutableType(String owner) {
        if (owner.startsWith("java/util/concurrent/atomic/Atomic")) return true;
        if (owner.startsWith("it/unimi/dsi/fastutil/")) return true;
        if (owner.startsWith("com/google/common/collect/") && !owner.contains("Immutable")) return true;
        if (!owner.startsWith("java/util/")) return false;
        String n = owner.substring(10);
        return n.equals("Map") || n.equals("Set") || n.equals("List") || n.equals("Collection") || n.equals("Iterator") || n.equals("ListIterator")
                || n.equals("Queue") || n.equals("Deque") || n.equals("SortedMap") || n.equals("NavigableMap") || n.equals("SortedSet") || n.equals("NavigableSet")
                || n.endsWith("Map") || n.endsWith("Set") || n.endsWith("List") || n.equals("ArrayDeque") || n.equals("PriorityQueue") || n.equals("Stack") || n.equals("Vector")
                || n.equals("BitSet");
    }

    static boolean mutator(String name) {
        for (String p : new String[] {"put", "remove", "rem", "add", "set", "clear", "compute", "merge", "replace", "poll", "push", "pop", "offer", "trim",
                "retain", "sort", "increment", "decrement", "compareAndSet", "weakCompare", "getAnd", "lazySet", "accumulate", "updateAnd", "flip"})
            if (name.startsWith(p)) return true;
        return false;
    }

    /**
     * Before every mutating call on a collection, map, iterator or atomic: {@code WB.mut(receiver)}. The receiver's value
     * is found by a source analysis of the method's frames: after each instruction that produced it, DUP and the call
     * (stack net zero, no branches, no locals - the frames stay valid). Unresolvable receivers are counted.
     */
    static void mutSites(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0 || m.name.equals("<clinit>")) continue;
            java.util.List<MethodInsnNode> calls = new java.util.ArrayList<>();
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext())
                if (in instanceof MethodInsnNode mi && (mi.getOpcode() == Opcodes.INVOKEINTERFACE || mi.getOpcode() == Opcodes.INVOKEVIRTUAL)
                        && mutableType(mi.owner) && mutator(mi.name)) calls.add(mi);
            if (calls.isEmpty()) continue;
            org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
            try {
                frames = new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(cn.name, m);
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException ex) {
                mutationMisses += calls.size();
                continue;
            }
            java.util.Set<AbstractInsnNode> after = new java.util.LinkedHashSet<>();
            for (MethodInsnNode mi : calls) {
                var f = frames[m.instructions.indexOf(mi)];
                if (f == null) continue;   // (dead code)
                int argSlots = 0;
                for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(mi.desc)) argSlots++;   // (SourceInterpreter: one value per argument)
                var recv = f.getStack(f.getStackSize() - argSlots - 1);
                boolean ok = !recv.insns.isEmpty();
                for (AbstractInsnNode src : recv.insns) {
                    int op = src.getOpcode();   // (a value from NEW / a DUP may be an uninitialised object: not passed anywhere)
                    if (op == Opcodes.NEW || op >= Opcodes.DUP && op <= Opcodes.SWAP || op < 0) ok = false;
                }
                if (!ok) {
                    mutationMisses++;
                    continue;
                }
                after.addAll(recv.insns);
            }
            for (AbstractInsnNode src : after) {
                InsnList post = new InsnList();
                post.add(new InsnNode(Opcodes.DUP));
                post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WB, "mut", "(Ljava/lang/Object;)V", false));
                m.instructions.insert(src, post);
            }
            if (!after.isEmpty()) {
                m.maxStack += 1;
                mutationSites += after.size();
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
        if (MUTATIONS) mutSites(cn);
        if (DRAW_SITES) drawSites(cn);
        if (WBIds.VERBOSE) System.out.println("[sleep/wb] " + cn.name + ": " + n + " write sites");
    }
}
