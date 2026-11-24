package dev.lull.optsupport;

/**
 * The hooks the parallel runs cannot work without, recorded by the mixin plugin's postApply when a mixin really went into
 * its class (a mixin that gave way to another mod's - MixinYield - never gets there). The runs ask before they use
 * workers: a missing guard keeps them serial, or keeps out the mobs that need it. No game classes here: the plugin
 * touches this class while the game's classes are being transformed.
 */
public final class HookFlags {
    private HookFlags() {}

    /** simple names of the mixins applied so far (classes load over time: a raid's class with the first raid) */
    private static final java.util.Set<String> APPLIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * ServerParallelItemsChunkMixin: a run worker's chunk lookups are served from the loaded chunks. Without it a worker
     * asks the server thread and waits - while the server thread waits for the run: the tick never ends (Lithium,
     * Canary and Radium replace ServerChunkCache.getChunk; ours gives way).
     */
    public static volatile boolean chunkHook;

    /** what every parallel run needs: chunk lookups, the entity lists' lock, spawns / block writes / section moves kept for the run's end */
    private static final String[] REQUIRED = {"ServerParallelItemsChunkMixin", "ServerParallelItemsMultiMapMixin", "ServerParallelItemsAbortMixin",
            "ServerParallelItemsQueryMixin", "ParallelEntityTicksMoveMixin"};
    private static final java.util.Set<String> TOLD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** simple names of our mixins that gave way to another mod's (not applied for good) */
    private static final java.util.Set<String> GAVE_WAY = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** OptMixinPlugin.shouldApplyMixin */
    public static void gaveWay(String mixinClassName) {
        GAVE_WAY.add(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
    }

    /** OptMixinPlugin.postApply */
    public static void applied(String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        APPLIED.add(simple);
        if (simple.equals("ServerParallelItemsChunkMixin")) chunkHook = true;
    }

    public static boolean has(String simpleName) {
        return APPLIED.contains(simpleName);
    }

    /** the runs may use workers; the first time not, one line says why */
    public static boolean parallelOk() {
        for (String r : REQUIRED) {
            if (APPLIED.contains(r)) continue;
            if (GAVE_WAY.contains(r)) tell("parallel ticks stay serial: " + r + " is not applied - another mod changes the same code (see the 'not applied' line above)");
            return false;
        }
        return true;
    }

    /** a guard for one kind of mob or one pass (false: that part stays on the server thread; said once) */
    public static boolean guard(String simpleName, String whatStaysSerial) {
        if (APPLIED.contains(simpleName)) return true;   // (neither applied nor given way: its class is not loaded yet - quietly serial for now)
        if (GAVE_WAY.contains(simpleName)) tell(whatStaysSerial + " stay serial: " + simpleName + " is not applied - another mod changes the same code");
        return false;
    }

    private static void tell(String line) {
        if (TOLD.add(line)) System.out.println("[sleep/spec] " + line);
    }
}
