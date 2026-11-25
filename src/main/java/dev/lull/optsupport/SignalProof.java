package dev.lull.optsupport;

import java.lang.reflect.Field;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per block state: may the redstone signal path hand it a reused MutableBlockPos (SignalPathMixin)? Proven by PosEscape
 * for the state's block class: getSignal and shouldCheckWeakPower (with the state's conductor predicate) for a
 * neighbour, getDirectSignal for a neighbour's neighbour. Cached in the state itself (Flags, via SignalProofStateMixin).
 */
public final class SignalProof {
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;", POS = "Lnet/minecraft/core/BlockPos;",
            DIR = "Lnet/minecraft/core/Direction;";
    private static final String GET_SIGNAL = "(" + STATE + "Lnet/minecraft/world/level/BlockGetter;" + POS + DIR + ")I";
    private static final String WEAK = "(" + STATE + "Lnet/minecraft/world/level/SignalGetter;" + POS + DIR + ")Z";
    private static final String PREDICATE = "net/minecraft/world/level/block/state/BlockBehaviour$StatePredicate";
    private static volatile Field conductorField;
    /** Foreign mixins on the wrappers the path calls make the whole shortcut unsafe. */
    public static final boolean WRAPPERS_UNTOUCHED = untouched();
    public static long provenSignal, refusedSignal, provenDirect, refusedDirect;
    private static volatile Boolean allConductors;

    static {
        PosEscape.allConductorsOk = SignalProof::allConductors;
    }

    /** Every block state in the registry has a proven conductor predicate (fixed once blocks are registered). */
    static boolean allConductors() {
        Boolean v = allConductors;
        if (v != null) return v;
        boolean ok = true;
        String first = null;
        try {
            for (BlockState st : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) {
                Object pred = conductor(st);
                boolean cached = !st.getBlock().hasDynamicShape();
                String bc = st.getBlock().getClass().getName().replace('.', '/');
                if (pred == null || !PosEscape.lambdaHostNonRetaining(st.getBlock().getClass().getClassLoader(), pred.getClass(), PREDICATE, 1, cached, bc)) {
                    ok = false;
                    first = st.getBlock().getClass().getSimpleName() + " " + (pred == null ? "null" : pred.getClass().getName()) + ": " + PosEscape.lastReason;
                    break;
                }
            }
        } catch (Throwable t) {
            ok = false;
            first = t.toString();
        }
        if (LOG) System.out.println("[sleep/opt] all conductor predicates proven: " + ok + (first != null ? " (first refusal " + first + ")" : ""));
        allConductors = ok;
        return ok;
    }

    private SignalProof() {}

    /** Implemented on BlockStateBase: 0 = unknown, 1 = proven, 2 = not. */
    public interface Flags {
        byte sleep$sigFlag();

        void sleep$setSigFlag(byte v);

        byte sleep$dirFlag();

        void sleep$setDirFlag(byte v);
    }

    public static boolean signalOk(BlockState state) {
        Flags f = (Flags) state;
        byte v = f.sleep$sigFlag();
        if (v == 0) {
            boolean ok = compute(state, true);
            f.sleep$setSigFlag(v = (byte) (ok ? 1 : 2));
            if (ok) provenSignal++;
            else refusedSignal++;
        }
        return v == 1;
    }

    public static boolean directOk(BlockState state) {
        Flags f = (Flags) state;
        byte v = f.sleep$dirFlag();
        if (v == 0) {
            boolean ok = compute(state, false);
            f.sleep$setDirFlag(v = (byte) (ok ? 1 : 2));
            if (ok) provenDirect++;
            else refusedDirect++;
        }
        return v == 1;
    }

    private static final boolean LOG = Boolean.getBoolean("lab.opt.posLog");

    private static boolean compute(BlockState state, boolean neighbour) {
        if (!WRAPPERS_UNTOUCHED) return false;
        boolean r = compute0(state, neighbour);
        if (LOG && !r) System.out.println("[sleep/opt] signal proof refused " + state.getBlock().getClass().getSimpleName() + (neighbour ? " neighbour" : " direct")
                + ": " + PosEscape.lastReason);
        return r;
    }

    private static boolean compute0(BlockState state, boolean neighbour) {
        try {
            Block block = state.getBlock();
            ClassLoader loader = block.getClass().getClassLoader();
            String cls = block.getClass().getName().replace('.', '/');
            boolean cached = !block.hasDynamicShape();   // the state has a shape cache exactly then (initCache)
            if (!neighbour) {
                Object pred = conductor(state);
                boolean conductorOk = pred != null && PosEscape.lambdaHostNonRetaining(loader, pred.getClass(), PREDICATE, 1, cached, cls);
                return PosEscape.nonRetaining(loader, cls, "getDirectSignal", GET_SIGNAL, 2, new PosEscape.StateCtx(0, conductorOk, cached, cls));
            }
            Object pred = conductor(state);
            boolean conductorOk = pred != null && PosEscape.lambdaHostNonRetaining(loader, pred.getClass(), PREDICATE, 1, cached, cls);
            if (LOG && !conductorOk) System.out.println("[sleep/opt] conductor predicate not proven for " + state.getBlock().getClass().getSimpleName() + " (" + (pred == null ? "null" : pred.getClass().getName()) + "): " + PosEscape.lastReason);
            PosEscape.StateCtx ctx = new PosEscape.StateCtx(0, conductorOk, cached, cls);
            return PosEscape.nonRetaining(loader, cls, "getSignal", GET_SIGNAL, 2, ctx)
                    && PosEscape.nonRetaining(loader, cls, "shouldCheckWeakPower", WEAK, 2, ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object conductor(BlockState state) throws ReflectiveOperationException {
        Field f = conductorField;
        if (f == null) {
            f = dev.lull.Names.fieldOf(BlockBehaviour.BlockStateBase.class, "isRedstoneConductor");
            f.setAccessible(true);
            conductorField = f;
        }
        return f.get(state);
    }

    private static boolean untouched() {
        String base = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
        return !MixinTouch.touched(base, "getSignal") && !MixinTouch.touched(base, "getDirectSignal") && !MixinTouch.touched(base, "isRedstoneConductor")
                && !MixinTouch.touched(base, "isCollisionShapeFullBlock")
                && !MixinTouch.touched("net/neoforged/neoforge/common/extensions/IBlockStateExtension", "shouldCheckWeakPower")
                && !MixinTouch.touched("net/minecraft/world/level/Level", "getBlockState")
                && !MixinTouch.touched("net/minecraft/world/level/Level", "getBlockEntity")
                && !MixinTouch.touched("net/minecraft/world/level/Level", "getFluidState")
                && !MixinTouch.touched("net/minecraft/world/level/Level", "getSignal") && !MixinTouch.touched("net/minecraft/world/level/Level", "getDirectSignal")
                && !MixinTouch.touched("net/minecraft/world/level/Level", "getDirectSignalTo") && !MixinTouch.touched("net/minecraft/world/level/Level", "hasNeighborSignal")
                && !MixinTouch.touched("net/minecraft/world/level/Level", "getBestNeighborSignal");
    }

    public static String report() {
        return "signal proofs: neighbour " + provenSignal + " states proven / " + refusedSignal + " refused, direct " + provenDirect + " / " + refusedDirect;
    }
}
