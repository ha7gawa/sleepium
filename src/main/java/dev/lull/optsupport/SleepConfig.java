package dev.lull.optsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The player's settings (the settings screen): config/sleepium.properties, {@code key=value} per engine flag the player
 * changed. Applied by GameDefaults before the defaults and before any flag is read: a launch flag ({@code -Dkey=...})
 * still wins over the file, the file over the defaults. Most flags are read once at start (mixins apply when their
 * class loads): the screen says when a change needs a restart.
 *
 * <p>The file documents itself: on the first start (a dedicated server too: it has no settings screen) and on every save
 * it lists every switch of the catalogue (options.tsv) by group with its description and default, commented out unless
 * set - a server owner edits it like any config. An existing file is only rewritten by the screen's save.
 */
public final class SleepConfig {
    private SleepConfig() {}

    private static final Map<String, String> FILE = new TreeMap<>();
    /** engine keys given on the command line (the screen shows them locked) */
    private static final Set<String> LAUNCH = new HashSet<>();
    /** the value of each engine key when the game started (a difference needs a restart) */
    private static final Map<String, String> STARTUP = new TreeMap<>();
    private static boolean loaded;

    public static Path file() {
        Path dir;
        try {
            dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        } catch (Throwable t) {
            dir = Path.of("config");
        }
        return dir.resolve("sleepium.properties");
    }

    static boolean engineKey(String k) {
        return k.startsWith("lab.") || k.startsWith("sleep.");
    }

    /** the pack's file (a modpack ships it; Sleepium only reads it): config/sleepium-pack.properties */
    public static Path packFile() {
        return file().resolveSibling("sleepium-pack.properties");
    }

    /** the pack's values; its keys the player cannot change ({@code lock.<key>=true}) */
    private static final Map<String, String> PACK = new TreeMap<>();
    private static final Set<String> LOCKED = new HashSet<>();
    /** the values mods declare about their own content (their mods.toml), and which mod declared each */
    private static final Map<String, String> DECLARED = new TreeMap<>(), DECLARED_BY = new TreeMap<>();
    /** the keys a bisect round turned off for this run (read at start) */
    private static final java.util.List<String> BISECT_OFF = new java.util.ArrayList<>();

    public static java.util.List<String> bisectOffThisRun() {
        return java.util.List.copyOf(BISECT_OFF);
    }

    /** where each value set from a file or a declaration came from (the rest: launch flags, defaults) */
    private static final Map<String, String> SOURCE = new TreeMap<>();

    /**
     * GameDefaults, first: the layers, highest first - a launch flag; the pack's locked keys; the player's file; the
     * pack's file; mods' declarations about their own content; (then GameDefaults: the jar's defaults). Lists of ids
     * ({@code sleep.compat.*}) are joined across the layers instead of replaced.
     */
    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        for (String k : System.getProperties().stringPropertyNames()) if (engineKey(k)) LAUNCH.add(k);
        Path pf = packFile();
        if (Files.exists(pf)) {
            Map<String, String> raw = read(pf);
            for (var e : raw.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("lock.")) {
                    if (Boolean.parseBoolean(e.getValue()) && engineKey(k.substring(5))) LOCKED.add(k.substring(5));
                } else if (engineKey(k)) PACK.put(k, e.getValue());
            }
            LOCKED.retainAll(PACK.keySet());   // a lock without a value locks nothing
        }
        ModDeclarations.read(DECLARED, DECLARED_BY);
        Path f = file();
        boolean exists = Files.exists(f);
        if (exists) for (var e : read(f).entrySet()) if (engineKey(e.getKey())) FILE.put(e.getKey(), e.getValue());
        java.util.Set<String> keys = new java.util.TreeSet<>(PACK.keySet());
        keys.addAll(FILE.keySet());
        keys.addAll(DECLARED.keySet());
        int fromFile = 0, fromPack = 0, fromMods = 0;
        for (String k : keys) {
            if (LAUNCH.contains(k)) continue;
            if (k.startsWith("sleep.compat.")) {   // id lists: every layer's ids
                java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
                for (Map<String, String> m : java.util.List.of(DECLARED, PACK, FILE)) {
                    String v = m.get(k);
                    if (v != null) for (String id : v.split(",")) if (!id.isBlank()) ids.add(id.trim());
                }
                System.setProperty(k, String.join(",", ids));
                SOURCE.put(k, "joined");
                continue;
            }
            String v, src;
            if (LOCKED.contains(k)) {
                v = PACK.get(k);
                src = "pack-locked";
                fromPack++;
            } else if (FILE.containsKey(k)) {
                v = FILE.get(k);
                src = "player";
                fromFile++;
            } else if (PACK.containsKey(k)) {
                v = PACK.get(k);
                src = "pack";
                fromPack++;
            } else {
                v = DECLARED.get(k);
                src = "mod:" + DECLARED_BY.get(k);
                fromMods++;
            }
            System.setProperty(k, v);
            SOURCE.put(k, src);
        }
        BISECT_OFF.addAll(Bisect.offNow());   // a search for a problem's setting: this round's keys off (over the files, not over launch flags)
        for (String k : BISECT_OFF) {
            if (LAUNCH.contains(k)) continue;
            System.setProperty(k, "false");
            SOURCE.put(k, "bisect");
        }
        if (!BISECT_OFF.isEmpty()) System.out.println("[sleep/bisect] this run: " + BISECT_OFF.size() + " settings off - " + BISECT_OFF);
        System.out.println("[sleep/opt] settings: " + fromFile + " from " + f + (Files.exists(pf) ? ", " + fromPack + " from the pack's " + pf.getFileName()
                + (LOCKED.isEmpty() ? "" : " (" + LOCKED.size() + " locked)") : "") + (DECLARED.isEmpty() ? "" : ", " + fromMods + " declared by mods (" + DECLARED.size() + " in all)"));
        if (!exists) write(f, Map.of());   // the documented catalogue, every line commented: the defaults stay the jar's
    }

    /** a properties-like file: key=value lines, # comments (the order kept) */
    private static Map<String, String> read(Path f) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq < 0) continue;
                m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            System.out.println("[sleep/opt] settings: " + f + ": " + e);
        }
        return m;
    }

    /** the player cannot change it: a launch flag or a key the pack locked */
    public static boolean locked(String key) {
        return LAUNCH.contains(key) || LOCKED.contains(key);
    }

    public static boolean packLocked(String key) {
        return LOCKED.contains(key) && !LAUNCH.contains(key);
    }

    /**
     * Where the key's value comes from: "launch", "pack-locked", "player", "pack", "mod:<id>", "joined" (id lists) or
     * "default" (the jar's defaults, or unset).
     */
    public static String source(String key) {
        if (LAUNCH.contains(key)) return "launch";
        return SOURCE.getOrDefault(key, "default");
    }

    /** the value without the player's file: the pack's, a mod's declaration, else the given default */
    public static String baseline(String key, String byDefault) {
        if (PACK.containsKey(key)) return PACK.get(key);
        if (DECLARED.containsKey(key)) return DECLARED.get(key);
        return byDefault;
    }

    /** the pack's values and locks (copies) */
    public static synchronized Map<String, String> pack() {
        return new TreeMap<>(PACK);
    }

    public static synchronized Set<String> packLocks() {
        return new java.util.TreeSet<>(LOCKED);
    }

    /** the mods' declarations: key -> "value (mod id)" */
    public static synchronized Map<String, String> declared() {
        Map<String, String> m = new TreeMap<>();
        for (var e : DECLARED.entrySet()) m.put(e.getKey(), e.getValue() + " (" + DECLARED_BY.get(e.getKey()) + ")");
        return m;
    }

    /** GameDefaults, last: the values the game starts with */
    static synchronized void started() {
        for (String k : System.getProperties().stringPropertyNames()) if (engineKey(k)) STARTUP.put(k, System.getProperty(k));
    }

    public static boolean launchFlag(String key) {
        return LAUNCH.contains(key);
    }

    /** the value the game started with (null: unset) */
    public static String startup(String key) {
        return STARTUP.get(key);
    }

    /** the file's values (a copy) */
    public static synchronized Map<String, String> saved() {
        return new TreeMap<>(FILE);
    }

    /** replaces the file's values (the screen's Done); also sets them as properties for what reads them live */
    public static synchronized void save(Map<String, String> values) {
        FILE.clear();
        FILE.putAll(values);
        FILE.keySet().removeAll(LOCKED);   // the pack's locks stay the pack's
        for (var e : FILE.entrySet()) if (!LAUNCH.contains(e.getKey()) && !e.getKey().startsWith("sleep.compat.")) System.setProperty(e.getKey(), e.getValue());
        write(file(), FILE);
    }

    /**
     * The file with every switch of the catalogue, by group: its description, then {@code key=value} when set here, else
     * the line commented out with its default; keys set here that the catalogue does not know at the end.
     */
    private static void write(Path f, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sleepium settings\n");
        sb.append("#\n");
        sb.append("# Every switch of Sleepium with what it does and its default. A line key=value here wins over the default;\n");
        sb.append("# a line starting with # uses the default (so a new version's defaults reach you). A -Dkey=value launch flag\n");
        sb.append("# wins over this file. The in-game settings screen (Options -> Sleepium...) writes this file too.\n");
        sb.append("# Most switches take effect after a restart (a server: edit, then restart it). true = on, false = off.\n");
        sb.append("# Switches marked \"part of\" belong to another one: switching that one off switches them off too.\n");
        Map<String, String> groups = groupNames();
        Map<String, java.util.List<dev.lull.ui.SleepOptions.Option>> byGroup = dev.lull.ui.SleepOptions.byGroup();
        Set<String> known = new HashSet<>();
        for (var g : byGroup.entrySet()) {
            if (g.getValue().isEmpty()) continue;
            sb.append("\n# ==== ").append(groups.getOrDefault(g.getKey(), g.getKey())).append(" ====\n");
            String gd = groups.get(g.getKey() + ".desc");
            if (gd != null) sb.append("# ").append(gd).append('\n');
            for (var o : g.getValue()) {
                known.add(o.key());
                sb.append("\n# ").append(o.description().isEmpty() ? o.shortName() : o.description()).append('\n');
                if (!o.partOf().isEmpty()) sb.append("# (part of ").append(String.join(", ", o.partOf())).append(")\n");
                String v = values.get(o.key());
                if (v != null) sb.append(o.key()).append('=').append(v).append('\n');
                else sb.append('#').append(o.key()).append('=').append(o.byDefault()).append('\n');
            }
        }
        boolean other = false;
        for (var e : values.entrySet()) {
            if (known.contains(e.getKey())) continue;
            if (!other) sb.append("\n# ==== Other settings ====\n");
            other = true;
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.out.println("[sleep/opt] settings: could not write " + f + ": " + e);
        }
    }

    /** the groups' names and descriptions as the screen shows them (en_us.json: sleepium.ui.group.<g>[.desc]) */
    private static Map<String, String> groupNames() {
        Map<String, String> m = new TreeMap<>();
        try (java.io.InputStream in = SleepConfig.class.getResourceAsStream("/assets/sleepium/lang/en_us.json")) {
            if (in == null) return m;
            java.util.regex.Matcher x = java.util.regex.Pattern.compile("\"sleepium\\.ui\\.group\\.([\\w.]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            while (x.find()) m.put(x.group(1), x.group(2).replace("\\\"", "\""));
        } catch (IOException e) {
            // the keys stand in for the names
        }
        return m;
    }
}
