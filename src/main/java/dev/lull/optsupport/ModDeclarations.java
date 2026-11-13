package dev.lull.optsupport;

import java.util.Map;

/**
 * What mods say about their own content, without depending on Sleepium: a table {@code sleepium} in their mods.toml's
 * {@code [modproperties.<modid>]} (the loader keeps it; without Sleepium nothing reads it):
 * <pre>
 * [modproperties.mymod]
 * sleepium = { sleep = "observed", entitySleep = "off", noRenderCache = ["mymod:clock"] }
 * </pre>
 * Rules about the whole mod: {@code sleep} (on / observed / off: its block entities), {@code entitySleep},
 * {@code renderCache}, {@code parallelRender} (on / off), {@code giveWay} (conflicts / always). Lists of its own type ids:
 * {@code noSleep}, {@code noEntitySleep}, {@code noRenderCache}, {@code noParallelRender}. A mod speaks only for itself:
 * ids outside its namespace are ignored. The rules become the engine keys {@code sleep.mod.<modid>.<rule>} and the
 * lists join {@code sleep.compat.<list>} - the same keys a pack or a player sets, which win over a declaration.
 */
public final class ModDeclarations {
    private ModDeclarations() {}

    static final java.util.Set<String> RULES = java.util.Set.of("sleep", "entitySleep", "renderCache", "parallelRender", "giveWay");
    static final java.util.Set<String> LISTS = java.util.Set.of("noSleep", "noEntitySleep", "noRenderCache", "noParallelRender");

    /** the value is one this rule takes (lower case), else null */
    public static String valid(String rule, String v) {
        if (v == null) return null;
        v = v.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (rule) {
            case "sleep" -> v.equals("on") || v.equals("observed") || v.equals("off") ? v : null;
            case "entitySleep", "renderCache", "parallelRender" -> v.equals("on") || v.equals("off") ? v : null;
            case "giveWay" -> v.equals("conflicts") || v.equals("always") ? v : null;
            default -> null;
        };
    }

    static void read(Map<String, String> out, Map<String, String> by) {
        java.util.List<?> mods;
        try {
            mods = net.neoforged.fml.loading.LoadingModList.get().getMods();
        } catch (Throwable t) {
            return;   // no loader list yet (tests): nothing declared
        }
        int n = 0;
        for (Object o : mods) {
            String id;
            Map<String, Object> props;
            try {
                net.neoforged.neoforgespi.language.IModInfo mi = (net.neoforged.neoforgespi.language.IModInfo) o;
                id = mi.getModId();
                props = mi.getModProperties();
            } catch (Throwable t) {
                continue;
            }
            if (props == null || props.isEmpty()) continue;
            Map<String, Object> table = new java.util.LinkedHashMap<>();
            Map<String, Object> nested = asMap(props.get("sleepium"));
            if (nested != null) table.putAll(nested);
            for (var e : props.entrySet()) if (e.getKey().startsWith("sleepium.")) table.put(e.getKey().substring(9), e.getValue());
            if (table.isEmpty()) continue;
            for (var e : table.entrySet()) {
                String k = e.getKey();
                if (RULES.contains(k)) {
                    String v = valid(k, String.valueOf(e.getValue()));
                    if (v == null) {
                        System.out.println("[sleep/opt] mod " + id + " declares sleepium " + k + " = " + e.getValue() + ": not a value of it, ignored");
                        continue;
                    }
                    String key = "sleep.mod." + id + "." + k;
                    out.put(key, v);
                    by.put(key, id);
                    n++;
                } else if (LISTS.contains(k)) {
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    Object v = e.getValue();
                    if (v instanceof Iterable<?> it) for (Object x : it) ids.add(String.valueOf(x).trim());
                    else for (String x : String.valueOf(v).split(",")) ids.add(x.trim());
                    String key = "sleep.compat." + k;
                    StringBuilder sb = new StringBuilder(out.getOrDefault(key, ""));
                    for (String x : ids) {
                        if (x.isEmpty()) continue;
                        if (!x.startsWith(id + ":")) {
                            System.out.println("[sleep/opt] mod " + id + " declares sleepium " + k + " " + x + ": not its own id, ignored");
                            continue;
                        }
                        if (sb.length() > 0) sb.append(',');
                        sb.append(x);
                        n++;
                    }
                    if (sb.length() > 0) {
                        out.put(key, sb.toString());
                        by.merge(key, id, (a, b) -> a.contains(b) ? a : a + "," + b);
                    }
                } else System.out.println("[sleep/opt] mod " + id + " declares sleepium " + k + ": unknown, ignored");
            }
        }
        if (n > 0) System.out.println("[sleep/opt] mods' declarations: " + n + " rule(s) - " + by.values().stream().distinct().toList());
    }

    /** a TOML table as the loader hands it over: a Map, or a night-config Config (its valueMap) */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o == null) return null;
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        try {
            Object vm = o.getClass().getMethod("valueMap").invoke(o);
            if (vm instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // not a table
        }
        return null;
    }
}
