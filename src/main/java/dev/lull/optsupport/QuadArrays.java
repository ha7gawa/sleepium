package dev.lull.optsupport;

/**
 * -Dlab.opt.QuadDataArraysMixin (11-18): ModelBlockRenderer.putQuadData builds a new float[4] (brightness) and int[4]
 * (lightmap) for every quad it meshes (8.8 MB/s on the meshing workers in the zoo orbit). Here the two arrays are the
 * thread's own, refilled for each quad: putBulkData reads them before it returns (vanilla's body, QuadGpu copies the
 * values), so the next quad's refill cannot be seen by the last. On our pool's threads through ThreadSlots, on others
 * through a ThreadLocal.
 */
public final class QuadArrays {
    public final float[] bright = new float[4];
    public final int[] light = new int[4];
    /** QuadGpu.vanilla's normal and vertex position (two new Vector3f a vertex before) */
    public final org.joml.Vector3f normal = new org.joml.Vector3f(), pos = new org.joml.Vector3f();
    public static final boolean ON = Boolean.getBoolean("lab.opt.QuadDataArraysMixin");

    private static final ThreadLocal<QuadArrays> OTHER = ThreadLocal.withInitial(QuadArrays::new);

    public static QuadArrays mine() {
        Object[] s = ThreadSlots.mine();
        if (s == null) return OTHER.get();
        QuadArrays q = (QuadArrays) s[ThreadSlots.QUAD];
        if (q == null) s[ThreadSlots.QUAD] = q = new QuadArrays();
        return q;
    }
}
