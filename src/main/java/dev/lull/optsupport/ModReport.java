package dev.lull.optsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What Sleepium did to each mod, for the settings screen's Mods page and the report (notes/73): our mixins that gave way
 * to it (and why), its mixins a rule took out, and per block entity / entity type of it the sleep verdict with the
 * number of objects seen. Filled as things happen (mixins at start, types as their first object ticks); read from the
 * screen's thread - every map is concurrent, the lines are plain strings.
 */
public final class ModReport {
    private ModReport() {}

    /** mod id -> lines about mixins ("gave way: ...", "cancelled: ...") */
    private static final Map<String, Set<String>> MIXINS = new ConcurrentHashMap<>();
    /** block entity / entity type -> its verdict; how many objects of it were seen */
    private static final Map<Object, String> BE = new ConcurrentHashMap<>(), ENT = new ConcurrentHashMap<>();
    private static final Map<Object, AtomicInteger> BE_SEEN = new ConcurrentHashMap<>(), ENT_SEEN = new ConcurrentHashMap<>();

    static void mixin(String mod, String line) {
        MIXINS.computeIfAbsent(mod, k -> ConcurrentHashMap.newKeySet()).add(line);
    }

    /** a block entity's sleep verdict ("sleeps", "watched", "off (a rule)", "never: reason"), once per type; counted per object */
    public static void blockEntity(Object type, String verdict) {
        BE.putIfAbsent(type, verdict);
        BE_SEEN.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
    }

    public static void entity(Object type, String verdict) {
        ENT.putIfAbsent(type, verdict);
        ENT_SEEN.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
    }

    /** one mod's view */
    public record Mod(String id, List<String> mixins, Map<String, String> blockEntities, Map<String, String> entities) {
        public boolean touched() {
            return !mixins.isEmpty() || !blockEntities.isEmpty() || !entities.isEmpty();
        }

        /** the list's short line: "gave way 2 · blocks 5/7 · mobs 3/4" */
        public String brief() {
            List<String> parts = new ArrayList<>();
            long gave = mixins.stream().filter(l -> l.startsWith("gave way")).count(), cancelled = mixins.stream().filter(l -> l.startsWith("cancelled")).count();
            if (cancelled > 0) parts.add("took out " + cancelled);
            if (gave > 0) parts.add("gave way " + gave);
            if (!blockEntities.isEmpty()) parts.add("blocks " + blockEntities.values().stream().filter(v -> v.startsWith("sleeps") || v.startsWith("watched")).count() + "/" + blockEntities.size());
            if (!entities.isEmpty()) parts.add("mobs " + entities.values().stream().filter(v -> v.startsWith("sleeps")).count() + "/" + entities.size());
            return String.join(" · ", parts);
        }

        /** "gave way 2 · 5 kinds of blocks sleep of 7 · 1 cancelled" */
        public String summary() {
            List<String> parts = new ArrayList<>();
            long gave = mixins.stream().filter(l -> l.startsWith("gave way")).count(), cancelled = mixins.stream().filter(l -> l.startsWith("cancelled")).count();
            if (gave > 0) parts.add("gave way " + gave);
            if (cancelled > 0) parts.add("took out " + cancelled + " of its mixins");
            if (!blockEntities.isEmpty()) {
                long sleep = blockEntities.values().stream().filter(v -> v.startsWith("sleeps") || v.startsWith("watched")).count();
                parts.add("blocks: " + sleep + "/" + blockEntities.size() + " kinds sleep");
            }
            if (!entities.isEmpty()) {
                long sleep = entities.values().stream().filter(v -> v.startsWith("sleeps")).count();
                parts.add("entities: " + sleep + "/" + entities.size() + " kinds sleep");
            }
            return String.join(" · ", parts);
        }
    }

    public static Mod of(String mod) {
        List<String> mx = new ArrayList<>(MIXINS.getOrDefault(mod, Set.of()));
        java.util.Collections.sort(mx);
        return new Mod(mod, mx, types(BE, BE_SEEN, mod), types(ENT, ENT_SEEN, mod));
    }

    private static Map<String, String> types(Map<Object, String> verdicts, Map<Object, AtomicInteger> seen, String mod) {
        Map<String, String> out = new TreeMap<>();
        for (var e : verdicts.entrySet()) {
            String ns = dev.lull.api.Compat.namespace(e.getKey());
            if (!ns.equals(mod)) continue;
            String id = typeId(e.getKey());
            AtomicInteger n = seen.get(e.getKey());
            out.put(id.substring(id.indexOf(':') + 1), e.getValue() + (n == null ? "" : " (" + n.get() + " seen)"));
        }
        return out;
    }

    private static String typeId(Object type) {
        net.minecraft.resources.ResourceLocation rl = type instanceof net.minecraft.world.entity.EntityType<?> t ? net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(t)
                : type instanceof net.minecraft.world.level.block.entity.BlockEntityType<?> t ? net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(t) : null;
        return rl == null ? "?:" + type : rl.toString();
    }

    /** every mod Sleepium knows something about */
    public static Set<String> mods() {
        Set<String> out = new java.util.TreeSet<>(MIXINS.keySet());
        for (Object t : BE.keySet()) out.add(dev.lull.api.Compat.namespace(t));
        for (Object t : ENT.keySet()) out.add(dev.lull.api.Compat.namespace(t));
        out.remove("");
        return out;
    }

    /** the whole report as text (the screen's "Write the report", /sleepium report) */
    public static String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sleepium report\n\n");
        sb.append("Settings: the pack's file ").append(SleepConfig.packFile().getFileName()).append(SleepConfig.pack().isEmpty() ? " (none)" : " " + SleepConfig.pack().size() + " values, locked " + SleepConfig.packLocks()).append('\n');
        if (!SleepConfig.declared().isEmpty()) sb.append("Mods' declarations: ").append(SleepConfig.declared()).append('\n');
        var rules = dev.lull.api.Compat.rules();
        if (!rules.isEmpty()) sb.append("Rules: ").append(rules).append('\n');
        if (!MixinYield.CANCELLED.isEmpty()) sb.append("Other mods' mixins taken out by a rule: ").append(MixinYield.CANCELLED).append('\n');
        if (ModProfile.ready) {
            sb.append("\n## What each mod cost (").append(ModProfile.span()).append(")\n");
            for (ModProfile.Result r : ModProfile.results()) {
                if (r.total() < 0.005) continue;
                sb.append("- ").append(r.mod()).append(": ").append(r.line()).append('\n');
                for (String t : r.top()) sb.append("  - ").append(t).append('\n');
            }
        }
        for (String m : mods()) {
            Mod r = of(m);
            sb.append("\n## ").append(m).append("  -  ").append(r.summary()).append('\n');
            for (String l : r.mixins()) sb.append("- ").append(l).append('\n');
            for (var e : r.blockEntities().entrySet()) sb.append("- block ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            for (var e : r.entities().entrySet()) sb.append("- entity ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** written next to the settings: config/sleepium-report.md */
    public static java.nio.file.Path write() throws java.io.IOException {
        java.nio.file.Path f = SleepConfig.file().resolveSibling("sleepium-report.md");
        java.nio.file.Files.writeString(f, text(), java.nio.charset.StandardCharsets.UTF_8);
        return f;
    }
}
