package dev.lull.zoo;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The loader side of the mods' pool (lab prototype): scans the jars in mods/ (and the jars they carry inside) for
 * classes that call Executors.newFixedThreadPool / newSingleThreadExecutor / newCachedThreadPool and points exactly
 * those calls at {@link Zoo}. Generic - no list of mods. On with -Dsleep.zoo=true.
 */
public final class ZooService implements ITransformationService {
    static final String EX = "java/util/concurrent/Executors";
    static final Set<String> NAMES = Set.of("newFixedThreadPool", "newSingleThreadExecutor", "newCachedThreadPool");
    static final byte[] NEEDLE = EX.getBytes(StandardCharsets.UTF_8);
    private Path gameDir;

    @Override public String name() { return "sleepium_zoo"; }
    @Override public void initialize(IEnvironment env) {}
    @Override public void onLoad(IEnvironment env, Set<String> others) {
        gameDir = env.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(Path.of("."));
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        if (!Boolean.getBoolean("sleep.zoo")) {
            System.out.println("[sleepium zoo] off (-Dsleep.zoo=true to route the mods' executors to one pool)");
            return List.of();
        }
        long t0 = System.nanoTime();
        Set<String> targets = new HashSet<>();
        int[] jars = new int[2];
        try (Stream<Path> s = Files.list(gameDir.resolve("mods"))) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!p.toString().endsWith(".jar")) continue;
                try (InputStream in = Files.newInputStream(p)) {
                    scan(in, targets, jars, 0);
                } catch (IOException e) {
                    System.out.println("[sleepium zoo] cannot read " + p + ": " + e);
                }
            }
        } catch (IOException e) {
            System.out.println("[sleepium zoo] no mods dir: " + e);
        }
        System.out.printf("[sleepium zoo] %d classes call Executors' pools (%d jars, %d with such calls), scan %.0f ms%n",
                targets.size(), jars[0], jars[1], (System.nanoTime() - t0) / 1e6);
        if (targets.isEmpty()) return List.of();
        return List.of(new Rewrite(targets));
    }

    private static void scan(InputStream raw, Set<String> targets, int[] jars, int depth) throws IOException {
        jars[0]++;
        boolean hit = false;
        ZipInputStream z = new ZipInputStream(raw);
        for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
            String n = e.getName();
            if (n.endsWith(".jar") && depth < 2) {
                byte[] b = z.readAllBytes();
                scan(new ByteArrayInputStream(b), targets, jars, depth + 1);
            } else if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) {
                byte[] b = z.readAllBytes();
                if (indexOf(b, NEEDLE) < 0) continue;
                String cls = calls(b);
                if (cls != null) {
                    targets.add(cls);
                    hit = true;
                }
            }
        }
        if (hit) jars[1]++;
    }

    /** the class's name if one of its methods calls the pools we route, else null */
    private static String calls(byte[] b) {
        ClassReader r = new ClassReader(b);
        boolean[] found = {false};
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC && owner.equals(EX) && NAMES.contains(mname)) found[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0] ? r.getClassName().replace('/', '.') : null;
    }

    private static int indexOf(byte[] h, byte[] n) {
        outer:
        for (int i = 0, end = h.length - n.length; i <= end; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    static final class Rewrite implements ITransformer<ClassNode> {
        final Set<Target<ClassNode>> targets = new HashSet<>();

        Rewrite(Set<String> classes) {
            for (String c : classes) targets.add(Target.targetClass(c));
        }

        @Override
        public ClassNode transform(ClassNode node, ITransformerVotingContext ctx) {
            int n = 0;
            for (MethodNode m : node.methods) {
                for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                    if (i.getOpcode() != Opcodes.INVOKESTATIC) continue;
                    MethodInsnNode mi = (MethodInsnNode) i;
                    if (!mi.owner.equals(EX) || !NAMES.contains(mi.name)) continue;
                    m.instructions.insertBefore(mi, new LdcInsnNode(node.name));
                    mi.owner = "dev/lull/zoo/Zoo";
                    mi.desc = mi.desc.replace(")", "Ljava/lang/String;)");
                    mi.itf = false;
                    n++;
                }
                if (n > 0) m.maxStack = Math.max(m.maxStack, 4) + 1;
            }
            if (n > 0) System.out.println("[sleepium zoo] " + node.name + ": " + n + " executor(s) to the mods' pool");
            return node;
        }

        @Override public TransformerVoteResult castVote(ITransformerVotingContext ctx) { return TransformerVoteResult.YES; }
        @Override public Set<Target<ClassNode>> targets() { return targets; }
        @Override public TargetType<ClassNode> getTargetType() { return TargetType.CLASS; }
    }
}
