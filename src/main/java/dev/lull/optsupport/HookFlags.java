package dev.lull.optsupport;

/**
 * Hooks the parallel runs cannot work without, set by the mixin plugins' postApply when the mixin really went into its
 * class (a mixin that gave way to another mod's - MixinYield - never gets there). No game classes here: the plugin
 * touches this class while the game's classes are being transformed.
 */
public final class HookFlags {
    private HookFlags() {}

    /**
     * ServerParallelItemsChunkMixin: a run worker's chunk lookups are served from the loaded chunks. Without it a worker
     * asks the server thread and waits - while the server thread waits for the run: the tick never ends (Lithium
     * replaces ServerChunkCache.getChunk; ours gives way). Runs stay serial then.
     */
    public static volatile boolean chunkHook;
    private static volatile boolean told;

    /** the runs may use workers; the first time not, one line says why */
    public static boolean parallelOk() {
        if (chunkHook) return true;
        if (!told) {
            told = true;
            System.out.println("[sleep/spec] parallel ticks stay serial: the worker chunk hook (ServerParallelItemsChunkMixin) is not applied"
                    + " - another mod replaces the server's chunk lookup (see the 'not applied' line above)");
        }
        return false;
    }
}
