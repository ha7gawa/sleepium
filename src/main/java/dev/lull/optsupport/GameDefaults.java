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
     * -Dlab.decoupled=true (1.3.0 research, non-exact - a decision of 11-15; port of 1.21.1's preset): the frame on its
     * own thread drawing while the main thread ticks, extra frames during a long tick, entities ticked on the pool after
     * the tick and drawn from tick-rate poses, the frames-in-flight limiter, the partial tick from the tick's end, a ring
     * of 3 recorded frames, and the pipeline's stages 1-3 (notes/75: the tick's generations, published lists, the view
     * latched at every frame, self-clocked frames). Not here (1.21.1 only so far): the one pool, the leaf cull. Each flag
     * can still be given on its own (a given value wins).
     */
    private static void decoupledPreset() {
        if (!Boolean.getBoolean("lab.decoupled")) return;
        // (the pipeline's stages are the frame thread's own defaults since 11-17)
        String[] set = {"lab.opt.FrameThreadMixin=true",
                "lab.opt.TickAsyncMixin=true", "lab.prender.tickPoses=true", "lab.pentities.living=true", "lab.partbatch.itemsAnyOrder=true",
                "lab.prender.tickPoses.tolerance=0.1",
                "lab.gpu.framesInFlight.auto=true", "lab.tickAsync.graph=true"};
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

    /**
     * -Dlab.specTicks=true (1.3.6, exact): the server's entity ticks in parallel in one switch - SpecTicks (speculative
     * ticks on the pool, each checked and committed in vanilla's order, undone and run again where they met; adaptive
     * blocks: dense crowds tick plainly), the write barrier its transactions stand on, the item runs and the tracker's
     * per-entity work on the pool (TrackPar). The world stays exactly vanilla's (production suites on four machines,
     * notes/99). Each flag can still be given on its own (a given value wins).
     */
    private static void specPreset() {
        if (!Boolean.getBoolean("lab.specTicks")) return;
        String[] set = {"lab.opt.ServerParallelItemsMixin=true", "lab.opt.WriteBarrier=true", "lab.spec=true", "lab.spec.margin=0.3",
                "lab.opt.TrackParMixin=true"};
        int n = 0;
        for (String kv : set) {
            int eq = kv.indexOf('=');
            if (System.getProperty(kv.substring(0, eq)) == null) {
                System.setProperty(kv.substring(0, eq), kv.substring(eq + 1));
                n++;
            }
        }
        System.out.println("[sleep/opt] parallel entity ticks (lab.specTicks): " + n + " flags set");
    }

    public static synchronized void ensure() {
        if (done) return;
        done = true;
        SleepConfig.load();   // the player's settings first: they win over the defaults
        decoupledPreset();
        specPreset();
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
            prod = net.minecraftforge.fml.loading.FMLLoader.isProduction();
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
