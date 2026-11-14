package dev.lull.optsupport;

/**
 * Experiment: the client's ticks paced over frames (stutters phase 2, the step before decoupling the client tick from
 * the frame). Vanilla runs every tick due in this frame, up to 10: after one slow frame the next one runs several ticks
 * and is slow too. Here a frame runs at most {@code lab.tick.pace} ticks (1); the rest is a debt run in the next frames,
 * a little faster the larger it grows (cap + debt / 4), so a slow device still keeps 20 ticks a second on average.
 * Beyond 10 owed ticks the rest is dropped, as vanilla drops them. Client side only: the server stays authoritative.
 */
public final class TickPace {
    private TickPace() {}

    private static final int CAP = Math.max(1, Integer.getInteger("lab.tick.pace", 1));
    private static int debt;
    public static long frames, burstsAvoided, maxDebt;

    /** render thread, once a frame: how many of the ticks due (plus the debt) run now */
    public static int ticksNow(int due) {
        int owed = Math.min(10, debt + due);
        int run = Math.min(owed, CAP + debt / 4);
        debt = owed - run;
        frames++;
        if (due > run) burstsAvoided++;
        maxDebt = Math.max(maxDebt, debt);
        return run;
    }
}
