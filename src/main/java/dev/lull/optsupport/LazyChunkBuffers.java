package dev.lull.optsupport;

/**
 * Chunk sections' vertex buffers take their GL names at their first bind instead of when the view area is built.
 *
 * Vanilla makes a VertexBuffer per section per chunk layer when it builds the view area - three GL names each (vertex
 * buffer, index buffer, VAO), for every section in view, empty or not: (2d+1)^2 * 24 * 5 of them at distance d. Most stay
 * empty for good (air above the ground, sealed stone below it), and with our arenas / regions most of the rest are never
 * drawn from. On Adreno through MojoLauncher's LTW the game dies past distance 7 (MojoLauncher caps vanilla there:
 * "more than 7 chunks needs Sodium") - 11-18 on the phone: distance 6 fine, 8 and 12 crash in the next buffer anyone
 * makes (the clouds' draw read its indices from address 0; the shared quad index buffer "Failed to map"); at distance 7
 * vanilla holds ~54 000 buffer names, at 8 ~69 000. Taken at the first bind (vanilla binds before each upload and each
 * draw), only the sections that hold something have names; at 32 chunks on the desktop that is ~1.5 million names
 * fewer up front.
 */
public final class LazyChunkBuffers {
    private LazyChunkBuffers() {
    }

    /** the thread building the view area's sections (their buffers are the lazy ones), or null */
    public static volatile Thread building;

    /** buffers made lazily / given their names later */
    public static long made, named;

    public static boolean lazyHere() {
        return building == Thread.currentThread();
    }

    public static String report() {
        return "lazy chunk buffers: " + made + " made without GL names, " + named + " named at their first bind";
    }
}
