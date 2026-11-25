package dev.lull.sleepium.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.gen.structure.StructureBoundingBox;

/**
 * -Dsleep.opt.TickIndex (default on): WorldServer's scheduled block ticks (pendingTickListEntriesTreeSet) with an index
 * by chunk. Saving a chunk asks for its scheduled ticks (getPendingBlockUpdates - a box of the chunk and two blocks
 * into its -x / -z neighbours); vanilla walks every scheduled tick of the world for each chunk saved. World generation
 * schedules hundreds of thousands (fluids, falling blocks), and saving thousands of chunks then took 70 % of the server
 * thread (GenBench, 11-25). With the index the box asks only the chunks under it.
 *
 * <p>Same answer: the entries in the box, filtered by vanilla's test, in the set's own order (the natural order the
 * TreeSet iterates: time, priority, entry id - every entry has its own id, so the order is total) - the index holds the
 * same objects, by identity. Every way the set changes - add, remove, an iterator's remove, clear, pollFirst / Last -
 * updates the index.
 */
public final class IndexedTickSet extends TreeSet<NextTickListEntry> {
    private final Long2ObjectOpenHashMap<ReferenceOpenHashSet<NextTickListEntry>> byChunk = new Long2ObjectOpenHashMap<>();

    private static long key(BlockPos p) {
        return ((long) (p.getX() >> 4) << 32) ^ ((p.getZ() >> 4) & 0xFFFFFFFFL);
    }

    private void indexAdd(NextTickListEntry e) {
        byChunk.computeIfAbsent(key(e.position), k -> new ReferenceOpenHashSet<>()).add(e);
    }

    private void indexRemove(Object o) {
        if (!(o instanceof NextTickListEntry)) return;
        NextTickListEntry e = (NextTickListEntry) o;
        long k = key(e.position);
        ReferenceOpenHashSet<NextTickListEntry> s = byChunk.get(k);
        if (s != null && s.remove(e) && s.isEmpty()) byChunk.remove(k);
    }

    @Override
    public boolean add(NextTickListEntry e) {
        boolean r = super.add(e);
        if (r) indexAdd(e);
        return r;
    }

    @Override
    public boolean remove(Object o) {
        // the element the tree holds (equal by compareTo: the same id, so the same object as o)
        boolean r = super.remove(o);
        if (r) indexRemove(o);
        return r;
    }

    @Override
    public NextTickListEntry pollFirst() {
        NextTickListEntry e = super.pollFirst();
        if (e != null) indexRemove(e);
        return e;
    }

    @Override
    public NextTickListEntry pollLast() {
        NextTickListEntry e = super.pollLast();
        if (e != null) indexRemove(e);
        return e;
    }

    @Override
    public void clear() {
        super.clear();
        byChunk.clear();
    }

    @Override
    public Iterator<NextTickListEntry> iterator() {
        Iterator<NextTickListEntry> it = super.iterator();
        return new Iterator<NextTickListEntry>() {
            private NextTickListEntry last;

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public NextTickListEntry next() {
                return last = it.next();
            }

            @Override
            public void remove() {
                it.remove();
                indexRemove(last);
            }
        };
    }

    /**
     * The entries in the box by vanilla's test (x in [minX, maxX), z in [minZ, maxZ)), in the set's order; with remove,
     * taken out of the set (the caller removes them from its hash set, as vanilla does). Null when there are none.
     */
    public List<NextTickListEntry> inBox(StructureBoundingBox bb, boolean remove) {
        List<NextTickListEntry> out = null;
        int cx0 = bb.minX >> 4, cx1 = (bb.maxX - 1) >> 4, cz0 = bb.minZ >> 4, cz1 = (bb.maxZ - 1) >> 4;
        if (bb.maxX <= bb.minX || bb.maxZ <= bb.minZ) return null;
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) {
                ReferenceOpenHashSet<NextTickListEntry> s = byChunk.get(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
                if (s == null) continue;
                for (NextTickListEntry e : s) {
                    BlockPos p = e.position;
                    if (p.getX() >= bb.minX && p.getX() < bb.maxX && p.getZ() >= bb.minZ && p.getZ() < bb.maxZ) {
                        if (out == null) out = new ArrayList<>();
                        out.add(e);
                    }
                }
            }
        if (out == null) return null;
        Collections.sort(out);   // the TreeSet's iteration order
        if (remove)
            for (NextTickListEntry e : out) remove(e);
        return out;
    }

    /** many chunks in the box: the vanilla walk is as good */
    public static boolean small(StructureBoundingBox bb) {
        long w = ((bb.maxX - 1) >> 4) - (bb.minX >> 4) + 1, d = ((bb.maxZ - 1) >> 4) - (bb.minZ >> 4) + 1;
        return w > 0 && d > 0 && w * d <= 64;
    }
}
