package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Proves, from bytecode, that a particle class's tick touches nothing but the particle itself and reads the level
 * (ParallelParticles ticks such particles on the frame pool): the resolved tick() and every method it calls on the
 * particle's own class chain write only fields of that chain, read other objects' fields only of values (Vec3, AABB),
 * read statics only when final and not a random source, allocate only values (Vec3, AABB, positions), and call only
 * pure helpers (math, vectors, boxes, positions, the particle's own random source, sprite sets), the level's block /
 * fluid reads and Entity.collideBoundingBox (the collide kernel, made safe for these workers). Anything else - adding
 * particles, sounds, entities, a shared random, a lambda, a lock, a static write - keeps the class on the render thread.
 * Methods changed by another mod's mixin count as unknown.
 */
public final class ParticlePurity {
    private ParticlePurity() {}

    private static final boolean LOG = Boolean.getBoolean("lab.particles.log");
    private static final String PARTICLE = "net/minecraft/client/particle/Particle";
    private static final int MAX_DEPTH = 8;
    private static final Map<Class<?>, Boolean> RESULT = new ConcurrentHashMap<>();
    private static final Map<String, ClassNode> NODES = new ConcurrentHashMap<>();
    public static volatile int pure, impure;

    /** value types whose methods and constructors are pure */
    private static final Set<String> VALUE_OWNERS = Set.of(
            "net/minecraft/world/phys/Vec3", "net/minecraft/world/phys/AABB", "net/minecraft/core/BlockPos",
            "net/minecraft/core/BlockPos$MutableBlockPos", "net/minecraft/core/Vec3i", "net/minecraft/core/Direction",
            "net/minecraft/core/Direction$Axis", "org/joml/Vector3f", "org/joml/Vector3d", "org/joml/Quaternionf",
            "java/lang/Math", "java/lang/StrictMath", "net/minecraft/util/Mth", "java/lang/Double", "java/lang/Float",
            "java/lang/Integer", "java/lang/Long", "java/lang/Boolean", "java/util/Optional");
    /** pure calls by owner.name (any descriptor) */
    private static final Set<String> PURE_CALLS = Set.of(
            "java/util/List.of", "java/util/Collections.emptyList",
            "net/minecraft/client/particle/SpriteSet.get", "net/minecraft/client/particle/SpriteSet.getRandom",
            "net/minecraft/client/renderer/texture/TextureAtlasSprite.getU0", "net/minecraft/client/renderer/texture/TextureAtlasSprite.getU1",
            "net/minecraft/client/renderer/texture/TextureAtlasSprite.getV0", "net/minecraft/client/renderer/texture/TextureAtlasSprite.getV1",
            "net/minecraft/world/entity/Entity.collideBoundingBox",
            "net/minecraft/world/level/material/FluidState.isEmpty", "net/minecraft/world/level/material/FluidState.is",
            "net/minecraft/world/level/material/FluidState.getHeight", "net/minecraft/world/level/material/FluidState.getType",
            "net/minecraft/world/level/block/state/BlockState.isAir", "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.isAir",
            "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.getFluidState",
            "net/minecraft/world/level/block/state/BlockState.getFluidState");
    /** the particle's random source (a field of its own: one object per particle) */
    private static final String RANDOM = "net/minecraft/util/RandomSource";
    /** level reads (no write, no entity) */
    private static final Set<String> LEVEL_OWNERS = Set.of("net/minecraft/world/level/Level", "net/minecraft/client/multiplayer/ClientLevel",
            "net/minecraft/world/level/BlockGetter", "net/minecraft/world/level/LevelReader", "net/minecraft/world/level/CollisionGetter");
    private static final Set<String> LEVEL_READS = Set.of("getBlockState", "getFluidState", "isEmptyBlock", "getMinBuildHeight", "getMaxBuildHeight", "getHeight");

    public static boolean pure(Class<?> c) {
        return RESULT.computeIfAbsent(c, ParticlePurity::analyse);
    }

    private static boolean analyse(Class<?> c) {
        String why;
        try {
            ClassLoader loader = c.getClassLoader();
            String cname = Type.getInternalName(c);
            MethodNode tick = resolve(loader, cname, "tick", "()V");
            why = tick == null ? "tick not resolved" : check(loader, cname, ownerOf(loader, cname, "tick", "()V"), tick, 0);
        } catch (Throwable t) {
            why = "error " + t;
        }
        if (why == null) pure++;
        else impure++;
        if (LOG) System.out.println("[sleep/opt] particle " + c.getName() + ": " + (why == null ? "PARALLEL" : "render thread (" + why + ")"));
        return why == null;
    }

    private static String check(ClassLoader loader, String concrete, String owner, MethodNode m, int depth) {
        if (depth > MAX_DEPTH) return "too deep";
        if (touched(owner, m.name)) return owner + "." + m.name + " mixin-changed";
        if ((m.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED)) != 0) return m.name + " native/abstract/synchronized";
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            int op = in.getOpcode();
            switch (op) {
                case Opcodes.PUTSTATIC -> { return m.name + " writes a static"; }
                case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> { return m.name + " locks"; }
                case Opcodes.INVOKEDYNAMIC -> { return m.name + " invokedynamic"; }
                case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE,
                        Opcodes.CASTORE, Opcodes.SASTORE -> { return m.name + " writes an array"; }
                case Opcodes.PUTFIELD -> {
                    FieldInsnNode f = (FieldInsnNode) in;
                    if (!inChain(loader, f.owner, concrete)) return m.name + " writes " + f.owner + "." + f.name;
                }
                case Opcodes.GETFIELD -> {
                    FieldInsnNode f = (FieldInsnNode) in;
                    if (!inChain(loader, f.owner, concrete) && !VALUE_OWNERS.contains(f.owner)) return m.name + " reads " + f.owner + "." + f.name;
                }
                case Opcodes.GETSTATIC -> {
                    FieldInsnNode f = (FieldInsnNode) in;
                    if (f.desc.contains("RandomSource") || f.desc.equals("Ljava/util/Random;")) return m.name + " reads a shared random " + f.name;
                    if (!finalField(loader, f.owner, f.name)) return m.name + " reads non-final static " + f.owner + "." + f.name;
                }
                case Opcodes.NEW -> {
                    String t = ((TypeInsnNode) in).desc;
                    if (!VALUE_OWNERS.contains(t) && !throwable(loader, t)) return m.name + " allocates " + t;
                }
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY -> { return m.name + " allocates an array"; }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    String key = mi.owner + "." + mi.name;
                    if (VALUE_OWNERS.contains(mi.owner) || PURE_CALLS.contains(key)) continue;
                    if (mi.owner.equals(RANDOM) && op == Opcodes.INVOKEINTERFACE) continue;   // obtained from the particle's own field (shared ones refused above)
                    if (LEVEL_OWNERS.contains(mi.owner) && LEVEL_READS.contains(mi.name)) {
                        if (touched(mi.owner, mi.name) || MixinTouch.touched(mi.owner, mi.name)) return m.name + " level read " + mi.name + " mixin-changed";
                        continue;
                    }
                    if (op == Opcodes.INVOKESPECIAL && mi.name.equals("<init>") && throwable(loader, mi.owner)) continue;
                    if (!inChain(loader, mi.owner, concrete)) return m.name + " calls " + key;
                    String calleeOwner = op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKESTATIC
                            ? ownerOf(loader, mi.owner, mi.name, mi.desc) : ownerOf(loader, concrete, mi.name, mi.desc);
                    MethodNode callee = calleeOwner == null ? null : method(loader, calleeOwner, mi.name, mi.desc);
                    if (callee == null) return m.name + " calls unresolved " + key;
                    String why = check(loader, concrete, calleeOwner, callee, depth + 1);
                    if (why != null) return why;
                }
                default -> { }
            }
        }
        return null;
    }

    /**
     * MixinTouch under either name of the method (1.20.1 production: the analysed bytecode carries Mojang's names, while
     * overwritten / merged methods of reobfuscated mixins carry SRG names) - as ShapePurity.touched.
     */
    private static boolean touched(String owner, String name) {
        if (dev.lull.ClassBytes.unseen(owner, name)) return true;
        String official = dev.lull.Names.official(name);
        return !official.equals(name) && dev.lull.ClassBytes.unseen(owner, official);
    }

    /** owner is the particle's class or one of its superclasses up to Particle */
    private static boolean inChain(ClassLoader loader, String owner, String concrete) {
        for (String k = concrete; k != null; ) {
            if (k.equals(owner)) return true;
            if (k.equals(PARTICLE)) return false;
            ClassNode cn = node(loader, k);
            if (cn == null) return false;
            k = cn.superName;
        }
        return false;
    }

    private static MethodNode resolve(ClassLoader loader, String cls, String name, String desc) {
        String o = ownerOf(loader, cls, name, desc);
        return o == null ? null : method(loader, o, name, desc);
    }

    private static String ownerOf(ClassLoader loader, String cls, String name, String desc) {
        for (String k = cls; k != null; ) {
            ClassNode cn = node(loader, k);
            if (cn == null) return null;
            for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc) && (m.access & Opcodes.ACC_ABSTRACT) == 0) return k;
            k = cn.superName;
        }
        return null;
    }

    private static MethodNode method(ClassLoader loader, String owner, String name, String desc) {
        ClassNode cn = node(loader, owner);
        if (cn == null) return null;
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    private static boolean throwable(ClassLoader loader, String internalName) {
        try {
            return Throwable.class.isAssignableFrom(Class.forName(internalName.replace('/', '.'), false, loader));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean finalField(ClassLoader loader, String owner, String name) {
        try {
            for (Class<?> k = Class.forName(owner.replace('/', '.'), false, loader); k != null; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) if (dev.lull.Names.official(f.getName()).equals(name)) return Modifier.isFinal(f.getModifiers());   // 1.20.1: SRG at runtime in production
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static ClassNode node(ClassLoader loader, String k) {
        ClassNode cached = NODES.get(k);
        if (cached != null) return cached;
        try {
            ClassNode cn = dev.lull.ClassBytes.read(loader, k, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            if (cn == null) return null;
            NODES.put(k, cn);
            return cn;
        } catch (Exception e) {
            return null;
        }
    }
}
