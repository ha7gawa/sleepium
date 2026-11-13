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

    /** per mixin: why it gives way ("" = it does not), decided once, quietly */
    private static final java.util.Map<String, String> DECIDED = new java.util.concurrent.ConcurrentHashMap<>();
    /** the mixins whose "not applied" line was printed (once each, when their own plugin asked) */
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * True when our mixin conflicts with a foreign one (decided once per mixin). Asked by the mixin's own plugin: the
     * reason is printed and counted here, once.
     */
    public static boolean yields(String targetClassName, String mixinClassName) {
        String why = conflict(targetClassName, mixinClassName);
        if (why == null) return false;
        if (REPORTED.add(mixinClassName)) {
            yielded++;
            System.out.println(why);
            NOTES.getOrDefault(mixinClassName, java.util.Map.of()).forEach(ModReport::mixin);   // (the Mods page, the report)
        }
        return true;
    }

    /** the same question without a trace (needsYielded's providers: the plugin may never ask about them - a flag is off) */
    private static String conflict(String targetClassName, String mixinClassName) {
        if (Boolean.getBoolean("lab.yield.off")) return null;   // (tests: the conflict itself, nothing of ours gives way)
        String why = DECIDED.computeIfAbsent(mixinClassName, k -> {
            String r = decide(targetClassName, mixinClassName);
            return r == null ? "" : r;
        });
        return why.isEmpty() ? null : why;
    }

    /** what a conflict check found: the "not applied" line and the foreign mixins behind it ("modid TAB mixin class") */
    private record Verdict(String why, java.util.Set<String> culprits) {}

    /** foreign mixins taken out by a rule so that ours applies: "OurMixin over modid (TheirMixin)" (the report) */
    public static final java.util.List<String> CANCELLED = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** our mixins that gave way because a rule asked (no conflict of their own): the report */
    public static final java.util.List<String> BY_RULE = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** per our mixin that would give way: mod id -> the Mods page's line (kept until its plugin asks: a flag off never does) */
    private static final java.util.Map<String, java.util.Map<String, String>> NOTES = new java.util.concurrent.ConcurrentHashMap<>();

    private static void note(String ourMixin, String culprit, String line) {
        NOTES.computeIfAbsent(ourMixin, k -> new java.util.concurrent.ConcurrentHashMap<>()).merge(culprit.substring(0, culprit.indexOf('\t')), line, (a, b) -> a);
    }

    private static String rule(String key) {
        return System.getProperty(key, "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** any rule asking ours to win (sleep.yield.<OurMixin>=never, sleep.mod.<id>.giveWay=never) */
    private static boolean anyNeverRule() {
        for (String k : System.getProperties().stringPropertyNames())
            if ((k.startsWith("sleep.yield.") || k.startsWith("sleep.mod.") && k.endsWith(".giveWay")) && "never".equals(rule(k))) return true;
        return false;
    }

    /**
     * Each of our config plugins, at its onLoad: with a rule asking ours to win, its enabled mixins are decided now. Mixin
     * loads every config's plugin before it prepares any config, and a foreign mixin can only be taken out of a config not
     * prepared yet; asked later (when our config is prepared) another mod's config may already be (11-13: the foreign
     * test config was).
     */
    public static void prepass(String mixinPackage, java.util.function.BiPredicate<String, String> enabled) {
        GameDefaults.ensure();   // (the settings' layers first: a pack's rule is in them)
        if (!anyNeverRule()) return;
        String pre = mixinPackage.endsWith(".") ? mixinPackage : mixinPackage + ".";
        MixinTouch.ourMixins((cls, cn) -> {
            if (!cls.startsWith(pre)) return;
            java.util.List<String> ts = MixinTouch.targets(cn);
            if (ts.isEmpty()) return;
            String target = ts.get(0).replace('/', '.');
            try {
                if (enabled.test(target, cls)) conflict(target, cls);
            } catch (Throwable t) {
                System.out.println("[sleep/opt] rules' early pass: " + cls + ": " + t);
            }
        });
    }

    /** the mods a rule asks to give way to always (sleep.mod.<id>.giveWay=always) */
    private static java.util.Set<String> alwaysMods() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String k : System.getProperties().stringPropertyNames())
            if (k.startsWith("sleep.mod.") && k.endsWith(".giveWay") && "always".equals(rule(k))) out.add(k.substring(10, k.length() - 8));
        return out;
    }

    /**
     * The conflict check under the rules of the pack, the player and the mods (notes/73): per our mixin
     * {@code sleep.yield.<OurMixin>} and per mod {@code sleep.mod.<modid>.giveWay} - conflicts (the default: give way on a
     * real conflict only), always (also where that mod's mixins merely touch the same methods), never (on a real conflict
     * ours applies and their conflicting mixins are taken out of their configs - only when all of them still can be).
     */
    private static String decide(String targetClassName, String mixinClassName) {
        String simple = simple(mixinClassName);
        String own = rule("sleep.yield." + simple);
        Verdict v = yields0(targetClassName, mixinClassName);
        if (v != null) {
            java.util.Set<String> mods = new java.util.TreeSet<>();
            for (String c : v.culprits()) mods.add(c.substring(0, c.indexOf('\t')));
            boolean win = !v.culprits().isEmpty() && ("never".equals(own) || mods.stream().allMatch(m -> "never".equals(rule("sleep.mod." + m + ".giveWay"))));
            if (!win) {
                for (String c : v.culprits())
                    note(mixinClassName, c, "gave way: our " + simple + " to its " + simple(c.substring(c.indexOf('\t') + 1)) + " - " + v.why().substring(v.why().indexOf("not applied: ") + 13));
                return v.why();
            }
            for (String c : v.culprits())
                if (!MixinTouch.cancel(c.substring(c.indexOf('\t') + 1), true)) {
                    System.out.println("[sleep/opt] " + simple + ": a rule asks it to apply over " + mods + ", but " + c.replace('\t', ' ')
                            + " cannot be taken out any more (its config is prepared): it gives way instead");
                    return v.why();
                }
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String c : v.culprits()) {
                MixinTouch.cancel(c.substring(c.indexOf('\t') + 1), false);
                String label = c.substring(0, c.indexOf('\t')) + " (" + simple(c.substring(c.indexOf('\t') + 1)) + ")";
                names.add(label);
                CANCELLED.add(simple + " over " + label);
                ModReport.mixin(c.substring(0, c.indexOf('\t')), "cancelled: its " + simple(c.substring(c.indexOf('\t') + 1)) + " taken out so that our " + simple + " applies (a rule: never give way)");
            }
            System.out.println("[sleep/opt] RULE: " + simple + " applied over another mod - CANCELLED " + names + " ("
                    + ("never".equals(own) ? "sleep.yield." + simple + "=never" : "sleep.mod.<id>.giveWay=never") + "); without the rule: " + v.why().replace("[sleep/opt] ", ""));
            return null;
        }
        java.util.Set<String> always = alwaysMods();
        if (!"always".equals(own) && always.isEmpty()) return null;
        ClassNode cn;
        try {
            cn = org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(mixinClassName);
        } catch (Throwable t) {
            return null;
        }
        java.util.Set<String> methods = new java.util.HashSet<>();
        MixinTouch.collect(cn, methods);
        for (String t : MixinTouch.targets(cn))
            for (String n : methods) {
                if (n.startsWith("<")) continue;   // (every mixin class has a constructor: not a touch of the target)
                for (String c : n.startsWith("*") ? MixinTouch.culpritsOnClass(t) : MixinTouch.culprits(t, n)) {
                    String mod = c.substring(0, c.indexOf('\t'));
                    if (!"always".equals(own) && !always.contains(mod)) continue;
                    String why = "[sleep/opt] " + simple + " not applied: a rule asks it to give way to " + mod + " (" + simple(c.substring(c.indexOf('\t') + 1))
                            + ") on " + t.replace('/', '.') + "#" + n + " (" + ("always".equals(own) ? "sleep.yield." + simple : "sleep.mod." + mod + ".giveWay") + "=always)";
                    BY_RULE.add(simple + " to " + mod);
                    note(mixinClassName, c, "gave way by a rule: our " + simple + " to its " + simple(c.substring(c.indexOf('\t') + 1)) + " on " + t.substring(t.lastIndexOf('/') + 1) + "#" + n);
                    return why;
                }
            }
        return null;
    }

    /** our mixins that add an interface of ours to their target: interface (internal name) -> {mixin class, target class} */
    private static volatile java.util.Map<String, String[]> PROVIDERS;
    private static volatile java.util.Set<String> UNAVAILABLE;
    /** an unavailable provider -> why, for the log: "gave way" or the chain it is left out through */
    private static final java.util.Map<String, String> UNAVAILABLE_WHY = new java.util.concurrent.ConcurrentHashMap<>();

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
                System.out.println("[sleep/opt] " + simple(mixinClassName) + " not applied: it needs " + simple(type) + " from " + simple(p[0])
                        + ", which " + UNAVAILABLE_WHY.getOrDefault(p[0], "gave way"));
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
        for (String[] p : providers.values())   // quietly: a provider whose flag is off is never asked by its plugin
            if (conflict(p[1], p[0]) != null && unavailable.add(p[0])) UNAVAILABLE_WHY.put(p[0], "gave way");
        // Propagate only from missing prerequisites: independent cycles stay available, regardless of query order.
        boolean changed;
        do {
            changed = false;
            for (var entry : used.entrySet()) {
                if (unavailable.contains(entry.getKey())) continue;
                for (String type : entry.getValue()) {
                    String[] p = providers.get(type);
                    if (p != null && unavailable.contains(p[0])) {
                        if (unavailable.add(entry.getKey())) {
                            changed = true;
                            UNAVAILABLE_WHY.put(entry.getKey(), "is not applied either (it needs " + simple(type) + " from " + simple(p[0]) + ", which "
                                    + UNAVAILABLE_WHY.getOrDefault(p[0], "gave way") + ")");
                        }
                        break;
                    }
                }
            }
        } while (changed);
        UNAVAILABLE = java.util.Set.copyOf(unavailable);
        return UNAVAILABLE;
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('/')) + 1);
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

    /** a dedicated server has no client classes: asking for one only makes the loader log an error ("invalid dist") */
    private static final boolean SERVER = !net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient();

    private static ClassNode node(String internal) {
        if (SERVER && internal.startsWith("net/minecraft/client/")) return null;
        try {
            return org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(internal.replace('/', '.'));
        } catch (Throwable t) {
            return null;
        }
    }

    /** " - by sodium (LevelRendererMixin), ..." for a "not applied" line (empty when the index cannot tell) */
    private static String by(String internalTarget, String method) {
        String w = MixinTouch.who(internalTarget, method);
        return w.isEmpty() ? "" : " - by " + w;
    }

    private static String byCall(String internalTarget, String method, String invoked) {
        String w = MixinTouch.whoCall(internalTarget, method, invoked);
        return w.isEmpty() ? "" : " - by " + w;
    }

    /** why this mixin of ours conflicts with a foreign one (its "not applied" line), or null */
    private static Verdict yields0(String targetClassName, String mixinClassName) {
        ClassNode cn;
        try {
            cn = org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(mixinClassName);
        } catch (Throwable t) {
            if (Boolean.getBoolean("sleep.mixinIndex.debug")) System.out.println("[sleep/opt] yield check: cannot read " + mixinClassName + ": " + t);
            return null;
        }
        String target = targetClassName.replace('.', '/');
        for (String key : MixinTouch.redirectKeys(cn)) {   // a @Redirect of ours on a call another mod redirects too: one of them fails to load
            int bar = key.indexOf('|');
            if (MixinTouch.redirected(target, key.substring(0, bar), key.substring(bar + 1))) {
                return new Verdict("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: the call to "
                        + key.substring(bar + 1) + " in " + targetClassName + "#" + key.substring(0, bar) + " is redirected by another mod's mixin" + byCall(target, key.substring(0, bar), key.substring(bar + 1)),
                        MixinTouch.culpritsCall(target, key.substring(0, bar), key.substring(bar + 1)));
            }
            if (MixinTouch.argsModified(target, key.substring(0, bar), key.substring(bar + 1))) {   // their @ModifyArg(s) after our redirect
                return new Verdict("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: another mod's mixin changes the arguments of the call to "
                        + key.substring(bar + 1) + " in " + targetClassName + "#" + key.substring(0, bar) + byCall(target, key.substring(0, bar), key.substring(bar + 1)),
                        MixinTouch.culpritsCall(target, key.substring(0, bar), key.substring(bar + 1)));
            }
        }
        for (String key : MixinTouch.argKeys(cn)) {   // a @ModifyArg(s) of ours on a call another mod redirects
            int bar = key.indexOf('|');
            if (MixinTouch.redirected(target, key.substring(0, bar), key.substring(bar + 1))) {
                return new Verdict("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: the call to "
                        + key.substring(bar + 1) + " in " + targetClassName + "#" + key.substring(0, bar) + " is redirected by another mod's mixin" + byCall(target, key.substring(0, bar), key.substring(bar + 1)),
                        MixinTouch.culpritsCall(target, key.substring(0, bar), key.substring(bar + 1)));
            }
        }
        for (MethodNode m : cn.methods)   // an injector of ours into a method another mod overwrites: Mixin refuses it at load
            for (String t : MixinTouch.injectorTargets(m))
                if (MixinTouch.overwritten(target, t)) {
                    return new Verdict("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: " + targetClassName + "#" + t
                            + " is overwritten by another mod's mixin (our injector cannot go into it)" + by(target, t),
                            MixinTouch.culprits(target, t));
                }
        for (MethodNode m : cn.methods) {   // a plain method we add (an override of an interface default: Level.noCollision) that another mod adds too: one is skipped
            if (overwrite(m) || annotated(m) || m.name.startsWith("<") || m.name.contains("$") || (m.access & (org.objectweb.asm.Opcodes.ACC_SYNTHETIC | org.objectweb.asm.Opcodes.ACC_BRIDGE)) != 0) continue;
            if (MixinTouch.touched(target, m.name)) {
                return new Verdict("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: "
                        + targetClassName + "#" + m.name + " is added by another mod's mixin too (one of the two would be skipped)" + by(target, m.name),
                        MixinTouch.culprits(target, m.name));
            }            // an override of an interface default another mod's mixin changes there (VertexConsumer.putBulkData): ours would
            // run instead of their changed default
            String iface = changedDefault(target, m.name, m.desc);
            if (iface != null) {
                return new Verdict("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: "
                        + iface.replace('/', '.') + "#" + m.name + " is changed by another mod's mixin (our override in " + targetClassName + " would skip it)" + by(iface, m.name),
                        MixinTouch.culprits(iface, m.name));
            }
        }
        for (MethodNode m : cn.methods) {
            if (!overwrite(m)) continue;
            if (Boolean.getBoolean("sleep.mixinIndex.debug"))
                System.out.println("[sleep/opt] yield check: " + mixinClassName + " overwrites " + target + "#" + m.name + " touched=" + MixinTouch.touched(target, m.name, m.desc));
            if (MixinTouch.touched(target, m.name, m.desc)) {
                return new Verdict("[sleep/opt] " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " not applied: "
                        + targetClassName + "#" + m.name + " is changed by another mod's mixin (our overwrite would break it)" + by(target, m.name),
                        MixinTouch.culprits(target, m.name));
            }
        }
        return null;
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
