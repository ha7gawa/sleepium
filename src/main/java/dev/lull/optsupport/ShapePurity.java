package dev.lull.optsupport;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Proves, from bytecode, that a Block class's collision shape is a pure function of the BlockState: the resolved
 * {@code getCollisionShape(state, level, pos, context)} (and, for the default one, {@code getShape(...)}) never reads
 * level, pos or context, never writes anything, reads only final fields, allocates nothing and calls only pure
 * helpers (state property reads, enum/Direction accessors, map lookups, Shapes.block()/empty(), and methods that pass
 * the same analysis). Then the value cached in the state (computed by that same method) is exactly what every call
 * returns, the very same object. Anything unknown -> not static (vanilla path). Classes transformed by any mixin
 * are rejected: their resource bytes would not be the code that runs.
 * {@code -Dlab.opt.shapeUnsafe=true}: treat every block as static (upper-bound experiment only, NOT exact).
 */
public final class ShapePurity {
    private static final boolean UNSAFE = Boolean.getBoolean("lab.opt.shapeUnsafe");
    private static final boolean LOG = Boolean.getBoolean("lab.opt.shapeLog");
    private static final String BLOCK_BEHAVIOUR = "net/minecraft/world/level/block/state/BlockBehaviour";
    private static final String SHAPE_DESC = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;";
    private static final int MAX_DEPTH = 6;
    // 1.20.1: Forge production runs SRG member names, but the analysed bytecode comes through ClassBytes with Mojang's
    // (its members and the references in its code): methods are looked up and fields matched by Mojang's names. Under
    // the SRG name no block's getCollisionShape was found in production - every shape "not static", the collision memo
    // never recorded (items: BlockCollisions 25 % of the server thread, 6.6 ms per tick against dev's 4.0)
    private static final String GET_COLLISION = "getCollisionShape";
    private static final String GET_SHAPE = "getShape";

    private static final Map<Class<?>, Boolean> RESULT = new ConcurrentHashMap<>();
    private static final Map<String, ClassNode> NODES = new ConcurrentHashMap<>();
    public static volatile int accepted, rejected;

    /** Pure calls on other receivers, by owner/name (any descriptor). */
    private static final Set<String> PURE_CALLS = Set.of(
            "net/minecraft/world/level/block/state/BlockState.getValue",
            "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.getValue",
            "net/minecraft/world/level/block/state/StateHolder.getValue",
            "net/minecraft/world/level/block/state/BlockState.hasProperty",
            "net/minecraft/world/level/block/state/StateHolder.hasProperty",
            "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.getBlock",
            "net/minecraft/world/level/block/state/BlockState.getBlock",
            "net/minecraft/world/phys/shapes/Shapes.block",
            "net/minecraft/world/phys/shapes/Shapes.empty",
            "net/minecraft/core/Direction.ordinal",
            "net/minecraft/core/Direction.get2DDataValue",
            "net/minecraft/core/Direction.get3DDataValue",
            "net/minecraft/core/Direction.getOpposite",
            "net/minecraft/core/Direction.getAxis",
            "net/minecraft/core/Direction.getClockWise",
            "net/minecraft/core/Direction.getCounterClockWise",
            "net/minecraft/core/Direction.getAxisDirection",
            "net/minecraft/core/Direction$Axis.ordinal",
            "net/minecraft/core/Direction$Axis.isHorizontal",
            "net/minecraft/core/Direction$Axis.isVertical",
            "java/lang/Enum.ordinal",
            "java/lang/Integer.intValue",
            "java/lang/Boolean.booleanValue",
            "java/util/Map.get",
            "com/google/common/collect/ImmutableMap.get",
            "java/lang/Object.getClass");

    private ShapePurity() {}

    /** Classes whose default getCollisionShape is kept but whose getShape is not static: static only without collision. */
    private static final Set<Class<?>> DEFAULT_COLLISION = ConcurrentHashMap.newKeySet();
    private static volatile Field hasCollision;

    public static boolean isStatic(net.minecraft.world.level.block.Block block) {
        if (UNSAFE) return true;
        Class<?> c = block.getClass();
        if (RESULT.computeIfAbsent(c, ShapePurity::analyse)) return true;
        // default getCollisionShape = hasCollision ? state.getShape(level, pos) : Shapes.empty(); hasCollision is final
        return DEFAULT_COLLISION.contains(c) && !hasCollision(block);
    }

    private static boolean hasCollision(Object block) {
        try {
            Field f = hasCollision;
            if (f == null) {
                f = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour", false, block.getClass().getClassLoader()).getDeclaredField(dev.lull.Names.field(BLOCK_BEHAVIOUR, "hasCollision"));
                f.setAccessible(true);
                hasCollision = f;
            }
            return f.getBoolean(block);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean analyse(Class<?> c) {
        String why;
        try {
            why = check(c);
        } catch (Throwable t) {
            why = "error " + t;
        }
        if (why == null) accepted++;
        else rejected++;
        if (LOG) System.out.println("[sleep/opt] shape " + c.getName() + ": " + (why == null ? "STATIC" : "vanilla (" + why + ")"));
        return why == null;
    }

    /** null = static; otherwise the reason it is not. */
    private static String check(Class<?> c) {
        ClassLoader loader = c.getClassLoader();
        String cname = Type.getInternalName(c);
        for (String k = cname; k != null; ) {
            ClassNode cn = node(loader, k);
            if (touched(k, GET_COLLISION) || touched(k, GET_SHAPE)) return k + ": shape methods mixin-changed";
            if (k.equals(BLOCK_BEHAVIOUR)) break;
            if (cn == null) return "no bytes for " + k;
            k = cn.superName;
        }
        Resolved col = resolve(loader, cname, GET_COLLISION, SHAPE_DESC);
        if (col == null) return "getCollisionShape not found";
        if (col.owner.equals(BLOCK_BEHAVIOUR)) {
            // default: hasCollision ? state.getShape(level, pos) : empty -> static iff the resolved getShape is
            Resolved shape = resolve(loader, cname, GET_SHAPE, SHAPE_DESC);
            if (shape == null) return "getShape not found";
            String why = pure(loader, cname, shape, 0, true, false);
            if (why != null && !touched(BLOCK_BEHAVIOUR, GET_COLLISION)) DEFAULT_COLLISION.add(c);
            return why;
        }
        return pure(loader, cname, col, 0, true, false);
    }

    private record Resolved(String owner, MethodNode method) {}

    private static Resolved resolve(ClassLoader loader, String cls, String name, String desc) {
        for (String k = cls; k != null; ) {
            ClassNode cn = node(loader, k);
            if (touched(k, name)) return null;   // resolution not knowable from resource bytes
            if (cn == null) return null;
            for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return new Resolved(k, m);
            k = cn.superName;
        }
        return null;
    }

    /**
     * @param shapeArgs the method has the (state, level, pos, context) signature: slots 2-4 are forbidden. Helpers
     *                  only ever receive values derived from allowed ones, so all their slots are allowed.
     */
    private static String pure(ClassLoader loader, String concrete, Resolved r, int depth, boolean shapeArgs, boolean memo) {
        if (depth > MAX_DEPTH) return "too deep";
        MethodNode m = r.method;
        if (touched(r.owner, m.name)) return r.owner + "." + m.name + " mixin-changed";
        // identity matters only for returned objects: a method returning a primitive may allocate temporaries, and a
        // memo function (the argument of computeIfAbsent) is called once per key, its result then kept by the map
        boolean mayAllocate = memo || Type.getReturnType(m.desc).getSort() <= Type.DOUBLE;
        if ((m.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED)) != 0) return m.name + " native/abstract/synchronized";
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            int op = in.getOpcode();
            switch (op) {
                case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD -> {
                    int v = ((VarInsnNode) in).var;
                    if (shapeArgs && v >= 2 && v <= 4) return m.name + " reads level/pos/context";
                }
                case Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> { return m.name + " writes a field"; }
                case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE,
                        Opcodes.CASTORE, Opcodes.SASTORE -> { return m.name + " writes an array"; }
                case Opcodes.NEW, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY -> {
                    // a new Throwable can only be thrown (e.g. the MatchException default of an enum switch)
                    if (!mayAllocate && !(op == Opcodes.NEW && throwable(loader, ((TypeInsnNode) in).desc))) return m.name + " allocates";
                }
                case Opcodes.INVOKEDYNAMIC -> {
                    String why = memoLambda(loader, concrete, (InvokeDynamicInsnNode) in, depth);
                    if (why != null) return m.name + " " + why;
                }
                case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> { return m.name + " locks"; }
                case Opcodes.GETFIELD, Opcodes.GETSTATIC -> {
                    FieldInsnNode f = (FieldInsnNode) in;
                    if (!finalField(loader, f.owner, f.name)) return m.name + " reads non-final " + f.owner + "." + f.name;
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    if (PURE_CALLS.contains(mi.owner + "." + dev.lull.Names.official(mi.name)) || pureByShape(mi)) continue;
                    if (MEMO_CALLS.contains(mi.name) && MEMO_OWNERS.contains(mi.owner)) continue;
                    if (op == Opcodes.INVOKESPECIAL && mi.name.equals("<init>") && throwable(loader, mi.owner)) continue;   // building the exception to throw   // its function passed memoLambda
                    Resolved callee;
                    if (op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKESPECIAL) callee = resolve(loader, mi.owner, mi.name, mi.desc);
                    else if (isSuperOf(loader, mi.owner, concrete)) callee = resolve(loader, concrete, mi.name, mi.desc);   // call on this (or a sibling of its class)
                    else return m.name + " calls " + mi.owner + "." + mi.name;
                    if (callee == null) return m.name + " calls unresolved " + mi.owner + "." + mi.name;
                    // a virtual call on a block-typed receiver may target another block: only accept receivers that are 'this'
                    if (op != Opcodes.INVOKESTATIC && !receiverIsThis(in, mi)) return m.name + " calls " + mi.name + " on another object";
                    boolean argsShape = callee.method.desc.equals(SHAPE_DESC) && op != Opcodes.INVOKESTATIC;
                    String why = pure(loader, concrete, callee, depth + 1, argsShape, false);
                    if (why != null) return why;
                }
                default -> { }
            }
        }
        return null;
    }

    private static final Set<String> MEMO_OWNERS = Set.of("java/util/Map", "java/util/HashMap", "java/util/concurrent/ConcurrentHashMap",
            "it/unimi/dsi/fastutil/objects/Object2IntMap", "it/unimi/dsi/fastutil/objects/Object2IntOpenHashMap",
            "it/unimi/dsi/fastutil/objects/Object2ObjectMap", "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap");
    private static final Set<String> MEMO_CALLS = Set.of("computeIfAbsent", "computeIntIfAbsent");

    private static boolean throwable(ClassLoader loader, String internalName) {
        try {
            return Throwable.class.isAssignableFrom(Class.forName(internalName.replace('/', '.'), false, loader));
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String HOLDER = "net/minecraft/world/level/block/state/StateHolder";

    /** Pure by name and descriptor: Enum.ordinal is final; state.is(Block), setValue and boxing are pure. */
    private static boolean pureByShape(MethodInsnNode mi) {
        if (mi.name.equals("ordinal") && mi.desc.equals("()I")) return true;
        boolean state = mi.owner.equals(STATE) || mi.owner.equals(STATE_BASE) || mi.owner.equals(HOLDER);
        String name = dev.lull.Names.official(mi.name);   // 1.20.1: SRG in production
        if (state && name.equals("is") && mi.desc.equals("(Lnet/minecraft/world/level/block/Block;)Z")) return true;
        if (state && name.equals("setValue")) return true;
        return (mi.owner.equals("java/lang/Integer") || mi.owner.equals("java/lang/Boolean")) && mi.name.equals("valueOf");
    }

    /** An invokedynamic is accepted only as a LambdaMetafactory function consumed right away by computeIfAbsent. */
    private static String memoLambda(ClassLoader loader, String concrete, InvokeDynamicInsnNode indy, int depth) {
        if (!indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) return "invokedynamic " + indy.name;
        AbstractInsnNode next = indy.getNext();
        while (next != null && next.getOpcode() == -1) next = next.getNext();
        if (!(next instanceof MethodInsnNode use) || !MEMO_CALLS.contains(use.name) || !MEMO_OWNERS.contains(use.owner))
            return "lambda not used as a memo";
        if (!(indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle impl)) return "lambda without handle";
        Resolved callee = resolve(loader, impl.getOwner(), impl.getName(), impl.getDesc());
        if (callee == null) return "unresolved lambda " + impl.getName();
        return pure(loader, concrete, callee, depth + 1, false, true);
    }

    /** The receiver of an instance call taking no arguments or only simple ones is loaded right before by ALOAD 0. */
    private static boolean receiverIsThis(AbstractInsnNode call, MethodInsnNode mi) {
        int slots = Type.getArgumentTypes(mi.desc).length;
        // walk back over the argument-producing instructions only for the common simple shapes: this.m() / this.m(state)
        AbstractInsnNode p = call.getPrevious();
        if (slots == 0) return p instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0;
        if (slots == 1 && p instanceof VarInsnNode a && a.getOpcode() == Opcodes.ALOAD) {
            AbstractInsnNode q = p.getPrevious();
            return q instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0;
        }
        return false;
    }

    private static boolean isSuperOf(ClassLoader loader, String owner, String concrete) {
        for (String k = concrete; k != null; ) {
            if (k.equals(owner)) return true;
            ClassNode cn = node(loader, k);
            if (cn == null) return false;
            k = cn.superName;
        }
        return false;
    }

    private static boolean finalField(ClassLoader loader, String owner, String name) {
        try {
            for (Class<?> k = Class.forName(owner.replace('/', '.'), false, loader); k != null; k = k.getSuperclass()) {
                // the running name (SRG in production) against the analysed one (Mojang's, ClassBytes)
                for (Field f : k.getDeclaredFields()) if (dev.lull.Names.official(f.getName()).equals(name)) return Modifier.isFinal(f.getModifiers());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }


    /**
     * MixinTouch under either name of the method (1.20.1 production: injector targets are read as written, Mojang's,
     * while overwritten/merged methods of reobfuscated mixins carry SRG names). {@code running} is the bytecode name.
     */
    private static boolean touched(String owner, String running) {
        if (dev.lull.ClassBytes.unseen(owner, running)) return true;
        String official = dev.lull.Names.official(running);
        return !official.equals(running) && dev.lull.ClassBytes.unseen(owner, official);
    }

    // ---------------------------------------------------------------- context-free shapes

    private static final Map<Class<?>, Boolean> CONTEXT_FREE = new ConcurrentHashMap<>();
    public static volatile int contextFree, contextBound;

    /**
     * The block's collision shape does not depend on the collision context (who asks): the resolved getCollisionShape
     * - and every method it calls on itself with the same (state, level, pos, context) signature, the context passed as
     * that argument - never loads the context argument otherwise. Level, position and block entity may be read: the
     * shape at a position is then the same for every entity and particle while the level does not change. Methods a
     * foreign mixin changes, or calls that cannot be resolved, count as reading it.
     */
    public static boolean contextFree(net.minecraft.world.level.block.Block block) {
        return CONTEXT_FREE.computeIfAbsent(block.getClass(), ShapePurity::analyseContext);
    }

    private static boolean analyseContext(Class<?> c) {
        String why;
        try {
            ClassLoader loader = c.getClassLoader();
            String cname = Type.getInternalName(c);
            Resolved col = resolve(loader, cname, GET_COLLISION, SHAPE_DESC);
            why = col == null ? "getCollisionShape not resolved" : ctxFree(loader, cname, col, 0);
        } catch (Throwable t) {
            why = "error " + t;
        }
        if (why == null) contextFree++;
        else contextBound++;
        if (LOG) System.out.println("[sleep/opt] shape context " + c.getName() + ": " + (why == null ? "FREE" : "read (" + why + ")"));
        return why == null;
    }

    private static String ctxFree(ClassLoader loader, String concrete, Resolved r, int depth) {
        if (depth > MAX_DEPTH) return "too deep";
        MethodNode m = r.method;
        if (touched(r.owner, m.name)) return r.owner + "." + m.name + " mixin-changed";
        if ((m.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) return m.name + " native/abstract";
        boolean statik = (m.access & Opcodes.ACC_STATIC) != 0;
        int ctxSlot = statik ? 3 : 4;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.ASTORE && ((VarInsnNode) in).var == ctxSlot) return m.name + " overwrites the context slot";
            if (in.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) in).var != ctxSlot) continue;
            // allowed only as the last argument of a call on this with the shape signature: getShape(state, level, pos, context)
            AbstractInsnNode next = in.getNext();
            while (next != null && next.getOpcode() == -1) next = next.getNext();
            if (!(next instanceof MethodInsnNode mi) || !mi.desc.equals(SHAPE_DESC) || mi.getOpcode() == Opcodes.INVOKESTATIC)
                return m.name + " uses the context";
            if (!isSuperOf(loader, mi.owner, concrete)) return m.name + " passes the context to " + mi.owner + "." + mi.name;
            Resolved callee = mi.getOpcode() == Opcodes.INVOKESPECIAL ? resolve(loader, mi.owner, mi.name, mi.desc) : resolve(loader, concrete, mi.name, mi.desc);
            if (callee == null) return m.name + " calls unresolved " + mi.name;
            String why = ctxFree(loader, concrete, callee, depth + 1);
            if (why != null) return why;
        }
        return null;
    }

    private static ClassNode node(ClassLoader loader, String k) {
        ClassNode cached = NODES.get(k);
        if (cached != null) return cached;
        try {
            ClassNode cn = dev.lull.ClassBytes.read(loader, k, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);   // Mojang's names also in production
            if (cn == null) return null;
            NODES.put(k, cn);
            return cn;
        } catch (Exception e) {
            return null;
        }
    }

}
