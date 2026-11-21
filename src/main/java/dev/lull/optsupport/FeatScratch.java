package dev.lull.optsupport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** WorldgenFeatureAlloc: small per-thread temporaries of feature code (see the mixins that use them). */
public final class FeatScratch {
    /** Direction.values(), never written (callers only read it) */
    public static final Direction[] DIRECTIONS = Direction.values();
    private static final ThreadLocal<BlockPos.MutableBlockPos> POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private FeatScratch() {}

    /** this thread's scratch position: valid until the next pos() on this thread */
    public static BlockPos.MutableBlockPos pos() {
        return POS.get();
    }
}
