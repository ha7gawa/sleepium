package dev.lull;

/** Fabric (lab): the world generation benchmark is NeoForge-only for now; what other classes read stays, off. */
public final class GenBenchHooks {
    private GenBenchHooks() {}

    public static volatile boolean active;

    public static boolean active() {
        return false;
    }

    public static void onStepDone(Object step, Object chunk) {}
}
