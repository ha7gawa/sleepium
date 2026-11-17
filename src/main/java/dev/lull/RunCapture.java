package dev.lull;

import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;
import org.objectweb.asm.tree.ClassNode;

/**
 * Entity and block entity classes as Mixin left them when the game loaded them (11-17). The analyses read a class
 * "as it runs" (ClassBytes.readRunning) when other mods' mixins change it; that was a second pass through Mixin's
 * transformer, holding Mixin's processor lock - and every class any other thread loads waits for that lock (a player's
 * 250-mod pack: the render thread waited up to 1.7 s behind our analysis, a mod looking a class up each tick). Mixin
 * calls its extensions right after it applied the mixins to a class (postApply), on the thread loading it: here the
 * class's bytes are kept then, for the subclasses of Entity and BlockEntity only, and readRunning takes them with no
 * pass and no lock. A class the analyses needed that was not kept (Container, ItemStack - read for what an entity
 * calls) goes through the pass once and its name into config/sleepium-capture.txt: the next starts keep it too, so
 * from the second start the analyses never take Mixin's lock. -Dlab.analysis.capture=false: the pass as before.
 */
public final class RunCapture implements IExtension {
    public static final boolean ON = !"false".equals(System.getProperty("lab.analysis.capture"));
    private static final String ENTITY = "net/minecraft/world/entity/Entity", BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final ConcurrentHashMap<String, byte[]> BYTES = new ConcurrentHashMap<>();
    /** classes past analyses needed beyond the entities (config/sleepium-capture.txt), kept at their load too */
    private static final java.util.Set<String> WANTED = ConcurrentHashMap.newKeySet();
    private static final java.nio.file.Path WANTED_FILE = java.nio.file.Paths.get("config", "sleepium-capture.txt");
    public static volatile long captured, capturedBytes, failed, used;
    private static volatile boolean registered;

    private RunCapture() {}

    /** OptMixinPlugin.onLoad: added to Mixin's extensions (once) */
    public static void register() {
        if (!ON || registered) return;
        try {
            MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
            if (!(env.getActiveTransformer() instanceof IMixinTransformer t) || !(t.getExtensions() instanceof Extensions ex)) {
                System.out.println("[sleep/analysis] run capture: Mixin's transformer not at hand, the analyses pass classes through it as before");
                return;
            }
            try {
                if (java.nio.file.Files.exists(WANTED_FILE))
                    for (String l : java.nio.file.Files.readAllLines(WANTED_FILE)) if (!l.isBlank() && !l.startsWith("#")) WANTED.add(l.trim());
            } catch (Throwable ignored) {}
            ex.add(new RunCapture());
            ex.select(env);
            registered = true;
        } catch (Throwable e) {
            System.out.println("[sleep/analysis] run capture: not registered (" + e + ")");
        }
    }

    /** ClassBytes: a class the analyses passed through the transformer (not kept): kept from the next start on */
    public static void wanted(String internalName) {
        if (!ON || !WANTED.add(internalName)) return;
        try {
            if (!java.nio.file.Files.exists(WANTED_FILE)) {
                java.nio.file.Files.createDirectories(WANTED_FILE.getParent());
                java.nio.file.Files.writeString(WANTED_FILE, "# classes Sleepium's analyses read beyond the entities: kept as they load (no pass through Mixin's transformer)\n");
            }
            java.nio.file.Files.writeString(WANTED_FILE, internalName + "\n", java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {}
    }

    /** the kept bytes of a class as it runs, or null */
    public static byte[] get(String internalName) {
        byte[] b = BYTES.get(internalName);
        if (b != null) used++;
        return b;
    }

    public static String report() {
        return "run capture: " + (registered ? captured + " classes kept (" + capturedBytes / 1024 + " KiB), " + failed + " failed, " + used + " reads, "
                + WANTED.size() + " wanted beyond the entities" : "off");
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {}

    @Override
    public void postApply(ITargetClassContext context) {
        if (ClassBytes.rereading()) return;   // (our own pass: the same bytes)
        try {
            ClassInfo info = context.getClassInfo();
            String n = info.getName();
            if (!WANTED.contains(n) && !n.equals(ENTITY) && !n.equals(BLOCK_ENTITY) && !info.hasSuperClass(ENTITY) && !info.hasSuperClass(BLOCK_ENTITY)) return;
            ClassNode node = context.getClassNode();
            ClassWriter cw = new ClassWriter(0);
            node.accept(new ClassVisitor(Opcodes.ASM9, cw) {   // no frames (the analyses drop them): nothing to keep consistent
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                        @Override
                        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {}
                    };
                }
            });
            byte[] b = cw.toByteArray();
            BYTES.put(n, b);
            captured++;
            capturedBytes += b.length;
        } catch (Throwable e) {
            failed++;
        }
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {}
}
