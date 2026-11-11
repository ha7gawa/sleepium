package dev.lull.optsupport;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The one case where the engine gives way to another mod: an @Overwrite of ours on a method another mod's mixin
 * changes. Mixin cannot inject into a method merged by an overwrite (a load error: "cannot inject into ... merged by"),
 * and an overwrite of theirs would drop ours or theirs anyway. Then our mixin is not applied (logged), and the method
 * runs vanilla's code with their change. The same for a @Redirect of ours on a call another mod's mixin redirects
 * (two redirects of one call: the second fails to load), and for any injector of ours into a method another mod's
 * mixin overwrites (Sodium's renderSectionLayer). Everything else of ours stays.
 */
public final class MixinYield {
    private MixinYield() {}

    public static long yielded;

    private static final java.util.Map<String, Boolean> DECIDED = new java.util.concurrent.ConcurrentHashMap<>();

    /** True when our mixin overwrites a method of the target that a foreign mixin touches (decided once per mixin). */
    public static boolean yields(String targetClassName, String mixinClassName) {
        if (Boolean.getBoolean("lab.yield.off")) return false;   // (tests: the conflict itself, nothing of ours gives way)
        Boolean d = DECIDED.get(mixinClassName);
        if (d == null) DECIDED.put(mixinClassName, d = yields0(targetClassName, mixinClassName));
        return d;
    }

    /** our mixins that add an interface of ours to their target: interface (internal name) -> {mixin class, target class} */
    private static volatile java.util.Map<String, String[]> PROVIDERS;
    private static volatile java.util.Set<String> UNAVAILABLE;

    /**
     * True when this mixin of ours uses (casts to, checks, calls) an interface another mixin of ours adds, and that one
     * gives way to another mod, directly or through a dependency: applied alone it would find the interface missing (ShapeCacheMixin gave way to
     * ModernFix's getCollisionShape and BlockCollisionMemoMixin's casts failed).
     */
    public static boolean needsYielded(String mixinClassName) {
        java.util.Map<String, String[]> providers = providers();
        java.util.Set<String> unavailable = unavailableProviders();
        for (String type : usedInterfaces(mixinClassName)) {
            String[] p = providers.get(type);
            if (p == null || p[0].equals(mixinClassName)) continue;
            if (unavailable.contains(p[0])) {
                yielded++;
                System.out.println("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: it needs "
                        + type.substring(type.lastIndexOf('/') + 1) + " from " + p[0].substring(p[0].lastIndexOf('.') + 1) + ", which gave way");
                return true;
            }
        }
        return false;
    }

    private static java.util.Set<String> usedInterfaces(String mixinClassName) {
        ClassNode cn;
        try {
            cn = org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(mixinClassName);
        } catch (Throwable t) {
            return java.util.Set.of();
        }
        java.util.Set<String> used = new java.util.TreeSet<>();
        for (MethodNode m : cn.methods)
            for (org.objectweb.asm.tree.AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof org.objectweb.asm.tree.TypeInsnNode t) used.add(t.desc);
                else if (in instanceof org.objectweb.asm.tree.MethodInsnNode mi && mi.getOpcode() == org.objectweb.asm.Opcodes.INVOKEINTERFACE) used.add(mi.owner);
            }
        return used;
    }

    private static synchronized java.util.Set<String> unavailableProviders() {
        if (UNAVAILABLE != null) return UNAVAILABLE;
        java.util.Map<String, String[]> providers = providers();
        java.util.Map<String, java.util.Set<String>> used = new java.util.TreeMap<>();
        java.util.Set<String> unavailable = new java.util.HashSet<>();
        for (String[] p : providers.values()) used.putIfAbsent(p[0], null);
        for (String mixin : used.keySet()) used.put(mixin, usedInterfaces(mixin));
        for (String[] p : providers.values()) if (yields(p[1], p[0])) unavailable.add(p[0]);
        // Propagate only from missing prerequisites: independent cycles stay available, regardless of query order.
        boolean changed;
        do {
            changed = false;
            for (var entry : used.entrySet()) {
                if (unavailable.contains(entry.getKey())) continue;
                for (String type : entry.getValue()) {
                    String[] p = providers.get(type);
                    if (p != null && unavailable.contains(p[0])) {
                        changed |= unavailable.add(entry.getKey());
                        break;
                    }
                }
            }
        } while (changed);
        UNAVAILABLE = java.util.Set.copyOf(unavailable);
        return UNAVAILABLE;
    }

    private static java.util.Map<String, String[]> providers() {
        java.util.Map<String, String[]> m = PROVIDERS;
        if (m != null) return m;
        java.util.Map<String, String[]> out = new java.util.HashMap<>();
        MixinTouch.ourMixins((cls, cn) -> {
            java.util.List<String> targets = MixinTouch.targets(cn);
            if (targets.isEmpty() || cn.interfaces == null) return;
            for (String i : cn.interfaces) if (i.startsWith("dev/lull/")) out.putIfAbsent(i, new String[] {cls, targets.get(0).replace('/', '.')});
        });
        if (Boolean.getBoolean("sleep.mixinIndex.debug")) System.out.println("[sleep/opt] mixin interface providers: " + out.keySet());
        PROVIDERS = out;
        return out;
    }

    /** an interface of the target (or of its superclasses, and their super-interfaces) whose method name + desc another mod's mixin changes */
    private static String changedDefault(String target, String name, String desc) {
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String k = target; k != null && !k.equals("java/lang/Object"); ) {
            ClassNode cn = node(k);
            if (cn == null) break;
            if (cn.interfaces != null) todo.addAll(cn.interfaces);
            k = cn.superName;
        }
        while (!todo.isEmpty()) {
            String i = todo.poll();
            if (!seen.add(i)) continue;
            if (MixinTouch.touched(i, name, desc)) return i;
            ClassNode cn = node(i);
            if (cn != null && cn.interfaces != null) todo.addAll(cn.interfaces);
        }
        return null;
    }

    private static ClassNode node(String internal) {
        try {
            return org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(internal.replace('/', '.'));
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean yields0(String targetClassName, String mixinClassName) {
        ClassNode cn;
        try {
            cn = org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(mixinClassName);
        } catch (Throwable t) {
            if (Boolean.getBoolean("sleep.mixinIndex.debug")) System.out.println("[sleep/opt] yield check: cannot read " + mixinClassName + ": " + t);
            return false;
        }
        String target = targetClassName.replace('.', '/');
        for (String key : MixinTouch.redirectKeys(cn)) {   // a @Redirect of ours on a call another mod redirects too: one of them fails to load
            int bar = key.indexOf('|');
            if (MixinTouch.redirected(target, key.substring(0, bar), key.substring(bar + 1))) {
                yielded++;
                System.out.println("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: the call to "
                        + key.substring(bar + 1) + " in " + targetClassName + "#" + key.substring(0, bar) + " is redirected by another mod's mixin");
                return true;
            }
            if (MixinTouch.argsModified(target, key.substring(0, bar), key.substring(bar + 1))) {   // their @ModifyArg(s) after our redirect
                yielded++;
                System.out.println("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: another mod's mixin changes the arguments of the call to "
                        + key.substring(bar + 1) + " in " + targetClassName + "#" + key.substring(0, bar));
                return true;
            }
        }
        for (String key : MixinTouch.argKeys(cn)) {   // a @ModifyArg(s) of ours on a call another mod redirects
            int bar = key.indexOf('|');
            if (MixinTouch.redirected(target, key.substring(0, bar), key.substring(bar + 1))) {
                yielded++;
                System.out.println("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: the call to "
                        + key.substring(bar + 1) + " in " + targetClassName + "#" + key.substring(0, bar) + " is redirected by another mod's mixin");
                return true;
            }
        }
        for (MethodNode m : cn.methods)   // an injector of ours into a method another mod overwrites: Mixin refuses it at load
            for (String t : MixinTouch.injectorTargets(m))
                if (MixinTouch.overwritten(target, t)) {
                    yielded++;
                    System.out.println("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: " + targetClassName + "#" + t
                            + " is overwritten by another mod's mixin (our injector cannot go into it)");
                    return true;
                }
        for (MethodNode m : cn.methods) {   // a plain method we add (an override of an interface default: Level.noCollision) that another mod adds too: one is skipped
            if (overwrite(m) || annotated(m) || m.name.startsWith("<") || m.name.contains("$") || (m.access & (org.objectweb.asm.Opcodes.ACC_SYNTHETIC | org.objectweb.asm.Opcodes.ACC_BRIDGE)) != 0) continue;
            if (MixinTouch.touched(target, m.name)) {
                yielded++;
                System.out.println("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: "
                        + targetClassName + "#" + m.name + " is added by another mod's mixin too (one of the two would be skipped)");
                return true;
            }            // an override of an interface default another mod's mixin changes there (VertexConsumer.putBulkData): ours would
            // run instead of their changed default
            String iface = changedDefault(target, m.name, m.desc);
            if (iface != null) {
                yielded++;
                System.out.println("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: "
                        + iface.replace('/', '.') + "#" + m.name + " is changed by another mod's mixin (our override in " + targetClassName + " would skip it)");
                return true;
            }
        }
        for (MethodNode m : cn.methods) {
            if (!overwrite(m)) continue;
            if (Boolean.getBoolean("sleep.mixinIndex.debug"))
                System.out.println("[sleep/opt] yield check: " + mixinClassName + " overwrites " + target + "#" + m.name + " touched=" + MixinTouch.touched(target, m.name, m.desc));
            if (MixinTouch.touched(target, m.name, m.desc)) {
                yielded++;
                System.out.println("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: "
                        + targetClassName + "#" + m.name + " is changed by another mod's mixin (our overwrite would break it)");
                return true;
            }
        }
        return false;
    }

    /** any annotation of Mixin's (Shadow, Unique, an injector, Accessor...): not a plain merged method */
    private static boolean annotated(MethodNode m) {
        for (java.util.List<AnnotationNode> l : java.util.Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
            if (l != null) for (AnnotationNode a : l) if (a.desc.startsWith("Lorg/spongepowered/") || a.desc.startsWith("Lcom/llamalad7/")) return true;
        return false;
    }

    private static boolean overwrite(MethodNode m) {
        for (java.util.List<AnnotationNode> l : java.util.Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
            if (l != null) for (AnnotationNode a : l) if ("Lorg/spongepowered/asm/mixin/Overwrite;".equals(a.desc)) return true;
        return false;
    }
}
