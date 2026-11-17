package dev.lull;

import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

/**
 * Class bytes for the engine's bytecode analyses (sleep, shape purity, escape, move memo), read as the class's
 * resource - before mixins - with Mojang's member names: in production Forge 1.20.1's classes carry SRG member names
 * (m_12345_, f_12345_, lambda$m_12345_$0), turned back here (Names.official), so every analysis compares names as it
 * does on 1.21.1. What an analysis then looks up at run time goes through Names.field / Names.method / Names.official.
 */
public final class ClassBytes {
    private ClassBytes() {}

    private static final Remapper OFFICIAL = new Remapper() {
        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return official(name);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return Names.official(name);
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            return official(name);
        }
    };

    /** m_x_ and lambda$m_x_$N (a lambda's synthetic method named after its SRG enclosing method) */
    private static String official(String name) {
        if (name.startsWith("lambda$")) {
            int end = name.lastIndexOf('$');
            if (end > 7) return "lambda$" + Names.official(name.substring(7, end)) + name.substring(end);
        }
        return Names.official(name);
    }

    /** The class's node before mixins (flags as ClassReader.accept's), or null when the resource is not there. */
    public static ClassNode readBefore(ClassLoader loader, String internalName, int flags) throws java.io.IOException {
        try (InputStream in = (loader != null ? loader : ClassLoader.getSystemClassLoader()).getResourceAsStream(internalName + ".class")) {
            if (in == null) return null;
            ClassNode cn = new ClassNode();
            ClassReader r = new ClassReader(in);
            if (Names.SRG) r.accept(new ClassRemapper(cn, OFFICIAL), flags);
            else r.accept(cn, flags);
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
     * The class's node (flags as ClassReader.accept's), or null when the resource is not there; with
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
     * -Dlab.analysis.running=true: for a class other mods' mixins change, the analyses read the code that runs instead
     * of refusing what those mixins touch.
     */
    public static final boolean RUNNING = Boolean.getBoolean("lab.analysis.running");

    /**
     * The class before mixins ({@code before}, as read) with the methods other mods' mixins change ({@code foreign}, by
     * name) as they run, and the methods mixins add (handlers, merged methods); this mod's own changes elsewhere stay out,
     * as in the resource bytes. Null when the running code is not at hand.
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
     * the class was loaded; Mixin's applyMixins is synchronized), Mojang's names, no frames. Null when the resource or
     * Mixin's transformer is not at hand, or the transformer fails.
     */
    public static ClassNode readRunning(ClassLoader loader, String internalName) {
        // each class through Mixin's transformer once: the analyses ask for the same superclasses (LivingEntity, Mob)
        // again and again, and every pass made Mixin print other mods' warnings again (issue #8: Critters and
        // Companions' Shift.BY warning 21 more times, from our analysis thread); a copy each time, the analyses change it
        if (!RUN_CACHE_ON) return readRunning0(loader, internalName);
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

    /** -Dlab.analysis.runCache (default on), -Dlab.analysis.passPriority (default on): issue #8 / the 11-17 lock fix, switchable */
    private static final boolean RUN_CACHE_ON = !"false".equals(System.getProperty("lab.analysis.runCache")),
            PASS_PRIORITY = !"false".equals(System.getProperty("lab.analysis.passPriority"));
    public static long passes, passNanos, passMaxNanos;
    public static String passMax = "";

    public static String passReport() {
        return String.format(java.util.Locale.ROOT, "analysis passes through Mixin's transformer: %d, %.0f ms in all, the longest %.0f ms (%s); %s%s",
                passes, passNanos / 1e6, passMaxNanos / 1e6, passMax, RunCapture.report(),
                CAPTURE_VERIFY ? ", verified " + captureVerified + ", mismatches " + captureMismatches : "");
    }

    /** the classes read as they run, the last 32 (the shared superclasses stay; a class's node can be large) */
    private static final java.util.LinkedHashMap<String, ClassNode> RUN_CACHE = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, ClassNode> e) {
            return size() > 32;
        }
    };

    /** a class's header from its bytes: super name, then the interfaces (null: no bytes) */
    private static final java.util.concurrent.ConcurrentHashMap<String, String[]> HEADERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String[] NONE = new String[0];
    public static final boolean TYPE_VERIFY = Boolean.getBoolean("lab.analysis.typeVerify");
    public static long typeChecks, typeFallbacks, typeMismatches;

    private static String[] header(ClassLoader loader, String internal) {
        String[] h = HEADERS.get(internal);
        if (h != null) return h == NONE ? null : h;
        try (InputStream in = (loader != null ? loader : ClassLoader.getSystemClassLoader()).getResourceAsStream(internal + ".class")) {
            if (in == null) {
                HEADERS.put(internal, NONE);
                return null;
            }
            ClassReader r = new ClassReader(in);
            String[] is = r.getInterfaces();
            h = new String[is.length + 1];
            h[0] = r.getSuperName();
            System.arraycopy(is, 0, h, 1, is.length);
            HEADERS.put(internal, h);
            return h;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Is {@code internal} {@code target} or a subtype of it - from the class files' headers, nothing loaded (a load goes
     * through the transformer under Mixin's lock). JDK types are asked of the JDK (loaded outside the transformer).
     * Null: not decidable from the bytes (a class file missing).
     */
    public static Boolean subtypeByBytes(ClassLoader loader, String internal, String target, boolean interfaces) {
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        todo.add(internal);
        while (!todo.isEmpty()) {
            String n = todo.poll();
            if (n == null || !seen.add(n)) continue;
            if (n.equals(target)) return true;
            if (n.equals("java/lang/Object")) continue;
            if (n.startsWith("java/") || n.startsWith("javax/") || n.startsWith("jdk/") || n.startsWith("sun/")) {
                if (!target.startsWith("java/")) continue;   // a JDK class is no subtype of a game type
                try {
                    if (Class.forName(target.replace('/', '.')).isAssignableFrom(Class.forName(n.replace('/', '.')))) return true;
                } catch (Throwable e) {
                    return null;
                }
                continue;
            }
            String[] h = header(loader, n);
            if (h == null) return null;
            todo.add(h[0]);
            if (interfaces) for (int i = 1; i < h.length; i++) todo.add(h[i]);
        }
        return false;
    }

    /** a loaded class's runtime supertypes by name (mixins' interfaces included): is {@code target} among them */
    public static boolean runtimeSubtype(Class<?> c, String target) {
        String t = target.replace('/', '.');
        java.util.ArrayDeque<Class<?>> todo = new java.util.ArrayDeque<>();
        todo.add(c);
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        while (!todo.isEmpty()) {
            Class<?> k = todo.poll();
            if (k == null || !seen.add(k)) continue;
            if (k.getName().equals(t)) return true;
            if (k.getSuperclass() != null) todo.add(k.getSuperclass());
            java.util.Collections.addAll(todo, k.getInterfaces());
        }
        return false;
    }

    /**
     * The analyses' Class.forName: a class not loaded yet is loaded now, through the transformer under Mixin's
     * processor lock - at a high priority while it does (the analysis thread runs at the lowest: an inversion every
     * class load on another thread would wait on)
     */
    public static Class<?> forName(String name, boolean init, ClassLoader loader) throws ClassNotFoundException {
        Thread th = Thread.currentThread();
        int priority = th.getPriority();
        if (priority >= Thread.NORM_PRIORITY) return Class.forName(name, init, loader);
        th.setPriority(Thread.MAX_PRIORITY);
        try {
            return Class.forName(name, init, loader);
        } finally {
            th.setPriority(priority);
        }
    }

    /** RunCapture: inside our own pass through Mixin's transformer (its postApply skips it) */
    public static boolean rereading() {
        return REREAD.get() != null;
    }

    private static final boolean CAPTURE_VERIFY = Boolean.getBoolean("lab.analysis.capture.verify");
    public static long captureVerified, captureMismatches;

    /** the kept bytes (RunCapture) as the pass would give them: Mojang's names, no frames */
    private static ClassNode fromCapture(byte[] b) {
        ClassNode cn = new ClassNode();
        ClassReader r = new ClassReader(b);
        if (Names.SRG) r.accept(new ClassRemapper(cn, OFFICIAL), ClassReader.SKIP_FRAMES);
        else r.accept(cn, ClassReader.SKIP_FRAMES);
        return complete(cn);
    }

    /** verify: the same methods with the same instructions (opcodes, in order) */
    private static String differs(ClassNode a, ClassNode b) {
        java.util.Map<String, org.objectweb.asm.tree.MethodNode> m = new java.util.HashMap<>();
        for (org.objectweb.asm.tree.MethodNode x : b.methods) m.put(x.name + x.desc, x);
        if (a.methods.size() != b.methods.size()) return "methods " + a.methods.size() + " vs " + b.methods.size();
        for (org.objectweb.asm.tree.MethodNode x : a.methods) {
            org.objectweb.asm.tree.MethodNode y = m.get(x.name + x.desc);
            if (y == null) return "no " + x.name + x.desc;
            var xi = x.instructions.toArray();
            var yi = y.instructions.toArray();
            java.util.List<Integer> xo = new java.util.ArrayList<>(), yo = new java.util.ArrayList<>();
            for (var i : xi) if (i.getOpcode() >= 0) xo.add(i.getOpcode());
            for (var i : yi) if (i.getOpcode() >= 0) yo.add(i.getOpcode());
            if (!xo.equals(yo)) return x.name + x.desc + ": " + xo.size() + " vs " + yo.size() + " instructions";
        }
        return null;
    }

    private static ClassNode readRunning0(ClassLoader loader, String internalName) {
        byte[] captured = RunCapture.ON ? RunCapture.get(internalName) : null;
        if (captured != null) {   // as Mixin left it when the class was loaded: no pass, no lock
            ClassNode cn = fromCapture(captured);
            if (!CAPTURE_VERIFY) return cn;
            ClassNode pass = readRunningPass(loader, internalName);
            if (pass != null) {
                captureVerified++;
                String d = differs(cn, pass);
                if (d != null && captureMismatches++ < 10) System.out.println("[sleep/analysis] run capture MISMATCH " + internalName + ": " + d);
            }
            return cn;
        }
        ClassNode pass = readRunningPass(loader, internalName);
        if (pass != null && RunCapture.ON) RunCapture.wanted(internalName);   // kept at its load from the next start on
        return pass;
    }

    private static ClassNode readRunningPass(ClassLoader loader, String internalName) {
        try (InputStream in = (loader != null ? loader : ClassLoader.getSystemClassLoader()).getResourceAsStream(internalName + ".class")) {
            if (in == null) return null;
            org.spongepowered.asm.mixin.MixinEnvironment env = org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment();
            if (!(env.getActiveTransformer() instanceof org.spongepowered.asm.mixin.transformer.IMixinTransformer t)) return null;
            ClassNode raw = new ClassNode();
            new ClassReader(in).accept(raw, ClassReader.EXPAND_FRAMES);
            REREAD.set(Boolean.TRUE);
            // Mixin's processor lock is held through the pass, and every class any thread loads meanwhile waits for it
            // (a 250-mod 1.20.1 pack, 11-17: a mod's Class.forName each client tick, the render thread waiting up to
            // 1.7 s on our analysis thread, which runs at the lowest priority - an inversion): the pass at a high one
            Thread th = Thread.currentThread();
            int priority = th.getPriority();
            if (PASS_PRIORITY && priority < Thread.NORM_PRIORITY) th.setPriority(Thread.MAX_PRIORITY);
            long tp = System.nanoTime();
            try {
                t.transformClass(env, internalName.replace('/', '.'), raw);
            } finally {
                th.setPriority(priority);
                REREAD.remove();
                long d = System.nanoTime() - tp;
                passes++;
                passNanos += d;
                if (d > passMaxNanos) {
                    passMaxNanos = d;
                    passMax = internalName;
                }
                if (d > 50_000_000L) System.out.println("[sleep/analysis] " + internalName + " through Mixin's transformer: " + d / 1_000_000 + " ms (" + Thread.currentThread().getName() + ")");
            }
            ClassNode cn = new ClassNode();
            raw.accept(Names.SRG ? new ClassRemapper(cn, OFFICIAL) : cn);
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
