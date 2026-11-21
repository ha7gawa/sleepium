package dev.lull.optsupport;

import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.renderer.RenderType;

/**
 * -Dlab.opt.RenderTypeIterMixin (11-18): SectionCompiler.compile walks each block model's ChunkRenderTypeSet with a new
 * iterator a block (11.7 MB/s in the zoo orbit, the meshing workers' second garbage). Here the walk uses one of a ring
 * of 4 iterators of the thread, reset over the set's bits: the same render types in the same order (the bits' order
 * over RenderType.chunkBufferLayers(), as NeoForge's own iterator). The compile's loop is not nested; on other threads,
 * and for the none / all sets, NeoForge's iterator as before.
 */
public final class TypeIter implements Iterator<RenderType> {
    private static final RenderType[] TYPES = RenderType.chunkBufferLayers().toArray(new RenderType[0]);
    private BitSet bits;
    private int index;

    @Override
    public boolean hasNext() {
        return index >= 0;
    }

    @Override
    public RenderType next() {
        RenderType t = TYPES[index];
        index = bits.nextSetBit(index + 1);
        return t;
    }

    private static final class Ring {
        final TypeIter[] its = {new TypeIter(), new TypeIter(), new TypeIter(), new TypeIter()};
        int next;
    }

    /** an iterator over bits (a ChunkRenderTypeSet's), or null off our threads */
    public static Iterator<RenderType> over(BitSet bits) {
        Object[] s = ThreadSlots.mine();
        if (s == null) return null;
        Ring r = (Ring) s[ThreadSlots.TYPES];
        if (r == null) s[ThreadSlots.TYPES] = r = new Ring();
        TypeIter it = r.its[r.next++ & 3];
        it.bits = bits;
        it.index = bits.nextSetBit(0);
        return it;
    }

    static {
        List<RenderType> l = RenderType.chunkBufferLayers();
        if (l.size() != TYPES.length) throw new IllegalStateException("render types changed");
    }
}
