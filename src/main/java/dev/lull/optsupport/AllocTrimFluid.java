package dev.lull.optsupport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * AllocTrim (lab.opt.AllocTrim): FlowingFluid.getNewLiquid's neighbour positions from one mutable position per thread
 * instead of a new BlockPos each - the neighbour is used inside one step of its loop only (the block state, the wall
 * check, the source checks). At distance 32 the fluids settling in chunk post-processing made ~6 % of all allocation
 * (1.21.1, 11-16, AllocSites). Taken only where nothing can keep the position: the fluid's class is vanilla's, no other
 * mod's mixin changes getNewLiquid or canPassThroughWall, and BlockEvent.CreateFluidSourceEvent has no listener (its
 * event carries the position; asked again only after a listener change). Otherwise vanilla's new BlockPos.
 */
public final class AllocTrimFluid {
    private AllocTrimFluid() {}

    private static final ThreadLocal<BlockPos.MutableBlockPos> NEIGHBOUR = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final java.util.Map<Class<?>, Boolean> OK = new java.util.concurrent.ConcurrentHashMap<>();
    private static Boolean untouched;
    private static volatile boolean noListener;
    private static volatile int checkedEpoch = -1;
    public static long reused, allocated;

    public static BlockPos neighbour(FlowingFluid fluid, BlockPos pos, Direction d) {
        if (!reusable(fluid)) {
            allocated++;
            return pos.relative(d);
        }
        reused++;
        return NEIGHBOUR.get().setWithOffset(pos, d);
    }

    private static boolean reusable(FlowingFluid fluid) {
        Boolean u = untouched;
        if (u == null) {
            u = !MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "getNewLiquid")
                    && !MixinTouch.touched("net/minecraft/world/level/material/FlowingFluid", "canPassThroughWall");
            untouched = u;
        }
        if (!u || !OK.computeIfAbsent(fluid.getClass(), c -> c.getName().startsWith("net.minecraft."))) return false;
        int e = EventSites.listenerEpoch;
        if (e != checkedEpoch) {   // (asked again only after a listener change: the ask itself allocates)
            noListener = net.minecraftforge.common.MinecraftForge.EVENT_BUS instanceof CompositeEventBus cb
                    && !cb.mayHaveListeners(net.minecraftforge.event.level.BlockEvent.CreateFluidSourceEvent.class);
            checkedEpoch = e;
        }
        return noListener;
    }
}
