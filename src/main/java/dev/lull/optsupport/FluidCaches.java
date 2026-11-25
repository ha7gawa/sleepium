package dev.lull.optsupport;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Server-thread caches for FluidSpreadMixin.
 * <ul>
 *   <li>Per getSpread call: vanilla's Short2ObjectMap / Short2BooleanMap as arrays over the 16-bit cache key, valid
 *       for the current generation only (a new call = a new generation, nothing to clear).</li>
 *   <li>Occlusion: a direct-mapped table (state, state, direction) -> answer; a collision just overwrites.</li>
 * </ul>
 */
public final class FluidCaches {
    private static final FluidCaches INSTANCE = new FluidCaches();
    private static boolean busy;
    private static final int OCC = 4096;
    private static final Object[] occA = new Object[OCC], occB = new Object[OCC];
    private static final byte[] occV = new byte[OCC];   // bits 0-2 direction + 1 (0 = empty), bit 3 answer

    private final int[] stateGen = new int[65536], holeGen = new int[65536];
    private final Object[] states = new Object[65536], fluids = new Object[65536];
    private final boolean[] holes = new boolean[65536];
    private int gen;

    private static final ClassValue<Boolean> FAST = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            if (MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "getSpread")
                    || MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "getSlopeDistance")) return false;
            for (Class<?> k = c; k != null && k != FlowingFluid.class; k = k.getSuperclass()) {
                for (var m : k.getDeclaredMethods()) {
                    if (m.getName().equals("getSpread") || m.getName().equals("getSlopeDistance")) return false;
                }
            }
            return true;
        }
    };

    private FluidCaches() {}

    public static boolean onServerThread() {
        var server = dev.lull.Loader.server;
        return server != null && Thread.currentThread() == server.getRunningThread();
    }

    /** The fluid class keeps vanilla's getSpread and getSlopeDistance (so the rewritten search is its search). */
    public static boolean fastSpread(Class<?> fluidClass) {
        return FAST.get(fluidClass);
    }

    /** Start a getSpread call; null if one is already running (nested: use vanilla). */
    public static FluidCaches begin() {
        if (busy) return null;
        busy = true;
        FluidCaches c = INSTANCE;
        if (++c.gen == 0) {   // wrapped: reset the stamps once every 2^32 calls
            java.util.Arrays.fill(c.stateGen, 0);
            java.util.Arrays.fill(c.holeGen, 0);
            c.gen = 1;
        }
        return c;
    }

    public static void end() {
        busy = false;
    }

    public boolean hasState(int key) { return stateGen[key] == gen; }
    public void putState(int key, BlockState s, FluidState f) { stateGen[key] = gen; states[key] = s; fluids[key] = f; }
    public BlockState state(int key) { return (BlockState) states[key]; }
    public FluidState fluid(int key) { return (FluidState) fluids[key]; }
    public boolean hasHole(int key) { return holeGen[key] == gen; }
    public void putHole(int key, boolean v) { holeGen[key] = gen; holes[key] = v; }
    public boolean hole(int key) { return holes[key]; }

    private static int slot(Object a, Object b, Direction d) {
        int h = System.identityHashCode(a) * 31 + System.identityHashCode(b) * 7 + d.ordinal();
        h ^= h >>> 16;
        return h & (OCC - 1);
    }

    /** -1 unknown, else 0/1 */
    public static int occlusion(BlockState a, BlockState b, Direction d) {
        int s = slot(a, b, d);
        byte v = occV[s];
        if ((v & 7) == d.ordinal() + 1 && occA[s] == a && occB[s] == b) return (v >> 3) & 1;
        return -1;
    }

    public static void putOcclusion(BlockState a, BlockState b, Direction d, boolean passes) {
        int s = slot(a, b, d);
        occA[s] = a;
        occB[s] = b;
        occV[s] = (byte) ((d.ordinal() + 1) | (passes ? 8 : 0));
    }
}
