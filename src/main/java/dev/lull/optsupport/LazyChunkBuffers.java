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

    /** buffer names 0 not deleted at close (a section's buffer closed before its first bind) */
    public static long closedUnnamed;

    private static final boolean ON_LINUX = net.minecraft.Util.getPlatform() == net.minecraft.Util.OS.LINUX;

    public static boolean lazyHere() {
        return building == Thread.currentThread();
    }

    /**
     * In place of RenderSystem.glDeleteBuffers(0) at VertexBuffer.close: deleting name 0 does nothing; on Linux vanilla's
     * delete binds the name to GL_ARRAY_BUFFER, orphans it (GL_INVALID_OPERATION with name 0) and binds 0 - the binding
     * that sequence leaves (0) is kept, the failing glBufferData is not made.
     */
    public static void skipUnnamedDelete() {
        closedUnnamed++;
        if (ON_LINUX) org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER, 0);
    }

    public static String report() {
        return "lazy chunk buffers: " + made + " made without GL names, " + named + " named at their first bind, " + closedUnnamed
                + " names 0 not deleted at close";
    }
}
