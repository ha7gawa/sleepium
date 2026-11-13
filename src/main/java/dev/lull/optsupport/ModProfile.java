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

    public static final int BE_TICK = 0, ENT_TICK = 1, CLIENT_ENT_TICK = 2, ENT_DRAW = 3, SERVER_EVENT = 4, CLIENT_EVENT = 5, KINDS = 6;
    /** server-side kinds are per server tick, client-side ones per frame */
    private static final boolean[] SERVER_SIDE = {true, true, false, false, true, false};
    private static volatile Thread serverThread;

    /**
     * An event listener's call (CompositeEventBus while timing): its mod (the module of the class it calls into) and the
     * event's class; on the server thread per tick, elsewhere per frame.
     */
    public static void listener(Object listener, Object event, long ns) {
        String mod = OWNERS.computeIfAbsent(listener, ModProfile::ownerOf);
        add(Thread.currentThread() == serverThread ? SERVER_EVENT : CLIENT_EVENT, mod + "|" + event.getClass().getSimpleName(), ns);
    }

    private static final Map<Object, String> OWNERS = new ConcurrentHashMap<>();
    private static volatile Map<String, String> MODULE_MOD;

    /** a listener object -> the mod whose code it calls: the first field (its consumer, handler, instance...) in a mod's module */
    private static String ownerOf(Object listener) {
        java.util.ArrayDeque<Object> todo = new java.util.ArrayDeque<>();
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        todo.add(listener);
        while (!todo.isEmpty() && seen.size() < 12) {
            Object o = todo.poll();
            if (!seen.add(o)) continue;
            String mod = modOf(o.getClass());
            if (mod != null && o != listener) return mod;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v != null && !(v instanceof String) && !(v instanceof Class<?>)) todo.add(v);
                    } catch (Throwable t) {
                        // (a field the module system keeps closed)
                    }
                }
        }
        String own = modOf(listener.getClass());
        return own != null ? own : "?";
    }

    /** a class's mod: its module's mod file (null: the loader's or the JDK's own classes) */
    private static String modOf(Class<?> c) {
        Map<String, String> m = MODULE_MOD;
        if (m == null) {
            m = new HashMap<>();
            try {
                for (var info : net.neoforged.fml.loading.LoadingModList.get().getModFiles())
                    if (!info.getMods().isEmpty()) m.put(info.moduleName(), info.getMods().get(0).getModId());
            } catch (Throwable t) {
                // (tests: no loader)
            }
            MODULE_MOD = m;
        }
        String module = c.getModule().getName();
        return module == null ? null : m.get(module);
    }

    /**
     * Timing on for this server tick / this frame: always while a recording runs (exact); for the HUD's live list every 4th
     * 16th tick and frame only (switched at their boundaries - the other ticks and frames cost one volatile read; the
     * results are per timed tick / frame, so the sample is unbiased without weights).
     */
    public static volatile boolean ON_SERVER, ON_CLIENT;
    private static int serverPhase, framePhase;

    /** a recording runs or the live list is shown */
    public static boolean active() {
        return recordingNow || live;
    }

    /** an event post: timed when this thread's side is timed now */
    public static boolean timingHere() {
        return Thread.currentThread() == serverThread ? ON_SERVER : ON_CLIENT;
    }
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
        BY_TYPE[kind].computeIfAbsent(type, k -> new LongAdder()).add(ns);
    }

    /** where the server's time went: dimension -> chunk -> type -> ns (block entity and entity ticks) */
    private static final Map<Object, Map<Long, Map<Object, LongAdder>>> SPOTS = new ConcurrentHashMap<>();

    /** a block entity's or entity's tick in this chunk (alongside add) */
    public static void at(Object dimension, long chunk, Object type, long ns) {
        SPOTS.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>()).computeIfAbsent(chunk, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, k -> new LongAdder()).add(ns);
    }

    /** a hot spot of the last recording: where, its ms per tick, what it is made of */
    public record Spot(String dimension, int chunkX, int chunkZ, double ms, String what) {
        /** "overworld x 192 z -64: 3.20 ms/tick - 40 furnace, 2 hopper" */
        public String line() {
            return String.format(java.util.Locale.ROOT, "%s x %d z %d: %.2f ms/tick - %s", dimension, chunkX * 16 + 8, chunkZ * 16 + 8, ms, what);
        }
    }

    private static List<Spot> lastSpots = List.of();

    /** the last recording's costliest chunks */
    public static List<Spot> spots() {
        return lastSpots;
    }

    private static List<Spot> computeSpots() {
        long t = Math.max(1, ticks);
        List<Spot> out = new ArrayList<>();
        for (var d : SPOTS.entrySet()) {
            String dim = d.getKey() instanceof net.minecraft.resources.ResourceKey<?> rk ? rk.location().getPath() : String.valueOf(d.getKey());
            for (var c : d.getValue().entrySet()) {
                double total = 0;
                List<Map.Entry<Object, LongAdder>> parts = new ArrayList<>(c.getValue().entrySet());
                for (var p : parts) total += p.getValue().sum();
                parts.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
                StringBuilder what = new StringBuilder();
                for (int i = 0; i < Math.min(3, parts.size()); i++) {
                    if (i > 0) what.append(", ");
                    Object type = parts.get(i).getKey();
                    String ns = dev.lull.api.Compat.namespace(type);
                    what.append(typeName(type)).append(ns.isEmpty() || ns.equals("minecraft") ? "" : " (" + ns + ")")
                            .append(String.format(java.util.Locale.ROOT, " %.2f", parts.get(i).getValue().sum() / 1e6 / t));
                }
                out.add(new Spot(dim, net.minecraft.world.level.ChunkPos.getX(c.getKey()), net.minecraft.world.level.ChunkPos.getZ(c.getKey()), total / 1e6 / t, what.toString()));
            }
        }
        out.sort((a, b) -> Double.compare(b.ms(), a.ms()));
        return out.size() > 10 ? new ArrayList<>(out.subList(0, 10)) : out;
    }

    /** a recording of this many seconds, from now */
    public static synchronized void start(int seconds) {
        for (Map<Object, LongAdder> m : BY_TYPE) m.clear();
        SPOTS.clear();
        ticks = frames = 0;
        startNs = System.nanoTime();
        stopAt = startNs + seconds * 1_000_000_000L;
        ready = false;
        announced = false;
        recordingNow = true;
        ON_SERVER = ON_CLIENT = true;
        System.out.println("[sleep/profile] recording what each mod costs for " + seconds + " s");
    }

    public static boolean recording() {
        return recordingNow;
    }

    /** the HUD's live list on / off: timing stays on while it is shown */
    public static synchronized void live(boolean on) {
        if (on == live) return;
        live = on;
        if (on && !recordingNow) {
            for (Map<Object, LongAdder> m : BY_TYPE) m.clear();
            ticks = frames = 0;
            startNs = System.nanoTime();
        }
        if (!recordingNow) ON_SERVER = ON_CLIENT = false;   // (the live sample starts at the next boundaries)
    }

    /** the HUD: the window since the last call (none while a recording runs: it owns the counters) */
    public static synchronized List<Result> roll() {
        if (recordingNow || !live) return null;
        List<Result> r = compute();
        for (Map<Object, LongAdder> m : BY_TYPE) m.clear();
        SPOTS.clear();
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
        serverThread = Thread.currentThread();
        if (ON_SERVER) ticks++;   // the tick that just ended was timed
        if (recordingNow) {
            if (System.nanoTime() >= stopAt) {
                finish();
                return true;
            }
            return false;
        }
        ON_SERVER = live && (++serverPhase & 3) == 0;   // (a server tick timed whole is cheap: every 4th - 15 a window)
        return false;
    }

    /** a frame (RenderFrameEvent.Pre) */
    public static void frame() {
        if (ON_CLIENT) frames++;   // the frame that just ended was timed
        if (!recordingNow) ON_CLIENT = live && (++framePhase & 15) == 0;
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
            lastSpots = computeSpots();
            lastSpan = String.format(java.util.Locale.ROOT, "%.0f s, %d server ticks, %d frames", wallNs / 1e9, ticks, frames);
            ready = true;
            System.out.println("[sleep/profile] done: " + lastSpan);
            for (Result r : last) if (r.total() > 0) System.out.println("[sleep/profile]   " + r.mod() + ": " + r.line());
            for (Map<Object, LongAdder> m : BY_TYPE) m.clear();   // (the HUD's window starts afresh)
            SPOTS.clear();
            for (Spot s : lastSpots) System.out.println("[sleep/profile]   hot spot " + s.line());
            ticks = frames = 0;
            startNs = System.nanoTime();
            ON_SERVER = ON_CLIENT = false;   // (the live sample, if shown, starts at the next boundaries)
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
            double server = ms[BE_TICK] + ms[ENT_TICK] + ms[SERVER_EVENT], client = ms[CLIENT_ENT_TICK] + ms[ENT_DRAW] + ms[CLIENT_EVENT];
            if (server >= 0.005) parts.add(String.format(java.util.Locale.ROOT, "server %.2f ms/tick (blocks %.2f, entities %.2f, events %.2f)", server, ms[BE_TICK], ms[ENT_TICK], ms[SERVER_EVENT]));
            if (client >= 0.005) parts.add(String.format(java.util.Locale.ROOT, "client %.2f ms/frame (drawing %.2f, ticks %.2f, events %.2f)", client, ms[ENT_DRAW], ms[CLIENT_ENT_TICK], ms[CLIENT_EVENT]));
            return parts.isEmpty() ? "under 0.01 ms" : String.join(" · ", parts);
        }

        /** "0.82 ms/t · 1.20 ms/f" */
        public String brief() {
            double server = ms[BE_TICK] + ms[ENT_TICK] + ms[SERVER_EVENT], client = ms[CLIENT_ENT_TICK] + ms[ENT_DRAW] + ms[CLIENT_EVENT];
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
                String mod = e.getKey() instanceof String s ? s.substring(0, s.indexOf('|')) : dev.lull.api.Compat.namespace(e.getKey());
                if (mod.isEmpty()) mod = "?";
                double ms = e.getValue().sum() / 1e6 / (SERVER_SIDE[k] ? t : f);
                by.computeIfAbsent(mod, x -> new double[KINDS])[k] += ms;
                String name = e.getKey() instanceof String s ? s.substring(s.indexOf('|') + 1) + " (event)"
                        : typeName(e.getKey()) + (k == BE_TICK ? " (block)" : k == ENT_DRAW ? " (drawing)" : "");
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
