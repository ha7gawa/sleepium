package dev.lull.optsupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * What each mod costs, measured where Sleepium itself makes the calls (notes/73, the inspector): every block entity's
 * tick (BeTickMixin), every entity's tick on the server and the client (the entity sleep's loops) and every entity's
 * drawing (RenderCacheMixin's wrap, on the render thread and the helper threads), timed per type while a recording
 * runs (a few seconds, started from the Mods page) - no sampling; nothing but one volatile read when not recording.
 * Types map to mods by their registry namespace.
 */
public final class ModProfile {
    private ModProfile() {}

    public static final int BE_TICK = 0, ENT_TICK = 1, CLIENT_ENT_TICK = 2, ENT_DRAW = 3, KINDS = 4;
    /** server-side kinds are per server tick, client-side ones per frame */
    private static final boolean[] SERVER_SIDE = {true, true, false, false};

    /** timing on: a recording runs or the HUD's live list is shown */
    public static volatile boolean ON;
    private static boolean recordingNow, live;
    /** the last recording's results (the Mods page), kept apart from the HUD's rolling window */
    private static List<Result> last = List.of();
    private static String lastSpan = "";
    @SuppressWarnings("unchecked")
    private static final Map<Object, LongAdder>[] BY_TYPE = new Map[KINDS];

    static {
        for (int i = 0; i < KINDS; i++) BY_TYPE[i] = new ConcurrentHashMap<>();
    }

    private static long startNs, stopAt, wallNs;
    private static volatile long ticks, frames;
    private static boolean announced = true;
    /** a recording was finished in this run (the page shows its results) */
    public static volatile boolean ready;

    public static void add(int kind, Object type, long ns) {
        BY_TYPE[kind].computeIfAbsent(type, k -> new LongAdder()).add(recordingNow ? ns : ns << 4);
    }

    private static int sampleAt;

    /**
     * Whether to time this call: always while recording (exact), one in 16 for the HUD's live list (weighted 16 in add:
     * the live list costs a sixteenth). A racy counter is fine: it only spreads the samples.
     */
    public static boolean take() {
        return recordingNow || (++sampleAt & 15) == 0;
    }

    /** a recording of this many seconds, from now */
    public static synchronized void start(int seconds) {
        for (Map<Object, LongAdder> m : BY_TYPE) m.clear();
        ticks = frames = 0;
        startNs = System.nanoTime();
        stopAt = startNs + seconds * 1_000_000_000L;
        ready = false;
        announced = false;
        recordingNow = true;
        ON = true;
        System.out.println("[sleep/profile] recording what each mod costs for " + seconds + " s");
    }

    public static boolean recording() {
        return recordingNow;
    }

    /** the HUD's live list on / off: timing stays on while it is shown */
    public static synchronized void live(boolean on) {
        if (on == live) return;
        live = on;
        if (on && !ON) {
            for (Map<Object, LongAdder> m : BY_TYPE) m.clear();
            ticks = frames = 0;
            startNs = System.nanoTime();
        }
        ON = recordingNow || live;
    }

    /** the HUD: the window since the last call (none while a recording runs: it owns the counters) */
    public static synchronized List<Result> roll() {
        if (recordingNow || !live) return null;
        List<Result> r = compute();
        for (Map<Object, LongAdder> m : BY_TYPE) m.clear();
        ticks = frames = 0;
        startNs = System.nanoTime();
        return r;
    }

    /** seconds left of the recording */
    public static int left() {
        return recordingNow ? (int) Math.max(0, (stopAt - System.nanoTime()) / 1_000_000_000L + 1) : 0;
    }

    /** server tick end (SleepCommand.onTickEnd: singleplayer and dedicated servers); true once when a recording just ended there */
    public static boolean serverTick() {
        if (ON) ticks++;
        if (recordingNow && System.nanoTime() >= stopAt) {
            finish();
            return true;
        }
        return false;
    }

    /** a frame (RenderFrameEvent.Pre) */
    public static void frame() {
        if (ON) frames++;
    }

    /** client tick: the recording's end; true once when it just finished (the caller tells the player) */
    public static synchronized boolean poll() {
        if (recordingNow && System.nanoTime() >= stopAt) finish();
        if (ready && !announced) {
            announced = true;
            return true;
        }
        return false;
    }

    private static synchronized void finish() {
        if (!recordingNow) return;
        {
            recordingNow = false;
            wallNs = System.nanoTime() - startNs;
            last = compute();
            lastSpan = String.format(java.util.Locale.ROOT, "%.0f s, %d server ticks, %d frames", wallNs / 1e9, ticks, frames);
            ready = true;
            System.out.println("[sleep/profile] done: " + lastSpan);
            for (Result r : last) if (r.total() > 0) System.out.println("[sleep/profile]   " + r.mod() + ": " + r.line());
            for (Map<Object, LongAdder> m : BY_TYPE) m.clear();   // (the HUD's window starts afresh)
            ticks = frames = 0;
            startNs = System.nanoTime();
            ON = live;
        }
    }

    /** one mod's cost: per kind (ms per tick for server kinds, ms per frame for client kinds) and its costliest types */
    public record Result(String mod, double[] ms, List<String> top) {
        public double total() {
            double t = 0;
            for (double v : ms) t += v;
            return t;
        }

        /** "server 0.82 ms/tick (blocks 0.50, mobs 0.32) · client 1.20 ms/frame (drawing 1.10)" */
        public String line() {
            List<String> parts = new ArrayList<>();
            double server = ms[BE_TICK] + ms[ENT_TICK], client = ms[CLIENT_ENT_TICK] + ms[ENT_DRAW];
            if (server >= 0.005) parts.add(String.format(java.util.Locale.ROOT, "server %.2f ms/tick (blocks %.2f, mobs %.2f)", server, ms[BE_TICK], ms[ENT_TICK]));
            if (client >= 0.005) parts.add(String.format(java.util.Locale.ROOT, "client %.2f ms/frame (drawing %.2f, ticks %.2f)", client, ms[ENT_DRAW], ms[CLIENT_ENT_TICK]));
            return parts.isEmpty() ? "under 0.01 ms" : String.join(" · ", parts);
        }

        /** "0.82 ms/t · 1.20 ms/f" */
        public String brief() {
            double server = ms[BE_TICK] + ms[ENT_TICK], client = ms[CLIENT_ENT_TICK] + ms[ENT_DRAW];
            List<String> parts = new ArrayList<>();
            if (server >= 0.005) parts.add(String.format(java.util.Locale.ROOT, "%.2f ms/tick", server));
            if (client >= 0.005) parts.add(String.format(java.util.Locale.ROOT, "%.2f ms/frame", client));
            return String.join(" · ", parts);
        }
    }

    /** the last recording's results, the costliest first */
    public static List<Result> results() {
        return last;
    }

    private static List<Result> compute() {
        Map<String, double[]> by = new HashMap<>();
        Map<String, Map<String, Double>> types = new HashMap<>();
        long t = Math.max(1, ticks), f = Math.max(1, frames);
        for (int k = 0; k < KINDS; k++) {
            for (var e : BY_TYPE[k].entrySet()) {
                String mod = dev.lull.api.Compat.namespace(e.getKey());
                if (mod.isEmpty()) mod = "?";
                double ms = e.getValue().sum() / 1e6 / (SERVER_SIDE[k] ? t : f);
                by.computeIfAbsent(mod, x -> new double[KINDS])[k] += ms;
                String name = typeName(e.getKey()) + (k == BE_TICK ? " (block)" : k == ENT_DRAW ? " (drawing)" : "");
                types.computeIfAbsent(mod, x -> new HashMap<>()).merge(name + (SERVER_SIDE[k] ? " /tick" : " /frame"), ms, Double::sum);
            }
        }
        List<Result> out = new ArrayList<>();
        for (var e : by.entrySet()) {
            List<String> top = types.getOrDefault(e.getKey(), Map.of()).entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(6)
                    .map(x -> String.format(java.util.Locale.ROOT, "%s %.3f ms%s", x.getKey().substring(0, x.getKey().lastIndexOf(' ')), x.getValue(), x.getKey().substring(x.getKey().lastIndexOf(' '))))
                    .toList();
            out.add(new Result(e.getKey(), e.getValue(), top));
        }
        out.sort((a, b) -> Double.compare(b.total(), a.total()));
        return out;
    }

    public static Result of(String mod) {
        for (Result r : results()) if (r.mod().equals(mod)) return r;
        return null;
    }

    /** "8 s, 160 server ticks, 980 frames" */
    public static String span() {
        return lastSpan;
    }

    private static String typeName(Object type) {
        net.minecraft.resources.ResourceLocation rl = type instanceof net.minecraft.world.entity.EntityType<?> et ? net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(et)
                : type instanceof net.minecraft.world.level.block.entity.BlockEntityType<?> bt ? net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(bt) : null;
        return rl == null ? String.valueOf(type) : rl.getPath();
    }
}
