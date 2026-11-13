package dev.lull.ui;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Sleepium's HUD (notes/73): widgets in the screen's corners, each switched and placed in the settings (HUD page) and
 * kept in config/sleepium-hud.properties (a pack can ship its own). Our measuring instrument first - frames with their
 * stutters taken apart (the client tick, GC pauses, the rest), the server's tick, GC, CPU per thread, memory, the mods
 * that cost most right now - and for players and pack makers the same.
 *
 * <p>No cost when off, little when on: each widget's data is gathered only while it is shown; GC pauses arrive as the
 * JVM's notifications (no polling); the threads' census runs once a second off the render thread; the drawing is
 * redone a few times a second and replayed from its vertices in between (HudReplay, as the F3 cache). Stutters go to
 * logs/sleepium-stutters.log while the stutter widget is shown (or -Dsleep.stutterLog=true).
 */
public final class Hud {
    private Hud() {}

    static final String[] WIDGETS = {"fps", "graph", "stutters", "server", "gc", "threads", "memory", "mods", "sleep", "world"};
    static final String[] CORNERS = {"off", "tl", "tr", "bl", "br"};
    private static final Map<String, String> CORNER = new LinkedHashMap<>();
    static int refreshMs = 250;
    private static boolean loaded;
    private static final dev.lull.optsupport.HudTexture TEXTURE = new dev.lull.optsupport.HudTexture();

    static Path file() {
        return dev.lull.optsupport.SleepConfig.file().resolveSibling("sleepium-hud.properties");
    }

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        for (String w : WIDGETS) CORNER.put(w, switch (w) {
            case "fps", "graph", "stutters", "server" -> "tl";
            default -> "off";
        });
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq < 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                if (k.startsWith("hud.") && CORNER.containsKey(k.substring(4)) && java.util.Arrays.asList(CORNERS).contains(v)) CORNER.put(k.substring(4), v);
                if (k.equals("hud.refreshMs")) refreshMs = Math.max(50, Math.min(2000, Integer.parseInt(v)));
            }
        } catch (Exception e) {
            System.out.println("[sleep/hud] " + f + ": " + e);
        }
    }

    static synchronized void save() {
        StringBuilder sb = new StringBuilder("# Sleepium's HUD: each widget's corner (off, tl, tr, bl, br) and how often it is redrawn.\n"
                + "# The settings screen (Options -> Sleepium -> HUD) writes this file; a modpack may ship its own.\n");
        for (var e : CORNER.entrySet()) sb.append("hud.").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        sb.append("hud.refreshMs=").append(refreshMs).append('\n');
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), sb, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[sleep/hud] " + file() + ": " + e);
        }
        TEXTURE.invalidate();
    }

    static String corner(String w) {
        load();
        return CORNER.getOrDefault(w, "off");
    }

    static void corner(String w, String c) {
        load();
        CORNER.put(w, c);
        save();
    }

    static boolean shown(String w) {
        return SleepStats.hud && !"off".equals(corner(w));
    }

    // ------------------------------------------------------------------ frames and stutters

    private static final int N = 600;
    private static final long[] FRAME = new long[N], TICK = new long[N], GC = new long[N], SORT = new long[N];
    private static int at, count;
    private static long frameStart, tickStart, tickInFrame, medianNs = 16_000_000L;
    private static final AtomicLong GC_PAUSE = new AtomicLong();
    private static final boolean LOG_ALWAYS = Boolean.getBoolean("sleep.stutterLog");
    /** the last stutters: when (nanoTime), total, tick, GC (ns) */
    private static final long[][] STUTTERS = new long[64][4];
    private static int stutterAt, stutterCount;

    private static boolean framesWanted() {
        return LOG_ALWAYS || shown("fps") || shown("graph") || shown("stutters");
    }

    /** a frame begins (RenderFrameEvent.Pre): the last frame's time and what was in it */
    static void frameStart() {
        if (!framesWanted()) {
            frameStart = 0;
            return;
        }
        gcListen();
        long now = System.nanoTime();
        if (frameStart != 0) {
            long d = now - frameStart, gc = GC_PAUSE.getAndSet(0);
            FRAME[at] = d;
            TICK[at] = tickInFrame;
            GC[at] = gc;
            at = (at + 1) % N;
            if (count < N) count++;
            if (count > 30 && d > Math.max(2 * medianNs, medianNs + 17_000_000L)) stutter(now, d, tickInFrame, gc);
        } else GC_PAUSE.set(0);
        frameStart = now;
        tickInFrame = 0;
    }

    static void tickStart() {
        if (frameStart != 0) tickStart = System.nanoTime();
    }

    static void tickEnd() {
        if (frameStart != 0 && tickStart != 0) tickInFrame += System.nanoTime() - tickStart;
        tickStart = 0;
    }

    private static void stutter(long now, long total, long tick, long gc) {
        long[] s = STUTTERS[stutterAt];
        s[0] = now;
        s[1] = total;
        s[2] = tick;
        s[3] = gc;
        stutterAt = (stutterAt + 1) % STUTTERS.length;
        if (stutterCount < STUTTERS.length) stutterCount++;
        if (LOG_ALWAYS || shown("stutters")) {
            String line = String.format(Locale.ROOT, "%s frame %.1f ms (median %.2f): client tick %.1f, GC %.1f, the rest %.1f; fps %d%n",
                    java.time.LocalTime.now().withNano(0), total / 1e6, medianNs / 1e6, tick / 1e6, gc / 1e6, Math.max(0, total - tick - gc) / 1e6, Minecraft.getInstance().getFps());
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    Path log = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("sleepium-stutters.log");
                    Files.writeString(log, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception ignored) {
                    // (a full disk: the HUD still shows it)
                }
            });
        }
    }

    /** p50 / p99 of the ring and its median for the stutter threshold (at each redraw) */
    private static double[] percentiles() {
        int n = Math.min(count, 240);
        if (n == 0) return new double[] {0, 0};
        for (int i = 0; i < n; i++) SORT[i] = FRAME[(at - 1 - i + N) % N];
        java.util.Arrays.sort(SORT, 0, n);
        medianNs = Math.max(1, SORT[n / 2]);
        return new double[] {SORT[n / 2] / 1e6, SORT[Math.min(n - 1, (int) (n * 0.99))] / 1e6};
    }

    // ------------------------------------------------------------------ GC (the JVM's notifications)

    private static boolean gcListening;
    private static final long[][] GCS = new long[32][2];   // when (nanoTime), pause (ns)
    private static final String[] GC_NAMES = new String[32];
    private static int gcAt, gcCount;

    private static void gcListen() {
        if (gcListening) return;
        gcListening = true;
        for (java.lang.management.GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(b instanceof javax.management.NotificationEmitter ne)) continue;
            ne.addNotificationListener((n, h) -> {
                if (!com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) return;
                var info = com.sun.management.GarbageCollectionNotificationInfo.from((javax.management.openmbean.CompositeData) n.getUserData());
                String name = info.getGcName();
                if (name.contains("Cycles") || name.contains("Concurrent")) return;   // concurrent work, not a pause
                long ns = info.getGcInfo().getDuration() * 1_000_000L;
                GC_PAUSE.addAndGet(ns);
                synchronized (GCS) {
                    GCS[gcAt][0] = System.nanoTime();
                    GCS[gcAt][1] = ns;
                    GC_NAMES[gcAt] = name.replace(" Generation", "").replace("G1 ", "G1 ").replace(" Pauses", "");
                    gcAt = (gcAt + 1) % GCS.length;
                    if (gcCount < GCS.length) gcCount++;
                }
            }, null, null);
        }
    }

    // ------------------------------------------------------------------ threads and memory (once a second, off the render thread)

    private static volatile String threadLine = "", threadLine2 = "", memoryLine = "";
    private static long censusAt;
    private static volatile boolean censusRunning;
    private static final Map<Long, Long> CPU = new HashMap<>();
    private static final Map<Long, String> NAMES = new HashMap<>();
    private static long censusWall, allocLast;

    private static void census() {
        long now = System.nanoTime();
        if (censusRunning || now - censusAt < 1_000_000_000L) return;
        censusAt = now;
        censusRunning = true;
        boolean threads = shown("threads"), memory = shown("memory");
        dev.lull.api.Pools.background().execute(() -> {
            try {
                var tmx = ManagementFactory.getThreadMXBean();
                long t = System.nanoTime(), dt = censusWall == 0 ? 0 : t - censusWall;
                censusWall = t;
                if (threads && tmx.isThreadCpuTimeSupported()) {
                    Map<String, Long> groups = new HashMap<>();
                    Map<Long, Long> seen = new HashMap<>();
                    long[] ids = tmx.getAllThreadIds();
                    List<Long> unnamed = new ArrayList<>();
                    for (long id : ids) if (!NAMES.containsKey(id)) unnamed.add(id);
                    if (!unnamed.isEmpty())   // the names of new threads only (a ThreadInfo each)
                        for (java.lang.management.ThreadInfo ti : tmx.getThreadInfo(unnamed.stream().mapToLong(Long::longValue).toArray()))
                            if (ti != null) NAMES.put(ti.getThreadId(), ti.getThreadName().replaceAll("\\d+", "N").replace("#N", "").replace(" thread", "").replace("-N", "").trim());
                    long[] cpus = tmx instanceof com.sun.management.ThreadMXBean sunT ? sunT.getThreadCpuTime(ids) : null;   // one call for all
                    for (int i = 0; i < ids.length; i++) {
                        long cpu = cpus != null ? cpus[i] : tmx.getThreadCpuTime(ids[i]);
                        if (cpu < 0) continue;
                        Long prev = CPU.get(ids[i]);
                        seen.put(ids[i], cpu);
                        if (prev == null || dt == 0) continue;
                        groups.merge(NAMES.getOrDefault(ids[i], "?"), cpu - prev, Long::sum);
                    }
                    NAMES.keySet().retainAll(seen.keySet());
                    CPU.clear();
                    CPU.putAll(seen);
                    if (dt > 0) {
                        List<String> top = new ArrayList<>();
                        groups.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(6)
                                .forEach(e -> top.add(e.getKey() + String.format(Locale.ROOT, " %.2f", e.getValue() / (double) dt)));
                        double all = groups.values().stream().mapToLong(Long::longValue).sum() / (double) dt;
                        threadLine = String.format(Locale.ROOT, "CPU %.2f cores · ", all) + String.join(" · ", top.subList(0, Math.min(3, top.size())));
                        threadLine2 = top.size() > 3 ? String.join(" · ", top.subList(3, top.size())) : "";
                    }
                }
                if (memory) {
                    Runtime rt = Runtime.getRuntime();
                    long alloc = -1;
                    if (tmx instanceof com.sun.management.ThreadMXBean sun) {
                        long a = sun.getTotalThreadAllocatedBytes();
                        if (allocLast > 0 && dt > 0) alloc = (long) ((a - allocLast) * 1e9 / dt);
                        allocLast = a;
                    }
                    memoryLine = String.format(Locale.ROOT, "Heap %d / %d MB", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20)
                            + (alloc >= 0 ? String.format(Locale.ROOT, " · allocating %d MB/s", alloc >> 20) : "");
                }
            } catch (Throwable e) {
                threadLine = "census: " + e;
            } finally {
                censusRunning = false;
            }
        });
    }

    // ------------------------------------------------------------------ the mods that cost most now

    private static List<String> modLines = List.of();
    private static long modsAt;

    private static void mods() {
        boolean want = shown("mods");
        long now = System.nanoTime();
        if (want && !dev.lull.optsupport.ModProfile.ON) modsAt = now;   // (the first window: a whole one)
        dev.lull.optsupport.ModProfile.live(want);
        if (!want) return;
        if (now - modsAt < 3_000_000_000L) return;
        modsAt = now;
        var r = dev.lull.optsupport.ModProfile.roll();
        if (r == null) return;
        List<String> out = new ArrayList<>();
        for (var m : r) {
            if (out.size() == 4 || m.total() < 0.01) break;
            out.add("  " + m.mod() + " " + m.brief());
        }
        modLines = out.isEmpty() ? List.of("  under 0.01 ms each") : out;
    }

    // ------------------------------------------------------------------ drawing

    /** -Dsleep.hud.selfTime=true: the HUD's own time per frame (draw: census + mods + replay or redraw; frame start), logged every 10 s */
    private static final boolean SELF = Boolean.getBoolean("sleep.hud.selfTime");
    private static long selfDraw, selfRedraw, selfFrames, selfRedraws, selfAt;

    /** RenderGuiEvent.Post, no screen open */
    static void draw(GuiGraphics g) {
        if (!SELF) {
            draw0(g);
            return;
        }
        long t0 = System.nanoTime();
        draw0(g);
        long t1 = System.nanoTime();
        selfDraw += t1 - t0;
        selfFrames++;
        if (selfAt == 0) selfAt = t1;
        if (t1 - selfAt > 10_000_000_000L) {
            System.out.println(String.format(Locale.ROOT, "[sleep/hud] self time: %.1f us a frame (redraws %d, %.0f us each), %d frames",
                    selfDraw / 1e3 / Math.max(1, selfFrames), selfRedraws, selfRedraw / 1e3 / Math.max(1, selfRedraws), selfFrames));
            selfDraw = selfRedraw = selfFrames = selfRedraws = 0;
            selfAt = t1;
        }
    }

    private static void draw0(GuiGraphics g) {
        if (!SleepStats.hud || Minecraft.getInstance().options.renderDebug) {   // (1.20.1: F3 is an option)
            dev.lull.optsupport.ModProfile.live(false);
            return;
        }
        load();
        census();
        mods();
        TEXTURE.render(g, refreshMs * 1_000_000L, SELF ? (x, rects) -> {
            long t = System.nanoTime();
            paint(x, rects);
            selfRedraw += System.nanoTime() - t;
            selfRedraws++;
        } : Hud::paint);
    }

    private static final int TEXT = 0xFFE6EAF5, DIM = 0xFFA8B3D0, WARN = 0xFFFFC857, BAD = 0xFFFF6B6B, GOOD = 0xFF7BE0A0, BOX = 0x90101418;
    private static final int GRAPH_W = 120, GRAPH_H = 28;

    /** a line of a widget: text and colour, or the frame graph (text null) */
    private record Item(String text, int color) {}

    private static void paint(GuiGraphics g, List<int[]> rects) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        double[] pct = percentiles();
        Map<String, List<Item>> byCorner = new LinkedHashMap<>();
        for (String c : CORNERS) byCorner.put(c, new ArrayList<>());
        for (String w : WIDGETS) {
            String c = corner(w);
            if (c.equals("off")) continue;
            List<Item> items = byCorner.get(c);
            switch (w) {
                case "fps" -> items.add(new Item(String.format(Locale.ROOT, "FPS %d · frame p50 %.2f · p99 %.2f ms", mc.getFps(), pct[0], pct[1]), TEXT));
                case "graph" -> items.add(new Item(null, 0));
                case "stutters" -> items.add(stutterItem());
                case "server" -> items.add(new Item(SleepStats.singleplayer() && SleepStats.mspt >= 0
                        ? String.format(Locale.ROOT, "Server %.2f ms/tick · TPS %.1f", SleepStats.mspt, Math.min(20.0, 1000.0 / Math.max(SleepStats.mspt, 1e-3)))
                        : "Server: not this game's", SleepStats.mspt > 50 ? BAD : SleepStats.mspt > 30 ? WARN : TEXT));
                case "gc" -> items.add(gcItem());
                case "threads" -> {
                    items.add(new Item(threadLine.isEmpty() ? "CPU: measuring..." : threadLine, DIM));
                    if (!threadLine2.isEmpty()) items.add(new Item(threadLine2, DIM));
                }
                case "memory" -> items.add(new Item(memoryLine.isEmpty() ? "Heap: measuring..." : memoryLine, DIM));
                case "mods" -> {
                    items.add(new Item(modLines.isEmpty() ? "Mods that cost most: measuring..." : "Mods that cost most (3 s):", DIM));
                    for (String l : modLines) items.add(new Item(l, WARN));
                }
                case "sleep" -> items.add(new Item(SleepStats.singleplayer() ? String.format(Locale.ROOT, "Asleep %d of %d · %d ticks skipped/s", SleepStats.asleep, SleepStats.tracked, SleepStats.skippedPerSecond) : "Asleep: not this game's", DIM));
                case "world" -> {
                    for (String s : SleepStats.world()) items.add(new Item(s, DIM));
                }
                default -> {
                }
            }
        }
        int top = dev.lull.shaders.ShaderPipeline.preparing() != null ? 14 : 2;
        for (var e : byCorner.entrySet()) {
            List<Item> items = e.getValue();
            if (items.isEmpty()) continue;
            String c = e.getKey();
            int w = 0, h = 0, max = Math.max(GRAPH_W, g.guiWidth() / 2 - 12);
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                if (it.text() != null && font.width(it.text()) > max)
                    items.set(i, it = new Item(font.plainSubstrByWidth(it.text(), max - font.width("…")) + "…", it.color()));
                w = Math.max(w, it.text() == null ? GRAPH_W : font.width(it.text()));
                h += it.text() == null ? GRAPH_H + 2 : 10;
            }
            int x = c.endsWith("l") ? 4 : g.guiWidth() - 4 - w;
            int y = c.startsWith("t") ? top + 2 : g.guiHeight() - 4 - h;
            g.fill(x - 3, y - 2, x + w + 3, y + h + 1, BOX);
            rects.add(new int[] {x - 3, y - 2, x + w + 3, y + h + 1});
            for (Item it : items) {
                if (it.text() == null) {
                    graph(g, x + (c.endsWith("r") ? w - GRAPH_W : 0), y);
                    y += GRAPH_H + 2;
                } else {
                    g.drawString(font, it.text(), c.endsWith("r") ? x + w - font.width(it.text()) : x, y, it.color(), false);
                    y += 10;
                }
            }
        }
    }

    /** the last GRAPH_W frames as bars: the height to twice the median (a bar at the top is a stutter), red for stutters, the GC part on top */
    private static void graph(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + GRAPH_W, y + GRAPH_H, 0x40000000);
        double full = Math.max(2.5 * medianNs, medianNs + 25_000_000L);
        int line = y + GRAPH_H - (int) Math.round(GRAPH_H * medianNs / full);
        int n = Math.min(count, GRAPH_W);
        for (int i = 0; i < n; i++) {
            int idx = (at - 1 - i + N) % N;
            long d = FRAME[idx];
            int bh = (int) Math.max(1, Math.min(GRAPH_H, Math.round(GRAPH_H * d / full)));
            boolean st = d > Math.max(2 * medianNs, medianNs + 17_000_000L);
            int bx = x + GRAPH_W - 1 - i;
            g.fill(bx, y + GRAPH_H - bh, bx + 1, y + GRAPH_H, st ? BAD : d > 1.5 * medianNs ? WARN : GOOD);
            if (GC[idx] > 0) {
                int gh = (int) Math.max(1, Math.min(bh, Math.round(GRAPH_H * GC[idx] / full)));
                g.fill(bx, y + GRAPH_H - bh, bx + 1, y + GRAPH_H - bh + gh, 0xFFB388FF);   // the GC pause's share, violet
            }
        }
        g.fill(x, line, x + GRAPH_W, line + 1, 0x80FFFFFF);   // the median
    }

    private static Item stutterItem() {
        long now = System.nanoTime();
        int perMin = 0;
        long[] last = null;
        for (int i = 0; i < stutterCount; i++) {
            long[] s = STUTTERS[(stutterAt - 1 - i + STUTTERS.length) % STUTTERS.length];
            if (now - s[0] > 60_000_000_000L) break;
            perMin++;
            if (last == null) last = s;
        }
        if (last == null) return new Item("Stutters: none in a minute", GOOD);
        long ago = (now - last[0]) / 1_000_000_000L;
        long rest = Math.max(0, last[1] - last[2] - last[3]);
        String biggest = last[2] >= last[3] && last[2] >= rest ? "client tick" : last[3] >= rest ? "GC" : "drawing / other";
        return new Item(String.format(Locale.ROOT, "Stutters %d/min · last %.0f ms %ds ago: %s (tick %.0f, GC %.0f, rest %.0f)", perMin, last[1] / 1e6, ago,
                biggest, last[2] / 1e6, last[3] / 1e6, rest / 1e6), perMin > 5 ? BAD : WARN);
    }

    private static Item gcItem() {
        gcListen();
        long now = System.nanoTime();
        int n = 0;
        long sum = 0;
        String lastName = null;
        long lastNs = 0, lastAt = 0;
        synchronized (GCS) {
            for (int i = 0; i < gcCount; i++) {
                int idx = (gcAt - 1 - i + GCS.length) % GCS.length;
                if (now - GCS[idx][0] > 60_000_000_000L) break;
                n++;
                sum += GCS[idx][1];
                if (lastName == null) {
                    lastName = GC_NAMES[idx];
                    lastNs = GCS[idx][1];
                    lastAt = GCS[idx][0];
                }
            }
        }
        if (n == 0) return new Item("GC: no pauses in a minute", GOOD);
        return new Item(String.format(Locale.ROOT, "GC %d/min, %.0f ms · last %.1f ms %ds ago (%s)", n, sum / 1e6, lastNs / 1e6, (now - lastAt) / 1_000_000_000L, lastName),
                lastNs > 30_000_000L ? BAD : lastNs > 10_000_000L ? WARN : DIM);
    }
}
