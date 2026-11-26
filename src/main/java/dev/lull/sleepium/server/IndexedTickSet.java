package dev.lull.sleepium.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.world.NextTickListEntry;

/**
 * -Dsleep.opt.TickIndex (default on; 1.12.2's, on 1.7.10's names): WorldServer's scheduled block ticks
 * (pendingTickListEntriesTreeSet) with an index by chunk. Saving a chunk asks for its scheduled ticks
 * (getPendingBlockUpdates - the chunk and two blocks into its -x / -z neighbours); vanilla walks every scheduled tick of
 * the world for each chunk saved. With the index the box asks only the chunks under it.
 *
 * <p>Same answer: the entries in the box, by vanilla's test, in the set's own order (the TreeSet's: time, priority,
 * entry id - every entry has its own id, so the order is total); the index holds the same objects, by identity. Every
 * way the set changes - add, remove, an iterator's remove, clear, pollFirst / Last - updates the index.
 */
public final class IndexedTickSet extends TreeSet<NextTickListEntry> {
    private final HashMap<Long, Set<NextTickListEntry>> byChunk = new HashMap<>();   // (1.7.10 ships no fastutil)

    private static long key(int x, int z) {
        return ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
    }

    private void indexAdd(NextTickListEntry e) {
        long k = key(e.xCoord, e.zCoord);
        Set<NextTickListEntry> s = byChunk.get(k);
        if (s == null) byChunk.put(k, s = Collections.newSetFromMap(new IdentityHashMap<>()));
        s.add(e);
    }

    private void indexRemove(Object o) {
        if (!(o instanceof NextTickListEntry)) return;
        NextTickListEntry e = (NextTickListEntry) o;
        long k = key(e.xCoord, e.zCoord);
        Set<NextTickListEntry> s = byChunk.get(k);
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
     * The entries with x in [x0, x1) and z in [z0, z1) (vanilla's test), in the set's order; with remove, taken out of
     * the set (the caller removes them from its hash set, as vanilla does). Null when there are none.
     */
    public List<NextTickListEntry> inBox(int x0, int x1, int z0, int z1, boolean remove) {
        if (x1 <= x0 || z1 <= z0) return null;
        List<NextTickListEntry> out = null;
        for (int cx = x0 >> 4; cx <= (x1 - 1) >> 4; cx++)
            for (int cz = z0 >> 4; cz <= (z1 - 1) >> 4; cz++) {
                Set<NextTickListEntry> s = byChunk.get(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
                if (s == null) continue;
                for (NextTickListEntry e : s) {
                    if (e.xCoord >= x0 && e.xCoord < x1 && e.zCoord >= z0 && e.zCoord < z1) {
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
}
