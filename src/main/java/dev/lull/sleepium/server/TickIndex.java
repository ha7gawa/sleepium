package dev.lull.sleepium.server;

import dev.lull.sleepium.Sleepium;
import java.util.List;
import net.minecraft.world.NextTickListEntry;

/** IndexedTickSet's switch, verify mode (-Dsleep.opt.TickIndex.verify: vanilla's walk too, the two answers compared) and counters. */
public final class TickIndex {
    private TickIndex() {}

    public static final boolean ON = !"false".equals(System.getProperty("sleep.opt.TickIndex"));
    public static final boolean VERIFY = Boolean.getBoolean("sleep.opt.TickIndex.verify");
    public static long ANSWERED, verified, mismatches;

    static {
        if (VERIFY) Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("[sleep] " + report())));
    }

    public static void compare(List<NextTickListEntry> vanilla, List<NextTickListEntry> ours) {
        verified++;
        int a = vanilla == null ? 0 : vanilla.size(), b = ours == null ? 0 : ours.size();
        boolean same = a == b;
        for (int i = 0; same && i < a; i++) same = vanilla.get(i) == ours.get(i);
        if (!same && mismatches++ < 5) Sleepium.LOGGER.warn("[sleep] tick index: answer differs ({} vanilla, {} ours)", a, b);
    }

    public static String report() {
        return "tick index: " + (ON ? "on" : "off") + ", answered " + ANSWERED + (VERIFY ? ", verified " + verified + ", mismatches " + mismatches : "");
    }
}
