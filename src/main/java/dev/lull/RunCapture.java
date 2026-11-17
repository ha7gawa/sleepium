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
 * pass and no lock. -Dlab.analysis.capture=false: the pass as before.
 */
public final class RunCapture implements IExtension {
    public static final boolean ON = !"false".equals(System.getProperty("lab.analysis.capture"));
    private static final String ENTITY = "net/minecraft/world/entity/Entity", BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final ConcurrentHashMap<String, byte[]> BYTES = new ConcurrentHashMap<>();
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
            ex.add(new RunCapture());
            ex.select(env);
            registered = true;
        } catch (Throwable e) {
            System.out.println("[sleep/analysis] run capture: not registered (" + e + ")");
        }
    }

    /** the kept bytes of a class as it runs, or null */
    public static byte[] get(String internalName) {
        byte[] b = BYTES.get(internalName);
        if (b != null) used++;
        return b;
    }

    public static String report() {
        return "run capture: " + (registered ? captured + " classes kept (" + capturedBytes / 1024 + " KiB), " + failed + " failed, " + used + " reads" : "off");
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
            if (!n.equals(ENTITY) && !n.equals(BLOCK_ENTITY) && !info.hasSuperClass(ENTITY) && !info.hasSuperClass(BLOCK_ENTITY)) return;
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
