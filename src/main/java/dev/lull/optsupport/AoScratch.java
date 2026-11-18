package dev.lull.optsupport;

import java.util.BitSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;

/**
 * AoScratchMixin: one set of tesselateWithAO's per-block scratch per thread. take() hands it out cleared as new
 * objects are (the shape array zeroed, the flags empty; the AO face is cleared by the caller), or null while the
 * thread's set is in use (a nested call: then new ones, as vanilla).
 */
public final class AoScratch {
    public final float[] shape = new float[12];
    public final BitSet flags = new BitSet(3);
    public final Object face = new ModelBlockRenderer.AmbientOcclusionFace();
    public final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private boolean busy;

    private static final ThreadLocal<AoScratch> MINE = ThreadLocal.withInitial(AoScratch::new);

    public static AoScratch take() {
        AoScratch s = MINE.get();
        if (s.busy) return null;
        s.busy = true;
        java.util.Arrays.fill(s.shape, 0f);
        s.flags.clear();
        return s;
    }

    public static void give(AoScratch s) {
        s.busy = false;
    }
}
