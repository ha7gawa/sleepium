package dev.lull.optsupport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * -Dlab.lazySections (11-18): a chunk's copy for meshing (RenderChunk) copies its sections for the regions made from it
 * only. Vanilla copies every non-empty section of every chunk a region touches (up to 24 a column, nine columns a
 * region) on the render thread, in the frame, though a mesh reads its section and the ones next to it. Here a section
 * is copied when a region for a section within RADIUS of it is made from the chunk copy - on the render thread at that
 * moment, as vanilla's whole copy is, so the mesh sees the same snapshot. A read beyond (none known: models read one
 * block around) copies the section then (LATE).
 */
public final class LazySections {
    private LazySections() {}

    public static final boolean ON = Boolean.getBoolean("lab.lazySections");
    public static final int RADIUS = 2;
    public static final AtomicLong COPIED = new AtomicLong(), SKIPPED = new AtomicLong(), LATE = new AtomicLong();

    /** RenderChunk (LazySectionCopyMixin) */
    public interface Chunk {
        /** the sections from sectionY - RADIUS to + RADIUS copied now (if not yet) */
        void sleep$ensureAround(int sectionY);

        /** sections never copied: counted when the chunk copy is dropped (approximately: at the next frame's cache) */
        int sleep$uncopied();
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "lazy sections: %d deferred (vanilla copies them all), %d copied for regions, %d copied late", SKIPPED.get(), COPIED.get(), LATE.get());
    }
}
