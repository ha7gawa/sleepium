package dev.lull.optsupport;

/**
 * Issue #11 (no client classes here - the bus is common code): the render cache is recording on the render thread
 * (RenderCache sets it), and the bus is inside listeners there (depth). BufferSourceListenerMixin sends a listener's
 * request for the frame's own buffers into the recording only then - not Sleepium's own code around it.
 */
public final class ListenerScope {
    private ListenerScope() {}

    /** the thread the render cache records on while it records, else null */
    public static volatile Thread recording;
    /** listener dispatch depth on that thread */
    public static int depth;
}
