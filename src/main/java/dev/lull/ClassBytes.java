package dev.lull;

import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Class bytes as they run, for the engine's bytecode analyses: they read classes as resources - before mixins - and
 * refuse what other mods' mixins change (MixinTouch); with the running code they can analyse it instead.
 */
public final class ClassBytes {
    private ClassBytes() {}

    /**
     * -Dlab.analysis.running=true: for a class other mods' mixins change, the analyses read the code that runs instead
     * of refusing what those mixins touch.
     */
    public static final boolean RUNNING = Boolean.getBoolean("lab.analysis.running");

    /**
     * The class's node (flags as ClassReader.accept's) read as its resource, or null when it is not there; with
     * lab.analysis.running, what other mods' mixins change in it as it runs (readRunning).
     */
    public static ClassNode read(ClassLoader loader, String internalName, int flags) throws java.io.IOException {
        ClassNode cn = readBefore(loader, internalName, flags);
        if (cn == null) return null;
        if (RUNNING && dev.lull.optsupport.MixinTouch.anyForeign(internalName)
                && readRunning(loader, internalName, cn, name -> dev.lull.optsupport.MixinTouch.touched(internalName, name)) != null)
            MERGED.add(internalName);   // other mods' mixins in it: what they change, as it runs
        return cn;
    }

    /** The class's node before mixins (flags as ClassReader.accept's), or null when the resource is not there. */
    public static ClassNode readBefore(ClassLoader loader, String internalName, int flags) throws java.io.IOException {
        try (InputStream in = (loader != null ? loader : ClassLoader.getSystemClassLoader()).getResourceAsStream(internalName + ".class")) {
            if (in == null) return null;
            ClassNode cn = new ClassNode();
            new ClassReader(in).accept(cn, flags);
            return complete(cn);
        }
    }

    /**
     * A node's method lists as the analyses expect them. Stock ASM creates every method's lists; a mod that patches ASM to
     * save memory may leave an empty one null (an I3AT5 pack: "method.tryCatchBlocks is null" in Analyzer, every entity
     * of two mods refused). A list null there was never filled - ClassWriter could not write the method otherwise.
     */
    public static ClassNode complete(ClassNode cn) {
        for (org.objectweb.asm.tree.MethodNode m : cn.methods) {
            if (m.tryCatchBlocks == null) m.tryCatchBlocks = new java.util.ArrayList<>(0);
            if (m.localVariables == null) m.localVariables = new java.util.ArrayList<>(0);
            if (m.exceptions == null) m.exceptions = new java.util.ArrayList<>(0);
        }
        return cn;
    }

    /**
     * An analyzer run on code whose max stack / locals may be too small: other mods' mixins leave them to the class
     * writer (transformClass hands the pre-mixin values on), and ASM's COMPUTE_MAXS cannot size it either - in code
     * without frames it undersizes a catch handler (Entity.tryCheckInsideBlocks: 2 where NEW DUP ALOAD needs 3). The
     * sizes only bound the analyzer's frames, so they grow until the analysis fits. (Collections Of Optimizations: 15
     * entity classes' analyses failed with "Insufficient maximum stack size", their entities never slept.)
     */
    public static <V extends org.objectweb.asm.tree.analysis.Value> org.objectweb.asm.tree.analysis.Frame<V>[] analyze(
            org.objectweb.asm.tree.analysis.Analyzer<V> an, String owner, org.objectweb.asm.tree.MethodNode m) throws org.objectweb.asm.tree.analysis.AnalyzerException {
        for (int grown = 0; ; grown++) {
            try {
                return an.analyze(owner, m);
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException e) {
                String msg = String.valueOf(e.getMessage());
                boolean stack = msg.contains("Insufficient maximum stack size");
                if (!stack && !msg.contains("inexistant local variable") || grown == 12) throw e;
                if (stack) m.maxStack = Math.min(0xFFFF, Math.max(m.maxStack * 2, m.maxStack + 4));
                else m.maxLocals = Math.min(0xFFFF, Math.max(m.maxLocals * 2, m.maxLocals + 4));
            }
        }
    }

    /** set while readRunning puts a class through Mixin's transformer again (a copy for the analyses, not the class) */
    public static final ThreadLocal<Boolean> REREAD = new ThreadLocal<>();

    /** classes read with what other mods' mixins change in them as it runs (readRunning) */
    private static final java.util.Set<String> MERGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Is this method changed by another mod's mixin in a way the analyses cannot see - its class read before mixins
     * (or not read yet)? With -Dlab.analysis.running the analyses see such methods as they run.
     */
    public static boolean unseen(String owner, String name) {
        return dev.lull.optsupport.MixinTouch.touched(owner, name) && !MERGED.contains(owner);
    }

    /**
     * The class before mixins ({@code before}, as read) with the methods other mods' mixins change ({@code foreign}, by
     * name) as they run, and what mixins add (handlers, merged methods, interfaces, fields); this mod's own changes
     * elsewhere stay out, as in the resource bytes. Null when the running code is not at hand.
     */
    public static ClassNode readRunning(ClassLoader loader, String internalName, ClassNode before, java.util.function.Predicate<String> foreign) {
        ClassNode run = readRunning(loader, internalName);
        if (run == null || before == null) return null;
        java.util.Map<String, org.objectweb.asm.tree.MethodNode> had = new java.util.HashMap<>();
        for (org.objectweb.asm.tree.MethodNode m : before.methods) had.put(m.name + m.desc, m);
        for (org.objectweb.asm.tree.MethodNode m : run.methods) {
            org.objectweb.asm.tree.MethodNode b = had.get(m.name + m.desc);
            if (b == null) before.methods.add(m);
            else if (foreign.test(m.name)) before.methods.set(before.methods.indexOf(b), m);
        }
        for (String i : run.interfaces) if (!before.interfaces.contains(i)) before.interfaces.add(i);   // a mixin's interface, its default methods
        java.util.Set<String> fields = new java.util.HashSet<>();
        for (org.objectweb.asm.tree.FieldNode f : before.fields) fields.add(f.name);
        for (org.objectweb.asm.tree.FieldNode f : run.fields) if (fields.add(f.name)) before.fields.add(f);
        return before;
    }

    /**
     * The class as it runs: its resource bytes put through Mixin's transformer again (every mod's mixins applied as when
     * the class was loaded; Mixin's applyMixins is synchronized), no frames. Null when the resource or Mixin's
     * transformer is not at hand, or the transformer fails.
     */
    public static ClassNode readRunning(ClassLoader loader, String internalName) {
        // each class through Mixin's transformer once: the analyses ask for the same superclasses (LivingEntity, Mob)
        // again and again, and every pass made Mixin print other mods' warnings again (issue #8: Critters and
        // Companions' Shift.BY warning 21 more times, from our analysis thread); a copy each time, the analyses change it
        ClassNode cached;
        synchronized (RUN_CACHE) {
            cached = RUN_CACHE.get(internalName);
        }
        if (cached == null) {
            cached = readRunning0(loader, internalName);
            if (cached == null) return null;
            synchronized (RUN_CACHE) {
                RUN_CACHE.put(internalName, cached);
            }
        }
        ClassNode copy = new ClassNode();
        cached.accept(copy);
        return copy;
    }

    /** the classes read as they run, the last 32 (the shared superclasses stay; a class's node can be large) */
    private static final java.util.LinkedHashMap<String, ClassNode> RUN_CACHE = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, ClassNode> e) {
            return size() > 32;
        }
    };

    private static ClassNode readRunning0(ClassLoader loader, String internalName) {
        try (InputStream in = (loader != null ? loader : ClassLoader.getSystemClassLoader()).getResourceAsStream(internalName + ".class")) {
            if (in == null) return null;
            org.spongepowered.asm.mixin.MixinEnvironment env = org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment();
            if (!(env.getActiveTransformer() instanceof org.spongepowered.asm.mixin.transformer.IMixinTransformer t)) return null;
            ClassNode cn = new ClassNode();
            new ClassReader(in).accept(cn, ClassReader.EXPAND_FRAMES);
            REREAD.set(Boolean.TRUE);
            try {
                t.transformClass(env, internalName.replace('/', '.'), cn);
            } finally {
                REREAD.remove();
            }
            for (org.objectweb.asm.tree.MethodNode m : cn.methods)
                for (org.objectweb.asm.tree.AbstractInsnNode i : m.instructions.toArray())
                    if (i instanceof org.objectweb.asm.tree.FrameNode) m.instructions.remove(i);
            return complete(cn);
        } catch (Throwable e) {
            System.out.println("[sleep/analysis] " + internalName + ": the running code not at hand: " + e);
            return null;
        }
    }
}
