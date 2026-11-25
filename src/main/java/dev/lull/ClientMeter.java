package dev.lull;

/** Fabric (lab): the client meter is not in the Fabric build yet; what other classes read stays, off. */
public final class ClientMeter {
    private ClientMeter() {}

    public static boolean measuring() {
        return false;
    }

    public static int frameIndex() {
        return 0;
    }
}
