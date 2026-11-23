package dev.lull.optsupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The cores budget (Options -> Sleepium... -> threads): how many worker threads each of Sleepium's pools takes, the
 * player's choice or Auto. Three pools share the machine's cores with the game's own threads (the server tick, the
 * render thread):
 * <ul>
 *   <li>server - parallel entity ticks / Maximum speed, the entity tracker, dropped items ({@code lab.sreactor.threads});</li>
 *   <li>client - parallel rendering, client entities, particles ({@code lab.reactor.threads});</li>
 *   <li>background - world generation and vanilla's background work ({@code lab.opt.bgThreads}).</li>
 * </ul>
 * Kept in sleepium-cores.properties beside the settings file; applied at start before any pool is made (a pool's size
 * is fixed when it starts: a change needs a restart). A value given at launch (-D...) wins over the file.
 */
public final class Cores {
    private Cores() {}

    public static final String[] POOLS = {"server", "client", "background"};
    private static final String[] PROPS = {"lab.sreactor.threads", "lab.reactor.threads", "lab.opt.bgThreads"};
    /** 0 = Auto */
    private static final int[] CHOSEN = new int[3];
    /** what was in effect at start (the pools' sizes for this run) */
    private static final int[] AT_START = new int[3];
    private static final int[] CHOSEN_AT_START = new int[3];
    private static boolean loaded;

    public static int cpus() {
        return Runtime.getRuntime().availableProcessors();
    }

    static Path file() {
        return SleepConfig.file().resolveSibling("sleepium-cores.properties");
    }

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq < 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < POOLS.length; i++)
                    if (k.equals("cores." + POOLS[i])) CHOSEN[i] = v.equals("auto") ? 0 : Math.max(0, Math.min(cpus(), Integer.parseInt(v)));
            }
        } catch (Exception e) {
            System.out.println("[sleep/cores] " + f + ": " + e);
        }
    }

    public static synchronized void save() {
        StringBuilder sb = new StringBuilder("# Sleepium's cores budget: worker threads per pool (auto or a number); read at start.\n"
                + "# server = parallel entity ticks / Maximum speed, tracker, items; client = parallel rendering, client entities,\n"
                + "# particles; background = world generation. The settings screen writes this file; a modpack may ship its own.\n");
        for (int i = 0; i < POOLS.length; i++) sb.append("cores.").append(POOLS[i]).append('=').append(CHOSEN[i] == 0 ? "auto" : String.valueOf(CHOSEN[i])).append('\n');
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), sb, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[sleep/cores] " + file() + ": " + e);
        }
    }

    /** at start (GameDefaults.ensure, before the presets): the chosen counts as the pools' properties (a launch value wins) */
    static void apply() {
        load();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < POOLS.length; i++) {
            if (CHOSEN[i] > 0 && System.getProperty(PROPS[i]) == null) {
                System.setProperty(PROPS[i], String.valueOf(CHOSEN[i]));
                sb.append(' ').append(POOLS[i]).append('=').append(CHOSEN[i]);
            }
        }
        if (sb.length() > 0) System.out.println("[sleep/cores] chosen worker threads:" + sb);
    }

    /** after the presets: what each pool will take this run */
    static void started() {
        for (int i = 0; i < POOLS.length; i++) {
            AT_START[i] = effective(i);
            CHOSEN_AT_START[i] = CHOSEN[i];
        }
        System.out.println("[sleep/cores] " + cpus() + " cores; worker threads: server " + AT_START[0] + ", client " + AT_START[1] + ", background " + AT_START[2]);
    }

    /** the size pool i takes with the properties as they are (the pools' own defaults, see ServerReactor / FrameReactor / LabPools) */
    static int effective(int i) {
        Integer v = Integer.getInteger(PROPS[i]);
        if (v != null) return v;
        int c = cpus();
        return switch (i) {
            case 0, 1 -> Math.max(1, Math.min(4, c - 2));
            default -> Boolean.getBoolean("lab.opt.ParallelWorldgenMixin") ? Math.max(4, Math.min(8, c / 2)) : 4;
        };
    }

    public static int chosen(int i) {
        load();
        return CHOSEN[i];
    }

    /** the next choice: Auto, 1, 2, ... cores, Auto */
    public static void cycle(int i) {
        load();
        CHOSEN[i] = CHOSEN[i] >= cpus() ? 0 : CHOSEN[i] + 1;
        save();
    }

    public static int atStart(int i) {
        return AT_START[i];
    }

    /** the change needs a restart: the choice differs from what this run took */
    public static boolean restartNeeded(int i) {
        return chosen(i) != CHOSEN_AT_START[i];
    }
}
