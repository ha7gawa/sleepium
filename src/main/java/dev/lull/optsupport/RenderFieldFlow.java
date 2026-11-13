package dev.lull.optsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Which fields another mod passes between its mixins around an entity's draw and its mixins in the code a render worker
 * runs for the entity (notes/71) - instead of refusing every mod with mixins on both sides (ParallelRender.autoInner).
 * <ul>
 * <li>the dispatcher side (D): injections into EntityRenderDispatcher.render and LevelRenderer.renderEntity - with the
 * merge inside the dispatcher they run on the render thread, around the replayed renderer;</li>
 * <li>the worker side (W): injections into models, model parts, entity renderers and their layers, ItemRenderer,
 * RenderType, and the dispatcher's flame / shadow / hitbox - run on a worker while the render thread draws other
 * entities.</li>
 * </ul>
 * Both walked through the mods' own code (calls, virtual / interface calls to the implementations in the mod files met,
 * lambdas). A mod is trusted when nothing D writes is read by W, W writes no global state (statics other than lazy
 * initialisation; instance fields of objects not per entity / model / renderer), W touches no GL state, and the walk
 * followed everything. Otherwise its entities stay on the render thread, the reasons in the log. Steps 2-3 of notes/71
 * (reads checked at the merge, global writes localized) will turn the D->W and global-write cases into trusted ones.
 * <p>
 * Read from the mod files themselves (no class loaded). -Dlab.prender.fieldFlow=true: autoInner trusts what it trusts
 * (off: only the log).
 */
public final class RenderFieldFlow {
    private RenderFieldFlow() {}

    /** a mod's result */
    public record Verdict(String mod, boolean trusted, Set<String> dToW, Set<String> dGame, Set<String> wGlobal, Set<String> wUnknown, Set<String> gl,
                          Set<String> notFollowed) {
        /** only fields from the dispatcher read in the models: the field log can check them at the merge */
        boolean onlyDToW() {
            return !trusted && !dToW.isEmpty() && dGame.isEmpty() && wGlobal.isEmpty() && wUnknown.isEmpty() && gl.isEmpty() && notFollowed.isEmpty();
        }

        String why() {
            if (trusted) return "trusted (no field from the dispatcher read in the models, no global writes there)";
            if (LOGGED.contains(mod)) return "trusted with its reads checked at the merge " + cut(dToW);
            List<String> r = new ArrayList<>();
            if (!dToW.isEmpty()) r.add("dispatcher -> models " + cut(dToW));
            if (!dGame.isEmpty()) r.add("the game's fields written around the draw " + cut(dGame));
            if (!wGlobal.isEmpty()) r.add("global writes in the models " + cut(wGlobal));
            if (!wUnknown.isEmpty()) r.add("writes to objects not followed " + cut(wUnknown));
            if (!gl.isEmpty()) r.add("GL state " + cut(gl));
            if (!notFollowed.isEmpty()) r.add("code not followed " + cut(notFollowed));
            return String.join("; ", r);
        }
    }

    private static Map<String, Verdict> byConfig;
    /** mods trusted with their dispatcher -> model reads logged and checked at the merge (FieldLog) */
    static final Set<String> LOGGED = new java.util.HashSet<>();
    /** per mod: its worker-side walk (the read sites, the fields' descriptors) */
    private static final Map<String, Walk> W_WALK = new HashMap<>();
    /** the mod classes made targets of the flow carrier */
    static final Set<String> CARRIED = new TreeSet<>();
    private static final Map<String, Verdict> BY_MOD = new LinkedHashMap<>();

    /** autoInner: may this config's mixins in the dispatcher and in the worker's code be trusted (with the flag on) */
    public static boolean trusted(String config) {
        if (!Boolean.getBoolean("lab.prender.fieldFlow")) return false;
        Verdict v = of(config);
        return v != null && (v.trusted || LOGGED.contains(v.mod));
    }

    public static synchronized Verdict of(String config) {
        if (!Boolean.getBoolean("lab.prender.fieldFlow")) return null;   // (off: no analysis at start - it costs seconds in large packs)
        if (byConfig == null) analyse();
        return byConfig.get(config);
    }

    static String cut(Set<String> s) {
        List<String> l = new ArrayList<>();
        for (String x : s) l.add(short_(x));
        return l.size() <= 6 ? l.toString() : l.subList(0, 6) + " +" + (l.size() - 6);
    }

    private static String short_(String key) {
        int dot = key.lastIndexOf('.', key.indexOf('(') < 0 ? key.length() : key.indexOf('('));
        String owner = dot < 0 ? key : key.substring(0, dot);
        return owner.substring(owner.lastIndexOf('/') + 1) + (dot < 0 ? "" : key.substring(dot));
    }

    // ---------------------------------------------------------------------------------------------------------------

    private record Handler(MixinTouch.ForeignConfig cfg, ClassNode mixin, MethodNode m, String side) {}

    static final String DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";

    /** D, W or null for an injection into target.method (official names; ALL: any method) */
    static String side(String target, String method) {
        boolean all = method.startsWith("*");   // MixinTouch: "*" any method, "*@member" a wildcard at a member
        if (target.equals(DISPATCHER)) {
            if (all || method.equals("render")) return "D";
            return method.equals("renderFlame") || method.equals("renderShadow") || method.equals("renderHitbox") ? "W" : null;
        }
        if (target.equals("net/minecraft/client/renderer/LevelRenderer")) return all || method.equals("renderEntity") ? "D" : null;
        if (target.startsWith("net/minecraft/client/model/") || target.startsWith("net/minecraft/client/renderer/entity/")
                || target.equals("net/minecraft/client/renderer/RenderType")) return "W";
        return null;
    }

    private static boolean earlyDone;

    /**
     * FlowMixinPlugin.acceptTargets (every config prepared, no game class transformed yet): the analysis; with
     * -Dlab.prender.fieldFlow=true the dispatcher -> model fields of the mods that need nothing else are logged
     * (FieldLog), and the mod classes reading them become targets of the flow carrier, so that LateEventSites sees
     * them. Mixin decides per class at its load: hasMixinsFor(name) on its configs' mappings - the carrier's
     * MixinInfo is added there for each such class (Mixin 0.8.5 checks a target against the mixin's declared ones
     * only while preparing; the carrier is empty).
     */
    public static synchronized void early() {
        if (earlyDone) return;
        earlyDone = true;
        if (!Boolean.getBoolean("lab.prender.fieldFlow")) return;   // (off: not analysed at all)
        if (byConfig == null) analyse();
        if (!LateEventSites.register()) {
            System.out.println("[sleep/opt] render field flow: Mixin's extensions out of reach, no reads logged");
            return;
        }
        Object mc = null;
        java.lang.reflect.Method mixinsFor = null;
        Object carrier = null;
        try {
            mc = processorConfig("sleepium-flow.mixins.json");
            if (mc != null) {
                mixinsFor = mc.getClass().getMethod("getMixinsFor", String.class);
                mixinsFor.setAccessible(true);
                List<?> l = (List<?>) mixinsFor.invoke(mc, "dev.lull.optsupport.FlowCarrierTarget");
                if (!l.isEmpty()) carrier = l.get(0);
            }
        } catch (Throwable t) {
            System.out.println("[sleep/opt] render field flow: the flow carrier not reachable (" + t + "), no reads logged");
            return;
        }
        if (carrier == null) {
            String keys = "?";
            try {
                java.lang.reflect.Field f = mc == null ? null : mc.getClass().getDeclaredField("mixinMapping");
                if (f != null) {
                    f.setAccessible(true);
                    keys = String.valueOf(((Map<?, ?>) f.get(mc)).keySet());
                }
            } catch (Throwable t) {
                keys = t.toString();
            }
            System.out.println("[sleep/opt] render field flow: the flow carrier not prepared, no reads logged (config " + (mc != null) + ", its targets " + keys + ")");
            return;
        }
        for (Verdict v : BY_MOD.values()) {
            if (!v.onlyDToW()) continue;
            Walk w = W_WALK.get(v.mod);
            boolean ok = true;
            for (String k : v.dToW) {
                String d = w.desc.get(k);
                String bare = d == null ? null : d.startsWith("S") ? d.substring(1) : d;
                // a container's identity says nothing of its content: such a flow stays refused
                if (bare == null || bare.startsWith("Ljava/util/") || bare.startsWith("[")) ok = false;
            }
            if (!ok) continue;
            Set<String> sites = new TreeSet<>();
            for (String k : v.dToW) sites.addAll(w.readSites.getOrDefault(k, Set.of()));
            try {
                for (String k : v.dToW) {
                    String d = w.desc.get(k);
                    boolean st = d.startsWith("S");
                    FieldLog.register(k.substring(0, k.lastIndexOf('.')), k.substring(k.lastIndexOf('.') + 1), st ? d.substring(1) : d, st);
                }
                for (String c : sites) {
                    if (outside(c)) continue;
                    ClassNode cn = Classes.read(null, c);
                    if (cn == null || !MixinTouch.targets(cn).isEmpty()) continue;   // a mixin's code lands in its targets, which pass anyway
                    @SuppressWarnings("unchecked") List<Object> l = (List<Object>) mixinsFor.invoke(mc, c.replace('/', '.'));
                    if (!l.contains(carrier)) l.add(carrier);
                    CARRIED.add(c);
                }
                LOGGED.add(v.mod);
                System.out.println("[sleep/opt] render field flow: " + v.mod + ": reads checked at the merge " + cut(v.dToW) + ", classes carried " + cut(sites));
            } catch (Throwable t) {
                System.out.println("[sleep/opt] render field flow: " + v.mod + " not set up (" + t + ")");
            }
        }
    }

    /**
     * A config as Mixin's processor holds it (Mixins.getConfigs lists only the configs not yet taken by the processor;
     * while the configs are prepared ours is in its pending list): MixinTransformer.processor -> pendingConfigs / configs
     */
    private static Object processorConfig(String name) throws ReflectiveOperationException {
        Object t = org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        java.lang.reflect.Field pf = t.getClass().getDeclaredField("processor");
        pf.setAccessible(true);
        Object proc = pf.get(t);
        for (String list : new String[] {"pendingConfigs", "configs"}) {
            java.lang.reflect.Field lf = proc.getClass().getDeclaredField(list);
            lf.setAccessible(true);
            for (Object c : (List<?>) lf.get(proc))
                if (c instanceof org.spongepowered.asm.mixin.extensibility.IMixinConfig mc && mc.getName().equals(name)) return c;
        }
        return null;
    }

    private static synchronized void analyse() {
        long t0 = System.nanoTime();
        byConfig = new HashMap<>();
        Map<String, List<Handler>> byMod = new LinkedHashMap<>();
        Map<String, List<String>> configsOf = new HashMap<>();
        Map<String, Set<String>> prepared = prepared();
        int skipped = 0;
        for (MixinTouch.ForeignConfig cfg : MixinTouch.foreignConfigs()) {
            configsOf.computeIfAbsent(cfg.mod(), k -> new ArrayList<>()).add(cfg.name());
            Set<String> applied = prepared.get(cfg.name());
            for (String cls : cfg.mixins()) {
                if (applied != null && !applied.contains(cls)) {   // its config's plugin declined it (a compat mixin for another version)
                    skipped++;
                    continue;
                }
                ClassNode cn = Classes.read(cfg.file(), cls.replace('.', '/'));
                if (cn == null) continue;
                List<String> targets = MixinTouch.targets(cn);
                for (MethodNode m : cn.methods) {
                    List<String> names = MixinTouch.handlerTargets(cfg.refmap(), cn.name, m);
                    if (names.isEmpty()) continue;
                    Set<String> sides = new HashSet<>();
                    for (String t : targets) for (String n : names) {
                        String s = side(t, n);
                        if (s != null && Classes.declares(t, n)) sides.add(s);   // a selector of another version (Pehkui's compat114) selects nothing
                    }
                    for (String s : sides) byMod.computeIfAbsent(cfg.mod(), k -> new ArrayList<>()).add(new Handler(cfg, cn, m, s));
                }
            }
        }
        long tScan = System.nanoTime();
        for (var e : byMod.entrySet()) {
            long tMod = System.nanoTime();
            List<Handler> hs = e.getValue();
            if (hs.stream().noneMatch(h -> h.side.equals("D")) || hs.stream().noneMatch(h -> h.side.equals("W"))) continue;
            Walk d = new Walk(), w = new Walk();
            for (Handler h : hs) (h.side.equals("D") ? d : w).walk(h.cfg.file(), h.mixin.name, h.m, 0);
            Set<String> dToW = new TreeSet<>(d.writes);
            dToW.retainAll(w.reads);
            Set<String> nf = new TreeSet<>(w.notFollowed);
            nf.addAll(d.notFollowed);
            // the game's own fields written around the draw: the vanilla renderer may read them, code this walk never sees
            Set<String> dGame = new TreeSet<>();
            for (String k : d.writes) if (outside(k.substring(0, k.lastIndexOf('.')))) dGame.add(k);
            boolean ok = dToW.isEmpty() && dGame.isEmpty() && w.global.isEmpty() && w.unknown.isEmpty() && w.gl.isEmpty() && nf.isEmpty();
            Verdict v = new Verdict(e.getKey(), ok, dToW, dGame, w.global, w.unknown, w.gl, nf);
            W_WALK.put(e.getKey(), w);
            BY_MOD.put(e.getKey(), v);
            for (String c : configsOf.getOrDefault(e.getKey(), List.of())) byConfig.put(c, v);
            System.out.println("[sleep/opt] render field flow: " + e.getKey() + " (" + hs.size() + " injections: "
                    + hs.stream().filter(h -> h.side.equals("D")).count() + " around the draw, " + hs.stream().filter(h -> h.side.equals("W")).count()
                    + " in models / renderers; " + d.methods + " + " + w.methods + " methods walked, " + (System.nanoTime() - tMod) / 1_000_000 + " ms): " + v.why());
        }
        System.out.println("[sleep/opt] render field flow: " + BY_MOD.size() + " mods with mixins on both sides, "
                + BY_MOD.values().stream().filter(Verdict::trusted).count() + " trusted, " + skipped + " mixins their configs declined, the mixins read in "
                + (tScan - t0) / 1_000_000 + " ms (lookups: has " + Classes.hasN + " x " + Classes.hasNs / 1_000_000 + " ms, game layer " + Classes.resN + " x "
                + Classes.resNs / 1_000_000 + " ms, mod reads " + Classes.readN + " x " + Classes.readNs / 1_000_000 + " ms; subclass index of " + Classes.idxN
                + " mod files, " + Classes.idxClasses + " classes, " + Classes.idxNs / 1_000_000 + " ms), "
                + (System.nanoTime() - t0) / 1_000_000 + " ms"
                + (Boolean.getBoolean("lab.prender.fieldFlow") ? "" : " (log only: -Dlab.prender.fieldFlow=true to use)"));
    }

    /**
     * The mixin classes Mixin prepared per config (its mapping: target -> mixins, after the configs' plugins declined
     * theirs); a config missing here (not prepared yet, or Mixin's internals out of reach): all its listed mixins count.
     */
    private static Map<String, Set<String>> prepared() {
        Map<String, Set<String>> out = new HashMap<>();
        try {
            for (org.spongepowered.asm.mixin.transformer.Config c : org.spongepowered.asm.mixin.Mixins.getConfigs()) {
                Object mc = c.getConfig();
                if (mc == null) continue;
                java.lang.reflect.Field f = mc.getClass().getDeclaredField("mixinMapping");
                f.setAccessible(true);
                if (!(f.get(mc) instanceof Map<?, ?> mapping) || mapping.isEmpty()) continue;
                Set<String> names = new HashSet<>();
                for (Object list : mapping.values())
                    if (list instanceof List<?> l) for (Object mi : l) if (mi instanceof org.spongepowered.asm.mixin.extensibility.IMixinInfo i) names.add(i.getClassName());
                out.put(c.getName(), names);
            }
        } catch (Throwable t) {
            System.out.println("[sleep/opt] render field flow: prepared mixins not read (" + t + "): every listed mixin counts");
        }
        return out;
    }

    /** where code comes from: vanilla / libraries are not walked (their calls end the walk there) */
    static boolean outside(String owner) {
        return owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/")
                || owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/") || owner.startsWith("net/minecraftforge/")
                || owner.startsWith("org/spongepowered/") || owner.startsWith("com/llamalad7/") || owner.startsWith("org/joml/")
                || owner.startsWith("it/unimi/") || owner.startsWith("com/google/") || owner.startsWith("org/lwjgl/")
                || owner.startsWith("org/apache/") || owner.startsWith("org/slf4j/") || owner.startsWith("dev/lull/");
    }

    /** objects one entity's draw owns (its entity, its renderer set's models / parts / renderers / layers, its pose stack) */
    static boolean perObject(String vanillaClass) {
        return vanillaClass.startsWith("net/minecraft/client/model/")
                || vanillaClass.startsWith("net/minecraft/client/renderer/entity/") && !vanillaClass.equals(DISPATCHER)
                        && !vanillaClass.equals("net/minecraft/client/renderer/entity/ItemRenderer")
                || vanillaClass.startsWith("net/minecraft/world/entity/") || vanillaClass.equals("com/mojang/blaze3d/vertex/PoseStack")
                || vanillaClass.equals("com/mojang/blaze3d/vertex/PoseStack$Pose");
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** one side's walk */
    static final class Walk {
        final Set<String> reads = new TreeSet<>(), writes = new TreeSet<>(), global = new TreeSet<>(), unknown = new TreeSet<>(),
                gl = new TreeSet<>(), notFollowed = new TreeSet<>();
        final Set<String> seen = new HashSet<>();
        /** field -> descriptor ("S" prefix: static); field -> the classes reading it (the walked methods' owners) */
        final Map<String, String> desc = new HashMap<>();
        final Map<String, Set<String>> readSites = new HashMap<>();
        int methods;

        void walk(ModFile home, String owner, MethodNode m, int depth) {
            if (m.instructions == null || !seen.add(owner + "." + m.name + m.desc)) return;
            if (depth > 14) {
                notFollowed.add(owner + "." + m.name + " (too deep)");
                return;
            }
            methods++;
            boolean ctor = m.name.equals("<init>"), clinit = m.name.equals("<clinit>");
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof FieldInsnNode f) {
                    if (f.owner.startsWith("java/")) continue;
                    String key = f.owner + "." + f.name;
                    int op = f.getOpcode();
                    desc.putIfAbsent(key, (op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC ? "S" : "") + f.desc);
                    if (op == Opcodes.GETSTATIC || op == Opcodes.GETFIELD) {
                        reads.add(key);
                        readSites.computeIfAbsent(key, k -> new TreeSet<>()).add(owner);
                        continue;
                    }
                    if (clinit && f.owner.equals(owner) || ctor && op == Opcodes.PUTFIELD && f.owner.equals(owner)) continue;   // initialising
                    writes.add(key);
                    if (op == Opcodes.PUTSTATIC) {
                        if (!lazy(m, f)) global.add(key);
                    } else if (!Classes.perObjectField(home, f.owner)) {
                        (Classes.isMixin(home, f.owner) || outside(f.owner) ? global : unknown).add(key);
                    }
                } else if (in instanceof MethodInsnNode mi) {
                    call(home, mi.owner, mi.name, mi.desc, mi.getOpcode() == Opcodes.INVOKEVIRTUAL || mi.getOpcode() == Opcodes.INVOKEINTERFACE, depth);
                } else if (in instanceof InvokeDynamicInsnNode id) {
                    for (Object b : id.bsmArgs) if (b instanceof Handle h) call(home, h.getOwner(), h.getName(), h.getDesc(), false, depth);
                }
            }
        }

        /** a static set only while it is null / false (GETSTATIC of it, then IFNONNULL / IFNULL / IFNE / IFEQ, earlier in the method) */
        private static boolean lazy(MethodNode m, FieldInsnNode put) {
            for (AbstractInsnNode in = put.getPrevious(); in != null; in = in.getPrevious()) {
                if (in instanceof FieldInsnNode g && g.getOpcode() == Opcodes.GETSTATIC && g.owner.equals(put.owner) && g.name.equals(put.name)) {
                    AbstractInsnNode n = g.getNext();
                    while (n != null && n.getOpcode() < 0) n = n.getNext();
                    if (n instanceof JumpInsnNode j && (j.getOpcode() == Opcodes.IFNONNULL || j.getOpcode() == Opcodes.IFNULL
                            || j.getOpcode() == Opcodes.IFNE || j.getOpcode() == Opcodes.IFEQ)) return true;
                }
            }
            return false;
        }

        void call(ModFile home, String owner, String name, String desc, boolean virtual, int depth) {
            if (owner.startsWith("[")) return;   // array clone
            if (owner.equals("java/lang/reflect/Method") && name.equals("invoke") || owner.equals("java/lang/invoke/MethodHandle") && name.startsWith("invoke")) {
                notFollowed.add(owner + "." + name + " (reflection)");
                return;
            }
            if (owner.startsWith("org/lwjgl/opengl/")
                    || owner.equals("com/mojang/blaze3d/systems/RenderSystem") && !name.startsWith("get") && !name.startsWith("is") && !name.startsWith("assert")) {
                gl.add(owner + "." + name);
                return;
            }
            if (outside(owner)) return;
            boolean found = false;
            ModFile[] where = new ModFile[1];
            for (String c = owner; c != null && !outside(c); ) {
                ClassNode cn = Classes.find(home, c, where);
                if (cn == null) break;
                MethodNode mn = concrete(cn, name, desc);
                if (mn != null) {
                    walk(where[0], cn.name, mn, depth + 1);
                    found = true;
                    break;
                }
                c = cn.superName;
            }
            if (virtual)
                for (String impl : Classes.implementors(home, owner)) {
                    ClassNode cn = Classes.find(home, impl, where);
                    MethodNode mn = cn == null ? null : concrete(cn, name, desc);
                    if (mn != null) {
                        walk(where[0], cn.name, mn, depth + 1);
                        found = true;
                    }
                }
            if (!found) {
                ClassNode cn = Classes.find(home, owner, where);
                // an interface / abstract method with no implementation in the files met, or a class without bytes
                if (cn == null ? !Classes.absent(owner) : !inheritedOutside(home, cn, name, desc)) notFollowed.add(owner + "." + name);
            }
        }

        /** the method comes from vanilla / a library up the hierarchy (a mod class extending a vanilla one) */
        private static boolean inheritedOutside(ModFile home, ClassNode cn, String name, String desc) {
            ModFile[] where = new ModFile[1];
            for (ClassNode c = cn; c != null; ) {
                if (c.superName != null && outside(c.superName)) return true;
                for (String i : c.interfaces) if (outside(i)) return true;
                c = c.superName == null ? null : Classes.find(home, c.superName, where);
            }
            return false;
        }

        private static MethodNode concrete(ClassNode cn, String name, String desc) {
            for (MethodNode mn : cn.methods)
                if (mn.name.equals(name) && mn.desc.equals(desc) && (mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) return mn;
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** classes read from the mod files (raw bytes, nothing loaded), cached; implementors indexed per file met */
    static final class Classes {
        private static final Map<String, ClassNode> NODES = new HashMap<>();
        private static final Map<String, ModFile> FILE_OF = new HashMap<>();
        private static final Set<String> MISSING = new HashSet<>();
        private static final Map<ModFile, Map<String, Set<String>>> SUBS = new HashMap<>();
        private static List<ModFile> all;

        private static final Map<String, ModFile> PKG_FILE = new HashMap<>();
        private static final Set<String> PKG_MISSING = new java.util.HashSet<>();

        static ClassNode read(ModFile file, String internal) {
            return find(file, internal, new ModFile[1]);
        }

        /** the class from its home file first, then any mod file */
        static ClassNode find(ModFile home, String internal, ModFile[] where) {
            ClassNode cn = NODES.get(internal);
            if (cn != null) {
                where[0] = FILE_OF.get(internal);
                return cn;
            }
            if (MISSING.contains(internal) || outside(internal)) return null;
            ModFile f = home != null && has(home, internal) ? home : null;
            if (f == null) {
                // the file its package was found in first; a package no mod file has: the game's class loader (below) -
                // every mod file asked for every class cost 2.6 s on a 229-mod pack (absent optional dependencies)
                String pkg = internal.substring(0, Math.max(0, internal.lastIndexOf('/')));
                ModFile pf = PKG_FILE.get(pkg);
                if (pf != null && has(pf, internal)) f = pf;
                else if (!PKG_MISSING.contains(pkg)) {
                    if (all == null) {
                        all = new ArrayList<>();
                        var mods = net.minecraftforge.fml.loading.LoadingModList.get();
                        if (mods != null) for (var i : mods.getModFiles()) all.add(i.getFile());
                    }
                    for (ModFile m : all) if (has(m, internal)) { f = m; break; }   // (a split package: its other file)
                    if (f != null) PKG_FILE.putIfAbsent(pkg, f);
                    else if (pf == null) PKG_MISSING.add(pkg);
                }
            }
            if (f == null) {
                cn = resource(internal);   // a library jar-in-jar (not a mod file): the game layer has it
                if (cn == null) {
                    MISSING.add(internal);
                    return null;
                }
                NODES.put(internal, cn);
                FILE_OF.put(internal, home);
                where[0] = home;
                return cn;
            }
            long tr = System.nanoTime();
            readN++;
            try {
                cn = new ClassNode();
                new ClassReader(Files.readAllBytes(f.findResource(internal + ".class"))).accept(cn, ClassReader.SKIP_FRAMES);
                readNs += System.nanoTime() - tr;
            } catch (Throwable t) {
                MISSING.add(internal);
                return null;
            }
            NODES.put(internal, cn);
            FILE_OF.put(internal, f);
            where[0] = f;
            return cn;
        }

        static long hasN, hasNs, resN, resNs, readN, readNs;

        private static boolean has(ModFile f, String internal) {
            long t0 = System.nanoTime();
            hasN++;
            try {
                Path p = f.findResource(internal + ".class");
                return p != null && Files.exists(p);
            } catch (Throwable t) {
                return false;
            } finally {
                hasNs += System.nanoTime() - t0;
            }
        }

        private static final Map<String, Set<String>> DECLARED = new HashMap<>();

        /** does the vanilla class declare a method of this official name ("*..." / mod classes: yes, unchecked) */
        static boolean declares(String target, String name) {
            if (name.startsWith("*") || !outside(target)) return true;
            Set<String> names = DECLARED.computeIfAbsent(target, t -> {
                try {
                    ClassNode cn = resource(t);
                    if (cn == null) {
                        System.out.println("[sleep/opt] render field flow: " + t + " not read: its selectors all count");
                        return null;
                    }
                    Set<String> out = new HashSet<>();
                    for (MethodNode m : cn.methods) out.add(MixinTouch.official(m.name));
                    return out;
                } catch (Throwable e) {
                    return null;   // not read: every selector counts
                }
            });
            return names == null || names.contains(name);
        }

        /**
         * A class' bytes as the game's class loader holds them, untransformed (its resource; Mixin's ModLauncher service
         * gives no untransformed bytecode), or null.
         */
        static ClassNode resource(String internal) {
            long t0 = System.nanoTime();
            resN++;
            try (java.io.InputStream in = RenderFieldFlow.class.getClassLoader().getResourceAsStream(internal + ".class")) {
                if (in == null) return null;
                ClassNode cn = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_FRAMES);
                return cn;
            } catch (Throwable t) {
                return null;
            } finally {
                resNs += System.nanoTime() - t0;
            }
        }

        /** a class neither a mod file nor the game layer holds: calls to it never run (an optional dependency absent) */
        static boolean absent(String internal) {
            return MISSING.contains(internal);
        }

        static boolean isMixin(ModFile home, String internal) {
            ClassNode cn = read(home, internal);
            return cn != null && !MixinTouch.targets(cn).isEmpty();
        }

        /**
         * An instance field of an object one entity's draw owns: a vanilla per-object class's, or a mixin's into such a
         * class (its @Unique / @Shadow fields live in the target's instances).
         */
        static boolean perObjectField(ModFile home, String owner) {
            if (outside(owner)) return perObject(owner);
            ClassNode cn = read(home, owner);
            if (cn == null) return false;
            List<String> ts = MixinTouch.targets(cn);
            if (ts.isEmpty()) return false;
            for (String t : ts) if (!perObject(t)) return false;
            return true;
        }

        /** every class in the home file and the files met so far extending / implementing owner, transitively */
        static Set<String> implementors(ModFile home, String owner) {
            Set<String> out = new TreeSet<>();
            Set<ModFile> files = new HashSet<>(FILE_OF.values());
            if (home != null) files.add(home);
            // the files not indexed yet, in parallel (each on its own: the whole jar's class headers - 225 files, 57 k
            // classes, 2.8 s one after another on a 229-mod pack)
            List<ModFile> missing = new ArrayList<>();
            for (ModFile f : files) if (!SUBS.containsKey(f)) missing.add(f);
            if (missing.size() > 1)
                for (var e : missing.parallelStream().map(f -> Map.entry(f, index(f))).toList()) SUBS.put(e.getKey(), e.getValue());
            for (ModFile f : files) {
                Map<String, Set<String>> subs = SUBS.computeIfAbsent(f, Classes::index);
                List<String> q = new ArrayList<>(List.of(owner));
                while (!q.isEmpty()) {
                    String c = q.remove(q.size() - 1);
                    for (String s : subs.getOrDefault(c, Set.of())) if (out.add(s)) q.add(s);
                }
            }
            return out;
        }

        static volatile long idxN, idxNs, idxClasses;   // (stats; the parallel indexing may lose a count)

        private static Map<String, Set<String>> index(ModFile f) {
            long ti = System.nanoTime();
            idxN++;
            Map<String, Set<String>> subs = new HashMap<>();
            try {
                Path root = f.getSecureJar().getRootPath();
                try (var walk = Files.walk(root)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (!p.toString().endsWith(".class")) continue;
                        try {
                            ClassReader r = new ClassReader(Files.readAllBytes(p));
                            idxClasses++;
                            if (r.getSuperName() != null) subs.computeIfAbsent(r.getSuperName(), k -> new HashSet<>()).add(r.getClassName());
                            for (String i : r.getInterfaces()) subs.computeIfAbsent(i, k -> new HashSet<>()).add(r.getClassName());
                        } catch (Throwable t) { /* a broken class: skipped */ }
                    }
                }
            } catch (Throwable t) {
                System.out.println("[sleep/opt] render field flow: " + f.getFileName() + " not indexed: " + t);
            }
            idxNs += System.nanoTime() - ti;
            return subs;
        }
    }

    /** the log's summary for the report */
    public static String report() {
        StringBuilder b = new StringBuilder();
        for (Verdict v : BY_MOD.values()) b.append(v.mod).append(v.trusted ? " trusted; " : " refused; ");
        return b.toString();
    }

    static FieldNode field(ClassNode cn, String name) {
        for (FieldNode f : cn.fields) if (f.name.equals(name)) return f;
        return null;
    }
}
