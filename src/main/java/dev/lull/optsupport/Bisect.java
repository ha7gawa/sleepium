package dev.lull.optsupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finding the setting behind a problem by halves (notes/73): the switches that are on are the suspects; each round
 * turns half of them off until the next start, the player says whether the problem still happens, and the suspects
 * halve - about log2(n) restarts. The rounds live in config/sleepium-bisect.properties (deleted at the end); the
 * settings' layers read it first (SleepConfig.load): its "off" keys win over the player's file and the pack's, not over
 * launch flags. At the end the one setting found stays off in the player's file.
 */
public final class Bisect {
    private Bisect() {}

    public static Path file() {
        return SleepConfig.file().resolveSibling("sleepium-bisect.properties");
    }

    /** the state: suspects, the ones off this round, the round number (empty when no search runs) */
    public record State(List<String> suspects, List<String> off, int round, boolean confirmed) {
        public int roundsLeft() {
            int n = suspects.size(), r = 0;
            while (n > 1) {
                n = (n + 1) / 2;
                r++;
            }
            return r;
        }
    }

    public static State read() {
        Path f = file();
        if (!Files.exists(f)) return null;
        try {
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq < 0) continue;
                m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
            return new State(split(m.get("suspects")), split(m.get("off")), Integer.parseInt(m.getOrDefault("round", "1")), Boolean.parseBoolean(m.get("confirmed")));
        } catch (Exception e) {
            System.out.println("[sleep/bisect] " + f + ": " + e);
            return null;
        }
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<>();
        if (v != null) for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private static void write(State s) {
        StringBuilder sb = new StringBuilder("# Sleepium is finding the setting behind a problem (Options -> Sleepium -> Find the cause).\n"
                + "# The settings under \"off\" are off until the next answer. Delete this file to stop.\n");
        sb.append("round=").append(s.round()).append('\n');
        sb.append("suspects=").append(String.join(",", s.suspects())).append('\n');
        sb.append("off=").append(String.join(",", s.off())).append('\n');
        try {
            Files.writeString(file(), sb, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[sleep/bisect] " + file() + ": " + e);
        }
    }

    /** a new search over these switches (the ones on now): the first half off from the next start */
    public static State start(List<String> on) {
        State s = new State(new ArrayList<>(on), new ArrayList<>(on.subList(0, (on.size() + 1) / 2)), 1, false);
        write(s);
        System.out.println("[sleep/bisect] started over " + on.size() + " settings; off at the next start: " + s.off().size());
        return s;
    }

    /**
     * The player's answer about this run (with the round's "off" keys off). Returns the new state, or a finished one: one
     * suspect left (the cause) or none (the problem does not come from these settings).
     */
    public static State answer(boolean stillHappens) {
        State s = read();
        if (s == null) return null;
        List<String> next = new ArrayList<>(s.suspects());
        boolean confirmed;
        if (stillHappens) {   // with those off it still happens: it is among the ones left on (if it is a setting at all)
            next.removeAll(s.off());
            confirmed = s.confirmed();
        } else {              // gone with those off: it is among them - a setting for sure
            next.retainAll(s.off());
            confirmed = true;
        }
        if (next.isEmpty() || next.size() == 1 && confirmed) {   // found (one), or not one of these settings (none)
            finish();
            return new State(next, List.of(), s.round(), confirmed);
        }
        // one left that was never off in a run where the problem went: a last round with only it off
        State n = next.size() == 1 ? new State(next, new ArrayList<>(next), s.round() + 1, false)
                : new State(next, new ArrayList<>(next.subList(0, (next.size() + 1) / 2)), s.round() + 1, confirmed);
        write(n);
        return n;
    }

    public static void finish() {
        try {
            Files.deleteIfExists(file());
        } catch (Exception e) {
            System.out.println("[sleep/bisect] " + file() + ": " + e);
        }
    }

    /** SleepConfig.load: this round's keys off (above the player's file and the pack's) */
    static List<String> offNow() {
        State s = read();
        return s == null ? List.of() : s.off();
    }
}
