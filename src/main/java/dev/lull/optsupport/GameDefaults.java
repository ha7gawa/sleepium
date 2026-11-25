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
     * -Dlab.decoupled=true (1.3.0 research, non-exact - a decision of 11-15): the decoupled mode in one switch - the
     * frame on its own thread drawing while the main thread ticks, extra frames during a long tick (the camera turning
     * with the cursor), entities ticked on the pool after the tick and drawn from tick-rate poses (held items and
     * translucent / additive layers in any order, the pose check's tolerance 0.1 blocks), no leaf faces deep in
     * canopies, the frames-in-flight limiter (Intel's driver queues far ahead: this jar's own per-frame buffers stalled
     * the N100 for 700-850 ms with Distant Horizons without it). The clouds at half resolution are no longer in it (the
     * user 11-16: blurry; -Dlab.clouds.half=true for weak GPUs). Each flag can still be given on its own (a given value
     * wins). A ring of 3 recorded frames (11-16: the main thread records the next frame while the frame thread draws -
     * N100 tour 427 -> 471 fps uncapped, p99 18.4 -> 16.3 ms at 120 fps). The pipeline's stages 1-3 (notes/75, 11-16
     * evening, a hand test: "very smooth"): the tick's generations, published lists, the view latched at every
     * frame, self-clocked frames (N100 tour at 120 fps p99 15.6 -> 12.2 ms, stutters halved).
     */
    private static void decoupledPreset() {
        if (!Boolean.getBoolean("lab.decoupled")) return;
        // (the pipeline's stages - overlap, extra frames and their turn, the tick-end partial tick, the ring of 3,
        // generations, published lists, the latch, self-clocked frames - are the frame thread's own defaults since 11-17)
        String[] set = {"lab.opt.FrameThreadMixin=true",
                "lab.opt.TickAsyncMixin=true", "lab.prender.tickPoses=true", "lab.pentities.living=true", "lab.partbatch.itemsAnyOrder=true",
                "lab.prender.tickPoses.tolerance=0.1", "lab.opt.OnePool=true", "lab.leaves.cull=2",
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
        if (!Boolean.getBoolean("lab.specTicks") && !Boolean.getBoolean("lab.fast")) return;   // (lab.fast: the same parts, ticked without transactions)
        // (lab.fast: no write barrier - nothing is undone; its hooks on every field write and random draw cost x92 pigs30k
        // 8 % of the parallel phase, 93 -> 86 ms)
        String[] set = Boolean.getBoolean("lab.fast")
                ? new String[] {"lab.opt.ServerParallelItemsMixin=true", "lab.spec=true", "lab.spec.margin=0.3", "lab.opt.TrackParMixin=true"}
                : new String[] {"lab.opt.ServerParallelItemsMixin=true", "lab.opt.WriteBarrier=true", "lab.spec=true", "lab.spec.margin=0.3",
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
        if (Boolean.getBoolean("lab.fast")) fastPreset();
    }

    /**
     * lab.fast (Maximum speed, not exact) beyond the parallel ticks: the server's pool on every core but one (two with a client;
     * the exact modes keep min(4, cores - 2): host pigs30k 4 workers 84.6 ms, 14 workers 49.8 ms - 20 TPS), the view edge with hysteresis (lab.hysteresis=2: a
     * chunk leaving the view by less than 2 chunks stays - zoo orbit at 32: chunks sent 1567 -> 1127, CPU -26 %; the
     * server side only, vanilla clients need nothing for K <= 2). A given value wins.
     */
    private static void fastPreset() {
        int cpus = Runtime.getRuntime().availableProcessors();
        // (a game with its own client: the render thread keeps a core too) - asked as a resource, never loaded: loading
        // Minecraft here (even uninitialised) loads its superclasses before the mixins are applied (1.4.1: a client with
        // Maximum speed on stopped at start, MixinTargetAlreadyLoadedException on BlockableEventLoop)
        boolean client = GameDefaults.class.getClassLoader().getResource("net/minecraft/client/Minecraft.class") != null;
        java.util.List<String> want = new java.util.ArrayList<>(java.util.List.of("lab.sreactor.threads=" + Math.max(1, cpus - (client ? 2 : 1)),
                "lab.hysteresis=2", "lab.opt.LagFairMove=true"));
        // the client's built non-exact parts (1.3.x research, the decoupled preset's frame-independent ones): no leaf faces
        // deep in canopies (N100 cutout -27 %), items' translucent layers in any order, living client entities ticked on
        // the workers, tick-rate poses (tolerance 0.1 block), the render cache's replays on the workers;
        // -Dlab.fast.client=true only: measured as a set on the host (client_entities, no decoupled frames) it cost fps -
        // 2050 / 2031 -> 1469 / 1298, CPU 2.2 -> 4.2 cores (built for the decoupled frame thread)
        if (Boolean.getBoolean("lab.fast.client"))
            want.addAll(java.util.List.of("lab.leaves.cull=2", "lab.partbatch.itemsAnyOrder=true", "lab.pentities.living=true",
                    "lab.prender.tickPoses=true", "lab.prender.tickPoses.tolerance=0.1", "lab.prender.cachedOnWorkers=true"));
        String[] set = want.toArray(new String[0]);
        java.util.List<String> applied = new java.util.ArrayList<>();
        for (String kv : set) {
            int eq = kv.indexOf('=');
            if (System.getProperty(kv.substring(0, eq)) == null) {
                System.setProperty(kv.substring(0, eq), kv.substring(eq + 1));
                applied.add(kv);
            }
        }
        System.out.println("[sleep/opt] Maximum speed (lab.fast): " + applied.size() + " more flags set " + applied);
    }

    public static synchronized void ensure() {
        if (done) return;
        done = true;
        SleepConfig.load();   // the player's settings first: they win over the defaults
        Cores.apply();   // the cores budget (sleepium-cores.properties): before the presets size the pools
        decoupledPreset();
        specPreset();
        try {
            defaults();
        } finally {
            SleepConfig.started();
            Cores.started();
        }
    }

    private static void defaults() {
        String opt = System.getProperty("lab.opt.defaults");
        if ("false".equals(opt)) return;
        boolean prod;
        try {
            prod = dev.lull.Loader.production();
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
