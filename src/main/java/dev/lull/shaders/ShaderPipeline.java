package dev.lull.shaders;

/** Fabric (lab): the shaderpack pipeline is not in the Fabric build yet; what other classes read stays, off. */
public final class ShaderPipeline {
    private ShaderPipeline() {}

    public static volatile boolean active;

    public static String preparing() {
        return null;
    }

    public static String currentName() {
        return null;
    }

    public static int gbufferDepth() {
        return 0;
    }

    public static int gbufferWidth() {
        return 0;
    }

    public static int gbufferHeight() {
        return 0;
    }
}
