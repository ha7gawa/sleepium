package dev.lull.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Opt-outs for mods whose objects must keep vanilla's path. probe proves what it can by itself (a renderer that
 * animates by time leaves the render cache, a block entity whose tick changes state never sleeps), but a mod knows
 * things no observation can see — a renderer with a thread-affine resource, a tick that talks to an external system.
 * Register during mod construction or common setup, before the first world loads: some decisions are made once per
 * class and kept.
 *
 * <p>Without code - a mod in its mods.toml (ModDeclarations), a pack in config/sleepium-pack.properties, a player in
 * config/sleepium.properties or as JVM properties:
 * <ul>
 * <li>lists of ids, comma-separated: {@code sleep.compat.noParallelRender=}, {@code noRenderCache}, {@code noSleep}
 * (block entity types), {@code noEntitySleep} (entity types), e.g. {@code sleep.compat.noRenderCache=create:shaft,mymod:*}
 * - {@code modid:*} is every type of that mod;</li>
 * <li>a whole mod: {@code sleep.mod.<modid>.sleep=off} (or {@code observed}: its block entities sleep only watched),
 * {@code sleep.mod.<modid>.entitySleep=off}, {@code renderCache=off}, {@code parallelRender=off}.</li>
 * </ul>
 */
public final class Compat {
    /** one kind of opt-out: registered types, whole mods (namespaces), and the answer per type once asked */
    private static final class Kind {
        final Set<Object> types = ConcurrentHashMap.newKeySet();
        final Set<String> mods = ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<Object, Boolean> answers = new ConcurrentHashMap<>();

        boolean test(Object type) {
            Boolean a = answers.get(type);
            if (a == null) {
                a = types.contains(type) || !mods.isEmpty() && mods.contains(namespace(type));
                answers.put(type, a);
            }
            return a;
        }
    }

    private static final Kind PARALLEL = new Kind(), CACHE = new Kind(), SLEEP = new Kind(), ENTITY_SLEEP = new Kind(), OBSERVED = new Kind();
    /** false while nothing is registered: the checks below then cost one volatile read */
    private static volatile boolean any;
    private static volatile boolean idsResolved;

    private Compat() {}

    /** this entity type always renders on the render thread (not on ParallelRender's workers) */
    public static void excludeFromParallelRender(EntityType<?> type) {
        add(PARALLEL, type);
    }

    /** this entity type is always rendered the vanilla way, never replayed from the render cache */
    public static void excludeFromRenderCache(EntityType<?> type) {
        add(CACHE, type);
    }

    /** this block entity type is always rendered the vanilla way, never replayed from the render cache */
    public static void excludeFromRenderCache(BlockEntityType<?> type) {
        add(CACHE, type);
    }

    /** this block entity type always ticks (never put to sleep by the block-entity sleep engine) */
    public static void excludeFromSleep(BlockEntityType<?> type) {
        add(SLEEP, type);
    }

    /** this entity type always ticks (never put to sleep by the entity sleep engine) */
    public static void excludeFromEntitySleep(EntityType<?> type) {
        add(ENTITY_SLEEP, type);
    }

    // ------------------------------------------------------------------ checks (probe internals)

    public static boolean parallelRenderExcluded(Entity e) {
        return any && check(PARALLEL, e.getType());
    }

    /** obj: an Entity or a BlockEntity */
    public static boolean renderCacheExcluded(Object obj) {
        if (!any) return false;
        if (obj instanceof Entity e) return check(CACHE, e.getType());
        if (obj instanceof BlockEntity be) return check(CACHE, be.getType());
        return false;
    }

    public static boolean sleepExcluded(BlockEntity be) {
        return any && check(SLEEP, be.getType());
    }

    /** its mod's block entities sleep only watched (sleep.mod.<modid>.sleep=observed) */
    public static boolean sleepObservedOnly(BlockEntity be) {
        return any && check(OBSERVED, be.getType());
    }

    public static boolean entitySleepExcluded(Entity e) {
        return any && check(ENTITY_SLEEP, e.getType());
    }

    /** the mod (registry namespace) of an entity or block entity type, "" when it is not registered */
    public static String namespace(Object type) {
        ResourceLocation rl = type instanceof EntityType<?> t ? BuiltInRegistries.ENTITY_TYPE.getKey(t)
                : type instanceof BlockEntityType<?> t ? BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(t) : null;
        return rl == null ? "" : rl.getNamespace();
    }

    /** the rules as read (for the report): kind -> whole mods and ids */
    public static java.util.Map<String, String> rules() {
        if (!idsResolved) resolveIds();
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        String[] names = {"parallelRender", "renderCache", "sleep", "entitySleep", "observedSleep"};
        Kind[] kinds = {PARALLEL, CACHE, SLEEP, ENTITY_SLEEP, OBSERVED};
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i].mods.isEmpty() && kinds[i].types.isEmpty()) continue;
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (String ns : new java.util.TreeSet<>(kinds[i].mods)) parts.add(ns + ":*");
            for (Object t : kinds[i].types) {
                ResourceLocation rl = t instanceof EntityType<?> et ? BuiltInRegistries.ENTITY_TYPE.getKey(et)
                        : t instanceof BlockEntityType<?> bt ? BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(bt) : null;
                parts.add(rl == null ? String.valueOf(t) : rl.toString());
            }
            m.put(names[i], String.join(", ", parts));
        }
        return m;
    }

    // ------------------------------------------------------------------

    static {
        boolean a = false;
        for (String n : new String[] {"noParallelRender", "noRenderCache", "noSleep", "noEntitySleep"}) a |= !prop(n).isEmpty();
        for (String k : System.getProperties().stringPropertyNames()) {
            if (!k.startsWith("sleep.mod.")) continue;
            int dot = k.lastIndexOf('.');
            if (dot <= 10) continue;
            String mod = k.substring(10, dot), rule = k.substring(dot + 1), v = System.getProperty(k, "").trim().toLowerCase(java.util.Locale.ROOT);
            Kind kind = switch (rule) {
                case "sleep" -> v.equals("off") ? SLEEP : v.equals("observed") ? OBSERVED : null;
                case "entitySleep" -> v.equals("off") ? ENTITY_SLEEP : null;
                case "renderCache" -> v.equals("off") ? CACHE : null;
                case "parallelRender" -> v.equals("off") ? PARALLEL : null;
                default -> null;
            };
            if (kind != null) {
                kind.mods.add(mod);
                a = true;
            }
        }
        any = a;
    }

    private static void add(Kind kind, Object type) {
        kind.types.add(type);
        kind.answers.clear();
        any = true;
    }

    private static boolean check(Kind kind, Object type) {
        if (!idsResolved) resolveIds();
        return kind.test(type);
    }

    /** the property ids, looked up once the registries are there (first check happens in a world) */
    private static synchronized void resolveIds() {
        if (idsResolved) return;
        ids("noParallelRender", PARALLEL, true, false);
        ids("noRenderCache", CACHE, true, true);
        ids("noSleep", SLEEP, false, true);
        ids("noEntitySleep", ENTITY_SLEEP, true, false);
        for (Kind k : new Kind[] {PARALLEL, CACHE, SLEEP, ENTITY_SLEEP, OBSERVED}) k.answers.clear();
        idsResolved = true;
        var r = rules();
        if (!r.isEmpty()) System.out.println("[sleep/opt] rules for mods and types (pack, player, mods' declarations, API): " + r);
    }

    private static void ids(String name, Kind kind, boolean entities, boolean blockEntities) {
        for (String id : prop(name)) {
            if (id.endsWith(":*")) {
                kind.mods.add(id.substring(0, id.length() - 2));
                continue;
            }
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            if (entities) BuiltInRegistries.ENTITY_TYPE.getOptional(rl).ifPresent(kind.types::add);
            if (blockEntities) BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(rl).ifPresent(kind.types::add);
        }
    }

    private static java.util.List<String> prop(String name) {
        String v = System.getProperty("sleep.compat." + name, "").trim();
        if (v.isEmpty()) return java.util.List.of();
        return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
