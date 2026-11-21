package dev.lull.optsupport;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** -Dlab.featcpu.verify=true: WorldgenRegionBlockMixin's remembered-section answers against vanilla's lookup. */
public final class RegionBlockVerify {
    public static final boolean ON = Boolean.getBoolean("lab.featcpu.verify");
    private static final AtomicLong CHECKS = new AtomicLong(), BAD = new AtomicLong();

    private RegionBlockVerify() {}

    public static void check(BlockState ours, BlockState vanilla, BlockPos pos) {
        long c = CHECKS.incrementAndGet();
        if (ours != vanilla) {
            long b = BAD.incrementAndGet();
            if (b <= 20) System.out.println("[sleep/featcpu] REGION BLOCK MISMATCH at " + pos + ": vanilla " + vanilla + " ours " + ours);
        }
        if ((c & ((1 << 22) - 1)) == 0) System.out.println("[sleep/featcpu] " + summary());
    }

    public static String summary() {
        return "region block reads " + CHECKS.get() + " checked, " + BAD.get() + " mismatches";
    }
}
