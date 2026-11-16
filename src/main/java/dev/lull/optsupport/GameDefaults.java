package dev.lull.optsupport;

/**
 * The engine's optimisations on by default in a built jar (10-04: a friend's launcher sets no -D flags, and the game must
 * run on the engine as it does here). In production - or with {@code -Dlab.opt.defaults=true} - every flag of
 * {@code sleep/defaults.txt} (the jar's copy of sleep/defaults.txt, the set the engine is tested with) is set unless
 * already given: {@code -Dlab.opt.X=false} still turns one off, {@code -Dlab.opt.defaults=false} all of them. Dev runs
 * are untouched (their flags come from run-client-direct.sh; OPT=0 stays the plain engine for A/B).
 * <p>
 * Called first by every mixin config plugin (whichever Mixin loads first), before any flag is read.
 */
public final class GameDefaults {
    private GameDefaults() {}

    private static boolean done;

    /**
     * -Dlab.decoupled=true (1.3.0 research, non-exact - the user's call 11-15): the decoupled mode in one switch - the
     * frame on its own thread drawing while the main thread ticks, extra frames during a long tick (the camera turning
     * with the cursor), entities ticked on the pool after the tick and drawn from tick-rate poses (held items and
     * translucent / additive layers in any order, the pose check's tolerance 0.1 blocks), no leaf faces deep in
     * canopies, the frames-in-flight limiter (Intel's driver queues far ahead: this jar's own per-frame buffers stalled
     * the N100 for 700-850 ms with Distant Horizons without it). The clouds at half resolution are no longer in it (the
     * user 11-16: blurry; -Dlab.clouds.half=true for weak GPUs). Each flag can still be given on its own (a given value
     * wins).
     */
    private static void decoupledPreset() {
        if (!Boolean.getBoolean("lab.decoupled")) return;
        String[] set = {"lab.opt.FrameThreadMixin=true", "lab.frameThread.overlap=true", "lab.frameThread.extra=true", "lab.frameThread.extraTurn=true",
                "lab.opt.TickAsyncMixin=true", "lab.prender.tickPoses=true", "lab.pentities.living=true", "lab.partbatch.itemsAnyOrder=true",
                "lab.prender.tickPoses.tolerance=0.1", "lab.opt.OnePool=true", "lab.leaves.cull=2",
                "lab.gpu.framesInFlight.auto=true", "lab.frameThread.ptFromTickEnd=true"};
        int n = 0;
        for (String kv : set) {
            int eq = kv.indexOf('=');
            if (System.getProperty(kv.substring(0, eq)) == null) {
                System.setProperty(kv.substring(0, eq), kv.substring(eq + 1));
                n++;
            }
        }
        System.out.println("[sleep/opt] decoupled mode (lab.decoupled): " + n + " flags set");
    }

    public static synchronized void ensure() {
        if (done) return;
        done = true;
        SleepConfig.load();   // the player's settings first: they win over the defaults
        decoupledPreset();
        try {
            defaults();
        } finally {
            SleepConfig.started();
        }
    }

    private static void defaults() {
        String opt = System.getProperty("lab.opt.defaults");
        if ("false".equals(opt)) return;
        boolean prod;
        try {
            prod = net.neoforged.fml.loading.FMLLoader.isProduction();
        } catch (Throwable t) {
            prod = false;
        }
        if (!prod && !"true".equals(opt)) return;
        try (var in = GameDefaults.class.getResourceAsStream("/sleepium/defaults.txt")) {
            if (in == null) {
                System.out.println("[sleep/opt] defaults: sleep/defaults.txt missing from the jar");
                return;
            }
            int set = 0, kept = 0;
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                line = line.trim();
                if (!line.startsWith("-D") || line.indexOf('=') < 0) continue;
                String key = line.substring(2, line.indexOf('=')), value = line.substring(line.indexOf('=') + 1);
                if (System.getProperty(key) != null) {
                    kept++;
                    continue;
                }
                System.setProperty(key, value);
                set++;
            }
            System.out.println("[sleep/opt] defaults (" + (prod ? "production" : "lab.opt.defaults") + "): " + set + " flags on, " + kept + " given");
        } catch (java.io.IOException e) {
            System.out.println("[sleep/opt] defaults: " + e);
        }
    }
}
