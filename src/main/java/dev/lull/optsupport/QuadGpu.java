package dev.lull.optsupport;

/** Fabric (lab): QuadGpu (block-entity models from VRAM) is not in the Fabric build yet; what other classes read stays, off. */
public final class QuadGpu {
    private QuadGpu() {}

    public static void endFrame() {}

    public static String report() {
        return "QuadGpu off (Fabric)";
    }
}
