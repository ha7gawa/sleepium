package dev.lull.optsupport;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * lab.opt.PostProcessStaticMixin: LevelChunk.postProcessGeneration - run on the main thread when a generated chunk
 * starts ticking, for every position the generation marked - calls Block.updateFromNeighbourShapes (six neighbour
 * reads, six updateShape calls) and then setBlock with the result. For a block whose class does not override
 * BlockBehaviour.updateShape, updateShape returns the state it is given, so the result is the state already there,
 * and setBlock with the same state changes nothing (LevelChunk.setBlockState writes the same palette value and returns
 * null before heightmaps, light, block entities or neighbours; Forge's snapshot, when captured, is added and removed).
 * The neighbour reads have no effect either: the chunk's 3x3 is FULL when it starts ticking (prepareTickingChunk waits
 * for it), so they load nothing. Skipping both for such blocks is exact; every other block runs vanilla's code.
 *
 * <p>Seen on a player flying over new terrain (1.20.1, host, a bot at 120 blocks/s): the server thread spent 72 % of its
 * time in postProcessGeneration (palette writes of unchanged states, neighbour reads); FastChunkGen's skipStaticShapes
 * does the same kind of skip.
 *
 * <p>Gives way when another mod's mixin changes updateShape, the post-processing or updateFromNeighbourShapes.
 */
public final class StaticShapes {
    private StaticShapes() {}

    private static final Method BASE;

    static {
        Method m;
        try {
            m = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findMethod(BlockBehaviour.class, "m_7417_",
                    BlockState.class, Direction.class, BlockState.class, LevelAccessor.class, BlockPos.class, BlockPos.class);
        } catch (Throwable t) {
            m = null;
        }
        BASE = m;
    }

    public static final boolean ACTIVE = BASE != null
            && !MixinTouch.touched("net/minecraft/world/level/block/state/BlockBehaviour", "updateShape")
            && !MixinTouch.touched("net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase", "updateShape")
            && !MixinTouch.touched("net/minecraft/world/level/block/Block", "updateFromNeighbourShapes")
            && !MixinTouch.touched("net/minecraft/world/level/chunk/LevelChunk", "postProcessGeneration");

    /**
     * Still water: a water source whose block, the block below and the four beside are all plain water blocks. Its tick
     * (FlowingFluid.tick: a source skips the first part, then spread) changes nothing: below, the new liquid is water
     * (the source above it) and WaterFluid.canBeReplacedWith refuses water - so no spread down; then spreadToSides
     * computes getSpread (reads) and for every direction canSpreadTo asks the neighbour's water, which refuses water
     * sideways. Every step before a write is a read of loaded chunks (the 3x3 is FULL). Oceans and aquifers mark every
     * water block for post-processing: 15 M such ticks, 30 s of the server thread in one minute of flying (1.20.1).
     */
    public static final boolean FLUIDS_ACTIVE = BASE != null
            && !MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "tick")
            && !MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "spread")
            && !MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "spreadToSides")
            && !MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "canSpreadTo")
            && !MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "getNewLiquid")
            && !MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "spreadTo")
            && !MixinTouch.touched("net/minecraft/world/level/material/WaterFluid", "canBeReplacedWith")
            && !MixinTouch.touched("net/minecraft/world/level/material/FluidState", "tick")
            && !MixinTouch.touched("net/minecraft/world/level/material/FluidState", "canBeReplacedWith");
    public static long stillSkipped, fluidsRan;

    public static boolean stillWater(net.minecraft.world.level.material.FluidState fluid, net.minecraft.world.level.Level level, BlockPos pos) {
        if (fluid.getType() != net.minecraft.world.level.material.Fluids.WATER) return false;
        net.minecraft.world.level.block.Block w = net.minecraft.world.level.block.Blocks.WATER;
        if (level.getBlockState(pos).getBlock() != w) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        if (level.getBlockState(m.setWithOffset(pos, Direction.DOWN)).getBlock() != w) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {   // beside: any block holding water - canSpreadTo stops at the water's canBeReplacedWith
            net.minecraft.world.level.material.Fluid f = level.getFluidState(m.setWithOffset(pos, d)).getType();
            if (f != net.minecraft.world.level.material.Fluids.WATER && f != net.minecraft.world.level.material.Fluids.FLOWING_WATER) return false;
        }
        return true;
    }

    /** -Dlab.postprocess.verify: skipped ticks run anyway, and any block write or scheduled tick during one is a mismatch */
    public static final boolean VERIFY = Boolean.getBoolean("lab.postprocess.verify");
    public static boolean inVerifiedTick;
    public static long verified, mismatches;

    public static void wrote() {
        if (inVerifiedTick) mismatches++;
    }

    private static final ClassValue<Boolean> STATIC = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            try {
                return c.getMethod(BASE.getName(), BASE.getParameterTypes()).getDeclaringClass() == BlockBehaviour.class;
            } catch (Throwable t) {
                return false;
            }
        }
    };

    public static long skipped, ran;
    /** -Dlab.postprocess.census: positions and nanos by block class (shape updates run) and fluid ticks, printed at exit */
    public static final boolean CENSUS = Boolean.getBoolean("lab.postprocess.census");
    public static final java.util.concurrent.ConcurrentHashMap<String, long[]> BY = new java.util.concurrent.ConcurrentHashMap<>();

    public static void count(String key, long nanos) {
        long[] a = BY.computeIfAbsent(key, k -> new long[2]);
        a[0]++;
        a[1] += nanos;
    }

    static {
        if (CENSUS) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            StringBuilder b = new StringBuilder("[sleep/opt] post-process census (" + report() + "):");
            BY.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(25)
                    .forEach(e -> b.append(String.format(java.util.Locale.ROOT, "%n   %8d x %8.1f ms  %s", e.getValue()[0], e.getValue()[1] / 1e6, e.getKey())));
            System.out.println(b);
        }));
    }

    /** updateShape of this block cannot change its state */
    public static boolean isStatic(Block b) {
        return STATIC.get(b.getClass());
    }

    public static String report() {
        return "static shapes: " + (ACTIVE ? "on" : "off (another mod changes the shape code)") + ", skipped " + skipped + ", vanilla " + ran + "; still water: " + (FLUIDS_ACTIVE ? "on" : "off") + ", skipped " + stillSkipped + ", vanilla " + fluidsRan
                + (VERIFY ? "; verified " + verified + ", mismatches " + mismatches : "");
    }
}
