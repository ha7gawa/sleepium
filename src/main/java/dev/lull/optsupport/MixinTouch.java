package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

/**
 * Which methods of a class other mods' mixins change, per method name. The bytecode analysers (sleep engine, shape
 * purity) read classes from their resources, i.e. before mixins; this tells them where those bytes are no longer the
 * code that runs, so they can refuse just those methods instead of the whole class.
 *
 * <p>A method name counts as touched by a mixin when the mixin
 * <ul>
 *   <li>injects into it: any method annotation with a {@code method} attribute (Inject, Redirect, ModifyArg(s),
 *       ModifyVariable, ModifyConstant, and MixinExtras' Wrap/Modify* injectors), a wildcard meaning every method;</li>
 *   <li>overwrites it (@Overwrite), or merges a method of that name (unannotated or @Unique: it may override an
 *       inherited one); @Shadow, @Accessor and @Invoker change no existing code and are ignored.</li>
 * </ul>
 * Mixins of this mod (dev.lull.*, and the framework's dev.probe.*) are ignored: they are exactness-checked. Pending mixins of not yet loaded classes
 * are included. Any failure to read a mixin makes every method of its target count as touched.
 */
public final class MixinTouch {
    private static final String ALL = "*";
    private static final Map<String, Set<String>> TOUCHED = new ConcurrentHashMap<>();
    private static final Field PENDING = pendingField();

    private MixinTouch() {}

    /**
     * A config of the engine or its framework (not indexed). The foreign test config (testmods.foreign: stand-ins for
     * other mods' mixins) counts as foreign when its mixins are applied, -Dsleep.foreignTest=true.
     */
    private static boolean ours(String config) {
        if (config.startsWith("sleepium-foreign-test")) return !Boolean.getBoolean("sleep.foreignTest");
        return config.startsWith("sleepium") || config.startsWith("probe");
    }

    /** Is method {@code name} of class {@code internalName} changed by some foreign mixin? */
    public static boolean touched(String internalName, String name) {
        return touched(internalName, name, null);
    }

    /** The same for one method ({@code desc}; null: every method of that name). */
    public static boolean touched(String internalName, String name, String desc) {
        Set<String> t = TOUCHED.computeIfAbsent(internalName, MixinTouch::compute);
        if (t.contains(ALL) || t.contains(official(name))) return true;
        for (String w : t)
            if (w.startsWith(WILD)) return WILD_TOUCHED.computeIfAbsent(internalName + "." + official(name) + (desc == null ? "" : desc), k -> wildTouches(internalName, official(name), desc, t));
        return false;
    }

    /**
     * A wildcard injector ({@code method = "*"}) at a field access or call ({@code @At(value = "FIELD" / "INVOKE", target
     * = ...)}) changes only the methods with that instruction: "*@" + the member's name. ModernFix's lazy block-state
     * cache redirects every read of BlockStateBase.cache; methods that never read it are not changed.
     */
    private static final String WILD = "*@";
    private static final Map<String, Boolean> WILD_TOUCHED = new ConcurrentHashMap<>();

    private static boolean wildTouches(String internalName, String name, String desc, Set<String> touched) {
        ClassNode cn;
        try {
            cn = dev.lull.ClassBytes.readBefore(MixinTouch.class.getClassLoader(), internalName, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        } catch (Throwable e) {
            cn = null;
        }
        if (cn == null) return true;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals(name) || desc != null && !m.desc.equals(desc)) continue;
            for (org.objectweb.asm.tree.AbstractInsnNode in : m.instructions) {
                String member = in instanceof org.objectweb.asm.tree.FieldInsnNode f ? f.name : in instanceof org.objectweb.asm.tree.MethodInsnNode mi ? mi.name : null;
                if (member != null && touched.contains(WILD + member)) return true;
            }
        }
        return false;
    }

    /** Any foreign mixin at all on this class (for callers that cannot work per method). */
    public static boolean anyForeign(String internalName) {
        return !TOUCHED.computeIfAbsent(internalName, MixinTouch::compute).isEmpty();
    }

    private static volatile Map<String, Set<String>> INDEX;
    /** foreign @Overwrites by target class: the method names (filled with the index) */
    private static final Map<String, Set<String>> OVERWRITES = new java.util.HashMap<>();

    /** Is method {@code name} of this class overwritten by a foreign mixin (no injector of ours can go into it)? */
    public static boolean overwritten(String internalName, String name) {
        index();
        Set<String> o = OVERWRITES.get(internalName);
        return o != null && o.contains(official(name));
    }

    /** the names of a mixin class's @Overwrite methods */
    static List<String> overwriteNames(ClassNode cn) {
        List<String> out = new java.util.ArrayList<>();
        for (MethodNode m : cn.methods)
            for (AnnotationNode a : annotations(m)) if ("Lorg/spongepowered/asm/mixin/Overwrite;".equals(a.desc)) out.add(official(m.name));
        return out;
    }

    /** the target method names of a mixin method's injector annotations (empty: not an injector) */
    static List<String> injectorTargets(MethodNode m) {
        List<String> out = new java.util.ArrayList<>();
        for (AnnotationNode a : annotations(m)) {
            String d = a.desc;
            boolean injector = d.startsWith("Lorg/spongepowered/asm/mixin/injection/") && !d.endsWith("/At;") && !d.endsWith("/Slice;")
                    || d.startsWith("Lcom/llamalad7/mixinextras/injector/");
            if (!injector || a.values == null) continue;
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                if (!"method".equals(a.values.get(i))) continue;
                Object v = a.values.get(i + 1);
                if (v instanceof List<?> l) for (Object o : l) out.add(nameOf(String.valueOf(o)));
                else out.add(nameOf(String.valueOf(v)));
            }
        }
        return out;
    }

    /** foreign @Redirects by target class: "method|invoked name" (filled with the index) */
    private static final Map<String, Set<String>> REDIRECTS = new java.util.HashMap<>();
    /** foreign @ModifyArg / @ModifyArgs by target class: "method|invoked name" (filled with the index) */
    private static final Map<String, Set<String>> ARGMODS = new java.util.HashMap<>();

    /** Does a foreign mixin @Redirect the call to {@code invoked} inside {@code method} of this class? */
    public static boolean redirected(String internalName, String method, String invoked) {
        index();
        Set<String> r = REDIRECTS.get(internalName);
        method = official(method);
        invoked = official(invoked);
        return r != null && (r.contains(method + "|" + invoked) || r.contains(ALL + "|" + invoked));
    }

    /**
     * Does a foreign mixin change the arguments of the call to {@code invoked} inside {@code method} of this class
     * (@ModifyArg / @ModifyArgs)? Next to a @Redirect of the same call one of them fails: after the redirect the call is
     * the handler's, the receiver first, and the argument indices point elsewhere.
     */
    public static boolean argsModified(String internalName, String method, String invoked) {
        index();
        Set<String> r = ARGMODS.get(internalName);
        method = official(method);
        invoked = official(invoked);
        return r != null && (r.contains(method + "|" + invoked) || r.contains(ALL + "|" + invoked));
    }

    private static final Set<String> REDIRECT = Set.of("Lorg/spongepowered/asm/mixin/injection/Redirect;"),
            ARG_MODIFIERS = Set.of("Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;");

    /** "method|invoked name" of every @Redirect of a mixin class (the invoked name from its @At target). */
    static List<String> redirectKeys(ClassNode cn) {
        return callKeys(cn, REDIRECT);
    }

    /** "method|invoked name" of every @ModifyArg / @ModifyArgs of a mixin class. */
    static List<String> argKeys(ClassNode cn) {
        return callKeys(cn, ARG_MODIFIERS);
    }

    private static List<String> callKeys(ClassNode cn, Set<String> kinds) {
        List<String> out = new java.util.ArrayList<>();
        for (MethodNode m : cn.methods)
            for (AnnotationNode a : annotations(m)) {
                if (!kinds.contains(a.desc) || a.values == null) continue;
                List<String> methods = new java.util.ArrayList<>();
                String invoked = null;
                for (int i = 0; i + 1 < a.values.size(); i += 2) {
                    Object v = a.values.get(i + 1);
                    if ("method".equals(a.values.get(i))) {
                        if (v instanceof List<?> l) for (Object o : l) methods.add(nameOf(String.valueOf(o)));
                        else methods.add(nameOf(String.valueOf(v)));
                    } else if ("at".equals(a.values.get(i)) && v instanceof AnnotationNode at && at.values != null) {
                        for (int k = 0; k + 1 < at.values.size(); k += 2)
                            if ("target".equals(at.values.get(k))) invoked = invokedName(String.valueOf(at.values.get(k + 1)));
                    }
                }
                if (invoked != null) for (String mm : methods) out.add(mm + "|" + invoked);
            }
        return out;
    }

    /** "Lowner;name(desc)ret" or "owner.name(...)" -> name */
    static String invokedName(String target) {
        String s = ref(target).trim();
        int paren = s.indexOf('(');
        if (paren >= 0) s = s.substring(0, paren);
        int colon = s.indexOf(':');   // a field: "Lowner;name:Ltype;"
        if (colon >= 0) s = s.substring(0, colon);
        int cut = Math.max(s.lastIndexOf(';'), s.lastIndexOf('.'));
        return official(cut >= 0 ? s.substring(cut + 1) : s);
    }

    /**
     * Every foreign mixin of every registered config, by target class, read from the configs' class lists and the
     * mixins' bytecode: complete even while Mixin has not prepared the other mods' configs yet (ours are prepared first,
     * and the plugins decide then).
     */
    private static synchronized Map<String, Set<String>> index() {
        Map<String, Set<String>> idx = INDEX;
        if (idx != null) return idx;
        Map<String, Set<String>> m = new java.util.HashMap<>();
        indexModFiles(m);
        try {
            var provider = org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider();
            for (org.spongepowered.asm.mixin.transformer.Config c : org.spongepowered.asm.mixin.Mixins.getConfigs()) {
                org.spongepowered.asm.mixin.extensibility.IMixinConfig mc = c.getConfig();
                if (mc == null) continue;
                if (ours(mc.getName())) continue;   // (the engine's and its framework's)
                String pkg = mc.getMixinPackage();
                if (pkg == null) continue;
                if (!pkg.isEmpty() && !pkg.endsWith(".")) pkg += ".";
                for (String field : new String[] {"mixinClasses", "mixinClassesClient", "mixinClassesServer"}) {
                    Field f = mc.getClass().getDeclaredField(field);
                    f.setAccessible(true);
                    Object list = f.get(mc);
                    if (!(list instanceof List<?> names)) continue;
                    for (Object n : names) {
                        String cls = pkg + n;
                        if (cls.startsWith("dev.lull.") || cls.startsWith("dev.probe.")) continue;
                        ClassNode cn;
                        try {
                            cn = provider.getClassNode(cls);
                        } catch (Throwable t) {
                            continue;
                        }
                        Set<String> methods = new HashSet<>();
                        List<String> reds, args, ows;
                        REFMAP.set(refMapper(mc, cn.name));
                        try {
                            collect(cn, methods);
                            reds = redirectKeys(cn);
                            args = argKeys(cn);
                            ows = overwriteNames(cn);
                        } finally {
                            REFMAP.remove();
                        }
                        for (String target : targets(cn)) {
                            m.computeIfAbsent(target, k -> new HashSet<>()).addAll(methods);
                            if (!reds.isEmpty()) REDIRECTS.computeIfAbsent(target, k -> new HashSet<>()).addAll(reds);
                            if (!args.isEmpty()) ARGMODS.computeIfAbsent(target, k -> new HashSet<>()).addAll(args);
                            if (!ows.isEmpty()) OVERWRITES.computeIfAbsent(target, k -> new HashSet<>()).addAll(ows);
                            noteWho(target, CONFIG_MOD.getOrDefault(mc.getName(), mc.getName()), cn.name, methods, reds, args, ows);
                            MIXIN_CONFIG.putIfAbsent(cls, mc.getName());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[sleep/opt] foreign mixin index incomplete: " + t);
        }
        INDEX = m;
        System.out.println("[sleep/opt] foreign mixin index: " + m.size() + " target classes; Block: " + m.get("net/minecraft/world/level/block/Block"));
        return m;
    }

    /**
     * Every mod file's mixin configs as FML lists them (jar-in-jar mods included), read from the files themselves: known
     * before Mixin registers or prepares any of them.
     */
    private static void indexModFiles(Map<String, Set<String>> m) {
        try {
            var mods = net.minecraftforge.fml.loading.LoadingModList.get();
            if (mods == null) return;
            for (var info : mods.getModFiles()) {
                var file = info.getFile();
                String modId = info.getMods().isEmpty() ? info.moduleName() : info.getMods().get(0).getModId();
                for (String name : mixinConfigs(file)) {
                    CONFIG_MOD.put(name, modId);
                    if (Boolean.getBoolean("sleep.mixinIndex.debug")) System.out.println("[sleep/opt] mixin index: " + info.moduleName() + " " + name + " -> " + file.findResource(name));
                    if (ours(name)) continue;
                    try {
                        java.nio.file.Path json = file.findResource(name);
                        if (json == null || !java.nio.file.Files.exists(json)) continue;
                        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(json)).getAsJsonObject();
                        if (!o.has("package") || !o.get("package").isJsonPrimitive()) continue;
                        String pkg = o.get("package").getAsString();
                        if (pkg.startsWith("dev.lull") || pkg.startsWith("dev.probe")) continue;
                        com.google.gson.JsonObject refmap = refMapFile(file, o);
                        for (String key : new String[] {"mixins", "client", "server"}) {
                            if (!o.has(key) || !o.get(key).isJsonArray()) continue;
                            for (var e : o.getAsJsonArray(key)) {
                                if (!e.isJsonPrimitive()) continue;   // Mixin skips a null entry (Sleep Tight's list ends in one)
                                java.nio.file.Path bytes = file.findResource((pkg + "." + e.getAsString()).replace('.', '/') + ".class");
                                if (bytes != null && java.nio.file.Files.exists(bytes)) {
                                    indexClass(java.nio.file.Files.readAllBytes(bytes), m, refmap, modId);
                                    MIXIN_CONFIG.putIfAbsent(pkg + "." + e.getAsString(), name);
                                }
                            }
                        }
                        // a config plugin may add mixins of its package the lists do not name (its getMixins: Embeddium's
                        // lists are empty): every @Mixin class under the package counts (one the plugin then declines too)
                        if (o.has("plugin")) {
                            java.nio.file.Path root = file.findResource(pkg.replace('.', '/'));
                            if (root != null && java.nio.file.Files.isDirectory(root))
                                try (var walk = java.nio.file.Files.walk(root)) {
                                    for (java.nio.file.Path c : (Iterable<java.nio.file.Path>) walk::iterator)
                                        if (c.toString().endsWith(".class")) indexClass(java.nio.file.Files.readAllBytes(c), m, refmap, modId);
                                }
                        }
                    } catch (Throwable t) {   // one mod's config the index cannot read: the other mods' still counted
                        System.out.println("[sleep/opt] foreign mixin index: " + name + " not read: " + t);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[sleep/opt] foreign mixin index (mod files) incomplete: " + t);
        }
    }

    /** Every mixin class of this mod's configs (sleepium*.mixins.json), read from the mod file (no code). */
    static void ourMixins(java.util.function.BiConsumer<String, ClassNode> each) {
        try {
            var mods = net.minecraftforge.fml.loading.LoadingModList.get();
            if (mods == null) return;
            for (var info : mods.getModFiles()) {
                var file = info.getFile();
                for (String name : ourConfigs(file)) {
                    if (!name.startsWith("sleepium")) continue;
                    java.nio.file.Path json = file.findResource(name);
                    if (json == null || !java.nio.file.Files.exists(json)) continue;
                    com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(json)).getAsJsonObject();
                    String pkg = o.get("package").getAsString();
                    for (String key : new String[] {"mixins", "client", "server"}) {
                        if (!o.has(key) || !o.get(key).isJsonArray()) continue;
                        for (var e : o.getAsJsonArray(key)) {
                            String cls = pkg + "." + e.getAsString();
                            java.nio.file.Path bytes = file.findResource(cls.replace('.', '/') + ".class");
                            if (bytes == null || !java.nio.file.Files.exists(bytes)) continue;
                            ClassNode cn = new ClassNode();
                            new org.objectweb.asm.ClassReader(java.nio.file.Files.readAllBytes(bytes)).accept(cn, org.objectweb.asm.ClassReader.SKIP_CODE);
                            each.accept(cls, cn);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[sleep/opt] own mixin list incomplete: " + t);
        }
    }

    /**
     * Our configs in a mod file: the manifest's list, or - a dev run's mod is the build's folders, no manifest; Mixin
     * gets the configs from the run's arguments - the sleepium*.mixins.json files at its root.
     */
    private static java.util.List<String> ourConfigs(net.minecraftforge.fml.loading.moddiscovery.ModFile file) {
        java.util.List<String> out = mixinConfigs(file);
        if (!out.isEmpty()) return out;
        try (var list = java.nio.file.Files.list(file.getSecureJar().getRootPath())) {
            list.map(p -> String.valueOf(p.getFileName())).filter(n -> n.startsWith("sleepium") && n.endsWith(".mixins.json")).sorted().forEach(out::add);
        } catch (java.io.IOException | RuntimeException e) {
            // not a folder or jar we can list: no configs of ours there
        }
        return out;
    }

    /**
     * A mod file's mixin configs. Forge 1.20.1 has no list of them in its mod file model (NeoForge's
     * IModFile.getMixinConfigs, from neoforge.mods.toml): Mixin reads them from the jar manifest's MixinConfigs
     * attribute (comma-separated), so this does too.
     */
    static java.util.List<String> mixinConfigs(net.minecraftforge.fml.loading.moddiscovery.ModFile file) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            java.nio.file.Path mf = file.findResource("META-INF/MANIFEST.MF");
            if (mf == null || !java.nio.file.Files.exists(mf)) return out;
            java.util.jar.Manifest man;
            try (var in = java.nio.file.Files.newInputStream(mf)) {
                man = new java.util.jar.Manifest(in);
            }
            String v = man.getMainAttributes().getValue("MixinConfigs");
            if (v == null) return out;
            for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
        } catch (java.io.IOException | RuntimeException e) {
            System.out.println("[sleep/opt] mixin configs of " + file.getFileName() + " not read: " + e);
        }
        return out;
    }

    /** a foreign mixin config as its mod file holds it (RenderFieldFlow) */
    record ForeignConfig(net.minecraftforge.fml.loading.moddiscovery.ModFile file, String mod, String name, String pkg, List<String> mixins,
                         com.google.gson.JsonObject refmap) {}

    /** every foreign mixin config of every mod file (jar-in-jar mods included), read from the files; mixins: class names (dots) */
    static List<ForeignConfig> foreignConfigs() {
        List<ForeignConfig> out = new java.util.ArrayList<>();
        try {
            var mods = net.minecraftforge.fml.loading.LoadingModList.get();
            if (mods == null) return out;
            for (var info : mods.getModFiles()) {
                var file = info.getFile();
                for (String name : mixinConfigs(file)) {
                    if (ours(name)) continue;
                    try {
                        java.nio.file.Path json = file.findResource(name);
                        if (json == null || !java.nio.file.Files.exists(json)) continue;
                        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(json)).getAsJsonObject();
                        if (!o.has("package") || !o.get("package").isJsonPrimitive()) continue;
                        String pkg = o.get("package").getAsString();
                        if (pkg.startsWith("dev.lull") || pkg.startsWith("dev.probe")) continue;
                        List<String> mixins = new java.util.ArrayList<>();
                        for (String key : new String[] {"mixins", "client"})
                            if (o.has(key) && o.get(key).isJsonArray())
                                for (var e : o.getAsJsonArray(key)) if (e.isJsonPrimitive()) mixins.add(pkg + "." + e.getAsString());
                        out.add(new ForeignConfig(file, info.moduleName(), name, pkg, mixins, refMapFile(file, o)));
                    } catch (Throwable t) {
                        System.out.println("[sleep/opt] render field flow: " + name + " not read: " + t);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[sleep/opt] render field flow: mod files not read: " + t);
        }
        return out;
    }

    /** a mixin method's injector targets (official names; ALL when unclear; empty: not an injector), read through the refmap */
    static List<String> handlerTargets(com.google.gson.JsonObject refmap, String mixinInternalName, MethodNode m) {
        REFMAP.set(refMapEntries(refmap, mixinInternalName));
        try {
            List<String> out = injectorTargets(m);
            for (AnnotationNode a : annotations(m)) if ("Lorg/spongepowered/asm/mixin/Overwrite;".equals(a.desc)) out.add(official(m.name));
            return out;
        } finally {
            REFMAP.remove();
        }
    }

    /** One foreign mixin class's touches, by its target classes (a class without @Mixin adds nothing). */
    private static void indexClass(byte[] bytes, Map<String, Set<String>> m, com.google.gson.JsonObject refmap, String owner) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(cn, org.objectweb.asm.ClassReader.SKIP_CODE);
        List<String> ts = targets(cn);
        if (ts.isEmpty()) return;
        Set<String> methods = new HashSet<>();
        List<String> reds, args, ows;
        REFMAP.set(refMapEntries(refmap, cn.name));
        try {
            collect(cn, methods);
            reds = redirectKeys(cn);
            args = argKeys(cn);
            ows = overwriteNames(cn);
        } finally {
            REFMAP.remove();
        }
        for (String target : ts) {
            m.computeIfAbsent(target, k -> new HashSet<>()).addAll(methods);
            if (!reds.isEmpty()) REDIRECTS.computeIfAbsent(target, k -> new HashSet<>()).addAll(reds);
            if (!args.isEmpty()) ARGMODS.computeIfAbsent(target, k -> new HashSet<>()).addAll(args);
            if (!ows.isEmpty()) OVERWRITES.computeIfAbsent(target, k -> new HashSet<>()).addAll(ows);
            noteWho(target, owner, cn.name, methods, reds, args, ows);
        }
    }

    /** who touches a method: "target#method" (official names; "target#*" a wildcard) -> "mod (Mixin)" */
    private static final Map<String, Set<String>> WHO = new java.util.HashMap<>();
    /** the same keys -> "modid TAB mixin class (dots)": the rules' view (which mod, which mixin to cancel) */
    private static final Map<String, Set<String>> WHO_MIX = new java.util.HashMap<>();
    /** a mixin config's name -> the mod id of its file; a foreign mixin class (dots) -> its config's name */
    private static final Map<String, String> CONFIG_MOD = new java.util.HashMap<>(), MIXIN_CONFIG = new java.util.HashMap<>();

    private static void noteWho(String target, String owner, String mixin, Set<String> methods, List<String> reds, List<String> args, List<String> ows) {
        String label = (owner == null || owner.isEmpty() ? "?" : owner) + " (" + mixin.substring(mixin.lastIndexOf('/') + 1) + ")";
        String culprit = (owner == null || owner.isEmpty() ? "?" : owner) + "\t" + mixin.replace('/', '.');
        java.util.function.Consumer<String> add = n -> {
            String key = target + "#" + (n.startsWith("*") ? "*" : n);
            WHO.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(label);
            WHO_MIX.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(culprit);
        };
        methods.forEach(add);
        ows.forEach(add);
        for (String k : reds) {
            add.accept(k.substring(0, k.indexOf('|')));
            WHO.computeIfAbsent(target + "#" + k, x -> new java.util.LinkedHashSet<>()).add(label);   // the call itself: "method|invoked"
            WHO_MIX.computeIfAbsent(target + "#" + k, x -> new java.util.LinkedHashSet<>()).add(culprit);
        }
        for (String k : args) {
            add.accept(k.substring(0, k.indexOf('|')));
            WHO.computeIfAbsent(target + "#" + k, x -> new java.util.LinkedHashSet<>()).add(label);
            WHO_MIX.computeIfAbsent(target + "#" + k, x -> new java.util.LinkedHashSet<>()).add(culprit);
        }
    }

    /** the foreign mixins on this method (or the class by a wildcard): "modid TAB mixin class" each */
    static Set<String> culprits(String internalName, String name) {
        index();
        Set<String> out = new java.util.LinkedHashSet<>();
        Set<String> a = WHO_MIX.get(internalName + "#" + name), b = WHO_MIX.get(internalName + "#*");
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out;
    }

    /** every foreign mixin on any method of this class */
    static Set<String> culpritsOnClass(String internalName) {
        index();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (var e : WHO_MIX.entrySet()) if (e.getKey().startsWith(internalName + "#")) out.addAll(e.getValue());
        return out;
    }

    /** the foreign mixins on this call (their @Redirect / @ModifyArg(s)), else on the method */
    static Set<String> culpritsCall(String internalName, String method, String invoked) {
        index();
        Set<String> out = new java.util.LinkedHashSet<>();
        Set<String> a = WHO_MIX.get(internalName + "#" + method + "|" + invoked), b = WHO_MIX.get(internalName + "#*|" + invoked);
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out.isEmpty() ? culprits(internalName, method) : out;
    }

    /**
     * Takes a foreign mixin out of its config before Mixin prepares that config (a pack's rule: our change wins there).
     * Ours are prepared first; a config already prepared cannot lose a mixin any more: false then (the caller gives way).
     */
    static synchronized boolean cancel(String mixinClass, boolean dryRun) {
        index();
        String config = MIXIN_CONFIG.get(mixinClass);
        if (config == null) return false;
        try {
            for (org.spongepowered.asm.mixin.transformer.Config c : org.spongepowered.asm.mixin.Mixins.getConfigs()) {
                org.spongepowered.asm.mixin.extensibility.IMixinConfig mc = c.getConfig();
                if (mc == null || !config.equals(mc.getName())) continue;
                try {
                    Field prepared = mc.getClass().getDeclaredField("prepared");
                    prepared.setAccessible(true);
                    if (prepared.getBoolean(mc)) return false;
                } catch (NoSuchFieldException e) {
                    // (a Mixin without the flag: try the lists)
                }
                String pkg = mc.getMixinPackage();
                if (pkg == null) return false;
                if (!pkg.isEmpty() && !pkg.endsWith(".")) pkg += ".";
                if (!mixinClass.startsWith(pkg)) return false;
                String rel = mixinClass.substring(pkg.length());
                if (dryRun) return true;
                boolean removed = false;
                for (String field : new String[] {"mixinClasses", "mixinClassesClient", "mixinClassesServer"}) {
                    Field f = mc.getClass().getDeclaredField(field);
                    f.setAccessible(true);
                    Object list = f.get(mc);
                    if (list instanceof List<?> names) {
                        try {
                            removed |= names.remove(rel);
                        } catch (UnsupportedOperationException e) {   // an unmodifiable list: a copy without it
                            List<Object> copy = new java.util.ArrayList<>(names);
                            removed |= copy.remove(rel);
                            f.set(mc, copy);
                        }
                    }
                }
                return removed;
            }
        } catch (Throwable t) {
            System.out.println("[sleep/opt] could not cancel " + mixinClass + ": " + t);
        }
        return false;
    }

    /** the mods whose @Redirect / @ModifyArg(s) target this call in this method ("*" methods included) */
    public static String whoCall(String internalName, String method, String invoked) {
        index();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        Set<String> a = WHO.get(internalName + "#" + method + "|" + invoked), b = WHO.get(internalName + "#*|" + invoked);
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out.isEmpty() ? who(internalName, method) : String.join(", ", out);
    }

    /** the mods whose mixins touch this method (or the whole class by a wildcard): "sodium (LevelRendererMixin), ..." */
    public static String who(String internalName, String name) {
        index();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        Set<String> a = WHO.get(internalName + "#" + official(name)), b = WHO.get(internalName + "#*");
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        if (out.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : out) {
            if (n++ == 4) {
                sb.append(", +").append(out.size() - 4);
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** The target classes (internal names) of a mixin class: @Mixin's value and targets. */
    static List<String> targets(ClassNode cn) {
        List<String> out = new java.util.ArrayList<>();
        List<AnnotationNode> as = new java.util.ArrayList<>();
        if (cn.visibleAnnotations != null) as.addAll(cn.visibleAnnotations);
        if (cn.invisibleAnnotations != null) as.addAll(cn.invisibleAnnotations);
        for (AnnotationNode a : as) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc) || a.values == null) continue;
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                Object v = a.values.get(i + 1);
                if (!(v instanceof List<?> l)) continue;
                for (Object o : l) {
                    if (o instanceof org.objectweb.asm.Type t) out.add(t.getInternalName());
                    else if (o instanceof String str) out.add(str.replace('.', '/'));
                }
            }
        }
        return out;
    }

    private static Set<String> compute(String internalName) {
        Set<String> out = new HashSet<>();
        Set<String> indexed = index().get(internalName);
        if (indexed != null) out.addAll(indexed);
        try {
            ClassInfo info = ClassInfo.fromCache(internalName);
            if (info == null) return out.isEmpty() ? Set.of() : Set.copyOf(out);   // not in Mixin's cache yet: the index alone
            Set<IMixinInfo> mixins = new HashSet<>(info.getAppliedMixins());
            if (PENDING != null) {
                Object p = PENDING.get(info);
                if (p instanceof Collection<?> c) for (Object o : c) if (o instanceof IMixinInfo mi) mixins.add(mi);
            }
            for (IMixinInfo mi : mixins) {
                if (mi.getClassName().startsWith("dev.lull.") || mi.getClassName().startsWith("dev.probe.")) continue;   // (the engine's and its framework's)
                REFMAP.set(refMapper(mi.getConfig(), mi.getClassRef()));
                try {
                    collect(mi, out);
                } finally {
                    REFMAP.remove();
                }
            }
        } catch (Throwable t) {
            out.add(ALL);
        }
        return out.isEmpty() ? Set.of() : Set.copyOf(out);
    }

    private static void collect(IMixinInfo mi, Set<String> out) {
        ClassNode cn;
        try {
            cn = mi.getClassNode(0);
        } catch (Throwable t) {
            cn = null;
        }
        if (cn == null) {
            out.add(ALL);
            return;
        }
        collect(cn, out);
    }

    static void collect(ClassNode cn, Set<String> out) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals("<clinit>")) continue;
            boolean injector = false, passive = false;
            for (AnnotationNode a : annotations(m)) {
                String d = a.desc;
                if (d.endsWith("/Shadow;") || d.endsWith("/Accessor;") || d.endsWith("/Invoker;")) passive = true;
                if (d.endsWith("/Overwrite;")) out.add(official(m.name));
                boolean injectorType = d.startsWith("Lorg/spongepowered/asm/mixin/injection/") && !d.endsWith("/At;") && !d.endsWith("/Slice;")
                        || d.startsWith("Lcom/llamalad7/mixinextras/injector/");
                boolean named = false;
                List<Object> v = a.values;
                if (v != null) {
                    for (int i = 0; i + 1 < v.size(); i += 2) {
                        if (!"method".equals(v.get(i))) continue;
                        named = injector = true;
                        Object targets = v.get(i + 1);
                        String wild = wildAt(a);
                        if (targets instanceof List<?> l) for (Object s : l) out.add(isWild(String.valueOf(s)) && wild != null ? wild : nameOf(String.valueOf(s)));
                        else out.add(isWild(String.valueOf(targets)) && wild != null ? wild : nameOf(String.valueOf(targets)));
                    }
                }
                if (injectorType && !named) {   // targets given another way (@Desc): cannot tell which method
                    injector = true;
                    out.add(ALL);
                }
            }
            if (!injector && !passive && !m.name.startsWith("lambda$")) out.add(official(m.name));
        }
    }

    /**
     * The reference map of the mixin being read: its annotations' strings as the refmap maps them (null: as written).
     * Mods written against other names than Mojang's - Yarn / intermediary in the ports of Fabric mods (Radium:
     * method = "forEachInBox" for EntitySectionStorage.forEachAccessibleNonEmptySection) - only name their targets
     * through the refmap; read as written, such a mixin touched no method we know and our overwrite of it did not give way.
     */
    private static final ThreadLocal<java.util.function.UnaryOperator<String>> REFMAP = new ThreadLocal<>();

    private static String ref(String annotationValue) {
        java.util.function.UnaryOperator<String> r = REFMAP.get();
        if (r == null) return annotationValue;
        String mapped = r.apply(annotationValue);
        return mapped == null || mapped.isEmpty() ? annotationValue : mapped;
    }

    /** a registered config's reference mapper (Mixin's own: in a dev run already remapped to Mojang's names) */
    private static java.util.function.UnaryOperator<String> refMapper(Object config, String mixinClass) {
        try {
            java.lang.reflect.Method get = config.getClass().getMethod("getReferenceMapper");
            get.setAccessible(true);   // MixinConfig is package-private
            Object mapper = get.invoke(config);
            if (!(mapper instanceof org.spongepowered.asm.mixin.refmap.IReferenceMapper rm) || rm.isDefault()) return null;
            String owner = mixinClass.replace('.', '/');
            return v -> rm.remap(owner, v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** a config's refmap file from its mod file ("refmap" in the config), or null */
    private static com.google.gson.JsonObject refMapFile(net.minecraftforge.fml.loading.moddiscovery.ModFile file, com.google.gson.JsonObject config) {
        try {
            if (!config.has("refmap")) return null;
            java.nio.file.Path p = file.findResource(config.get("refmap").getAsString());
            if (p == null || !java.nio.file.Files.exists(p)) return null;
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(p)).getAsJsonObject();
            // Forge's names when the file has several environments ("data": {"searge": ...}), else the default table
            if (o.has("data") && o.getAsJsonObject("data").has("searge")) return o.getAsJsonObject("data").getAsJsonObject("searge");
            return o.has("mappings") ? o.getAsJsonObject("mappings") : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** one mixin class's entries of a refmap file (values in SRG names: official() turns them into Mojang's) */
    private static java.util.function.UnaryOperator<String> refMapEntries(com.google.gson.JsonObject refmap, String mixinInternalName) {
        if (refmap == null || !refmap.has(mixinInternalName) || !refmap.get(mixinInternalName).isJsonObject()) return null;
        com.google.gson.JsonObject e = refmap.getAsJsonObject(mixinInternalName);
        return v -> e.has(v) && e.get(v).isJsonPrimitive() ? e.get(v).getAsString() : null;
    }

    /** "name", "name(desc)ret", "Lowner;name(desc)ret" or a pattern -> the method name, or ALL if unclear. */
    private static boolean isWild(String target) {
        return ref(target).trim().equals("*");
    }

    /** an injector's one @At at a field access or a call: "*@" + that member's name; null otherwise */
    private static String wildAt(AnnotationNode a) {
        Object at = null;
        for (int i = 0; a.values != null && i + 1 < a.values.size(); i += 2) if ("at".equals(a.values.get(i))) at = a.values.get(i + 1);
        if (at instanceof List<?> l) at = l.size() == 1 ? l.get(0) : null;
        if (!(at instanceof AnnotationNode an) || an.values == null) return null;
        String value = null, target = null;
        for (int i = 0; i + 1 < an.values.size(); i += 2) {
            if ("value".equals(an.values.get(i))) value = String.valueOf(an.values.get(i + 1));
            if ("target".equals(an.values.get(i))) target = String.valueOf(an.values.get(i + 1));
        }
        if (target == null || !("FIELD".equals(value) || "INVOKE".equals(value))) return null;
        String n = nameOf(target);
        return n.equals(ALL) ? null : WILD + n;
    }

    static String nameOf(String target) {
        String s = ref(target).trim();
        int semi = s.indexOf(';'), paren = s.indexOf('(');
        if (s.startsWith("L") && semi > 0 && (paren < 0 || semi < paren)) s = s.substring(semi + 1);
        int end = s.length();
        for (char c : new char[] {'(', ':'}) {
            int k = s.indexOf(c);
            if (k >= 0 && k < end) end = k;
        }
        s = s.substring(0, end);
        return s.isEmpty() || s.contains("*") || s.contains("/") || s.contains(" ") ? ALL : official(s);
    }

    /**
     * 1.20.1: every method name is kept and compared as Mojang's (1.21: one namespace). In production the names meet in
     * two namespaces: injector targets and @At targets are read as written (Mojang's, the refmap maps them at apply
     * time; some mods write SRG), while @Overwrite and merged methods of reobfuscated mixins (other mods' and ours)
     * carry SRG names, and callers pass Mojang constants or names read from running (SRG) bytecode.
     */
    static String official(String name) {
        return dev.lull.Names.official(name);
    }

    private static List<AnnotationNode> annotations(MethodNode m) {
        List<AnnotationNode> all = new java.util.ArrayList<>();
        if (m.visibleAnnotations != null) all.addAll(m.visibleAnnotations);
        if (m.invisibleAnnotations != null) all.addAll(m.invisibleAnnotations);
        return all;
    }

    private static Field pendingField() {
        try {
            Field f = ClassInfo.class.getDeclaredField("mixins");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The mixin configs of other mods whose mixins change method {@code name} of {@code internalName} (applied or
     * pending), for the log: which mods are in a method. Only the index's (a mod's jar not applied yet): "index".
     */
    public static Set<String> touchers(String internalName, String name) {
        Set<String> out = new java.util.TreeSet<>();
        try {
            ClassInfo info = ClassInfo.fromCache(internalName);
            if (info != null) {
                Set<IMixinInfo> mixins = new HashSet<>(info.getAppliedMixins());
                if (PENDING != null && PENDING.get(info) instanceof Collection<?> c) for (Object o : c) if (o instanceof IMixinInfo mi) mixins.add(mi);
                for (IMixinInfo mi : mixins) {
                    if (mi.getClassName().startsWith("dev.lull.") || mi.getClassName().startsWith("dev.probe.")) continue;
                    Set<String> one = new HashSet<>();
                    REFMAP.set(refMapper(mi.getConfig(), mi.getClassRef()));
                    try {
                        collect(mi, one);
                    } finally {
                        REFMAP.remove();
                    }
                    boolean wild = false;
                    for (String w : one) if (w.startsWith(WILD)) wild = true;
                    if (one.contains(ALL) || one.contains(official(name)) || wild && wildTouches(internalName, official(name), null, one))
                        out.add(mi.getConfig().getName());
                }
            }
        } catch (Throwable t) {
            out.add("?");
        }
        if (out.isEmpty() && touched(internalName, name)) out.add("index");
        return out;
    }

    /** Diagnostics: the touched method names of a class. */
    public static Set<String> touchedMethods(String internalName) {
        return TOUCHED.computeIfAbsent(internalName, MixinTouch::compute);
    }
}
