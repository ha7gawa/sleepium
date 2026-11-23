package dev.lull.optsupport.sleep;

import net.minecraft.world.entity.Entity;

/**
 * SpecTicks (lab, notes/99): one speculative entity tick's undo log. While the tick runs (WB.TX), every write into a
 * root object (WriteBarrier: the entity, its goals, controls, navigation, path, synched data...) reaches
 * {@link #wrote}: on the first write the object's fields are copied (SpecUndo's shape of its class) and its watch word
 * set to this transaction - later writes cost a compare. Every mutating call on a collection, map, iterator or atomic
 * in the barrier classes reaches {@link #mutated}: on the first one its content is copied (a view or an iterator: its
 * backing collection). {@link #restore} puts all of it back; {@link #release} clears the watch words.
 *
 * <p>Conflicts found here: a write into an object another transaction copied (two ticks touched one object - both
 * roll back; the other's copy holds the state before either), a write into another entity, a write into an object
 * someone else watches (a sleeping mob's part), a draw from the level's random source, a mutation through a view
 * whose collection cannot be found. Until released, a finished tick's objects keep its watch word: a later tick that
 * writes into them (a push into a neighbour that already ticked) is a conflict too.
 */
public final class SpecTx {
    static final sun.misc.Unsafe U = SnapCompiler.U;
    private static final ThreadLocal<SpecTx[]> CUR = ThreadLocal.withInitial(() -> new SpecTx[1]);

    /** the server thread (its transaction in a field: no ThreadLocal on the write barrier's path) */
    public static volatile Thread mainThread;
    private static SpecTx mainCur;
    private static Object mainTok;

    /** the write barrier's fast path: the word is the running tick's own token (an object it already copied) */
    public static boolean mine(Object w) {
        Thread t = Thread.currentThread();
        if (t instanceof dev.lab.runtime.LabRuntime.LabThread lt) return lt.slots[dev.lull.optsupport.ThreadSlots.SPEC_TOK] == w;
        return t == mainThread && mainTok == w;
    }

    /** the transaction of the tick running on this thread (null: none) - a field of our pool's threads */
    public static SpecTx current() {
        Thread t = Thread.currentThread();
        if (t instanceof dev.lab.runtime.LabRuntime.LabThread lt) return (SpecTx) lt.slots[dev.lull.optsupport.ThreadSlots.SPEC_TX];
        if (t == mainThread) return mainCur;
        return CUR.get()[0];
    }

    private static void setCurrent(SpecTx tx) {
        Thread t = Thread.currentThread();
        if (t instanceof dev.lab.runtime.LabRuntime.LabThread lt) {
            lt.slots[dev.lull.optsupport.ThreadSlots.SPEC_TX] = tx;
            lt.slots[dev.lull.optsupport.ThreadSlots.SPEC_TOK] = tx == null ? null : tx.tok;
        } else if (t == mainThread) {
            mainCur = tx;
            mainTok = tx == null ? null : tx.tok;
        }
        else CUR.get()[0] = tx;
    }

    public boolean active;
    /** the group this tick runs in (ticks of a group run one after another in walk order on one thread), -1 none */
    public int group = -1;
    /** a group lookup for entities of the run (SpecTicks), -1 when not in one */
    public static volatile java.util.function.ToIntFunction<Entity> GROUP;
    /** serial: a re-run in walk order (writes into other entities are allowed; into a later tick's objects not) */
    public boolean serial;
    public Entity entity;
    public int index;
    public boolean conflict;
    public String why;
    /** in a serial re-run: the transactions of the run's ticks after this one that still hold their objects (cascade) */
    public final java.util.ArrayList<SpecTx> cascade = new java.util.ArrayList<>(2);
    /** the level's random source's seed (a draw by a parallel tick is a conflict - the order of draws) */
    public static volatile Object levelSeed;

    Object[] objs = new Object[16];
    SpecUndo.Shape[] shapes = new SpecUndo.Shape[16];
    long[][] prims = new long[16][];
    Object[][] refs = new Object[16][];
    int[] roots = new int[16];
    int n;
    final SpecUndo.Snap cs = new SpecUndo.Snap();
    int nc;
    java.util.IdentityHashMap<Object, Boolean> many;
    public static long writes, copies, contents, conflicts;   // (writes / copies / contents: summed per transaction at release - no shared counter on the hot path)

    // ------------------------------------------------------------------ what the tick read: entity queries, path types

    /** the boxes of its entity queries (minX, minY, minZ, maxX, maxY, maxZ) */
    public double[] q = new double[48];
    public int nq;
    /** vanilla's path type cache as the tick used it: position, value, whether it came from the shared cache */
    public long[] pathKey = new long[32];
    public net.minecraft.world.level.pathfinder.BlockPathTypes[] pathVal = new net.minecraft.world.level.pathfinder.BlockPathTypes[32];
    public byte[] pathKind = new byte[32];
    public int np;

    public static void queried(net.minecraft.world.phys.AABB box) {
        SpecTx tx = current();
        if (tx == null || !tx.active) return;
        if (tx.nq + 6 > tx.q.length) tx.q = java.util.Arrays.copyOf(tx.q, tx.q.length * 2);
        double[] a = tx.q;
        int i = tx.nq;
        a[i] = box.minX; a[i + 1] = box.minY; a[i + 2] = box.minZ; a[i + 3] = box.maxX; a[i + 4] = box.maxY; a[i + 5] = box.maxZ;
        tx.nq = i + 6;
    }

    public void path(long key, net.minecraft.world.level.pathfinder.BlockPathTypes v, byte kind) {
        if (np == pathKey.length) {
            pathKey = java.util.Arrays.copyOf(pathKey, np * 2);
            pathVal = java.util.Arrays.copyOf(pathVal, np * 2);
            pathKind = java.util.Arrays.copyOf(pathKind, np * 2);
        }
        pathKey[np] = key;
        pathVal[np] = v;
        pathKind[np] = kind;
        np++;
    }

    /** this tick's own path type computes (its view of the cache: shared as at the block's start + these) */
    // as vanilla's cache: one entry per slot (index = mix(position) & 4095) - a compute replaces the slot's entry
    final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap ownKey = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<net.minecraft.world.level.pathfinder.BlockPathTypes> ownVal = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    /** this tick's own entry in slot idx: its value when it holds key; null when it holds another; NONE when untouched */
    public Object ownSlot(int idx, long key) {
        if (ownKey.isEmpty() || !ownKey.containsKey(idx)) return NONE;
        return ownKey.get(idx) == key ? ownVal.get(idx) : null;
    }

    public static final Object NONE = new Object();

    public void ownPut(int idx, long key, net.minecraft.world.level.pathfinder.BlockPathTypes v) {
        ownKey.put(idx, key);
        ownVal.put(idx, v);
    }

    /** path log kinds */
    public static final byte SHARED = 0, OWN = 1, COMPUTED = 2;

    /**
     * the watch word a transaction puts on the objects it copied: live until the transaction is released or restored
     * (then the objects' words are stale - read as unwatched, cleared by the next write outside a run)
     */
    public static final class Token {
        final SpecTx tx;
        volatile boolean live = true;

        Token(SpecTx tx) {
            this.tx = tx;
        }
    }

    Token tok;
    long copiesMine, contentsMine;

    public long copiesMine() { return copiesMine; }

    public long contentsMine() { return contentsMine; }

    /** the entity's section as the tick began / as of its last storage move; section changes in this tick */
    long section;
    int sectionChanges;

    /** a storage move (deferred): a second section change in one tick would reorder the storage unlike vanilla - a conflict */
    public void moved(Entity e) {
        long s = net.minecraft.core.SectionPos.asLong(e.blockPosition());
        if (s == section) return;
        section = s;
        if (++sectionChanges > 1) conflict("changed section twice");
    }

    public void begin(Entity e, int index, boolean serial) {
        section = net.minecraft.core.SectionPos.asLong(e.blockPosition());
        sectionChanges = 0;
        tok = new Token(this);
        nq = 0;
        np = 0;
        lastContent = null;
        if (!ownKey.isEmpty()) {
            ownKey.clear();
            ownVal.clear();
        }
        this.entity = e;
        this.index = index;
        this.serial = serial;
        conflict = false;
        why = null;
        cascade.clear();
        n = 0;
        nc = 0;
        if (many != null) many.clear();
        active = true;
        setCurrent(this);
    }

    public void end() {
        active = false;
        setCurrent(null);
    }

    public void conflict(String what) {
        if (!conflict) {
            conflict = true;
            why = what;
            conflicts++;
        }
    }

    // ------------------------------------------------------------------ fields

    void wrote(Object o, Object w, int root) {
        Object seen = w;   // (the word as read: the claim swaps exactly it - null or a released transaction's stale token)
        if (w == tok) return;
        if (w instanceof Token t) {
            if (!t.live || serial && COMMITTED != null && COMMITTED.test(t.tx)) w = null;   // (stale: released, or a tick already committed - its release comes after the commit loop)
            else if (group >= 0 && t.tx.group == group && !serial) {
                // an earlier tick of this group wrote it (a push): ours on top - copied as it is now, the word stays
                copy(o, root, false, null);
                return;
            } else {
                SpecTx other = t.tx;
            if (serial) {
                // a re-run in walk order writing into a tick that still holds its objects: a later tick of the run (its
                // speculative result must be undone first) - or an earlier one already committed... (released, never here)
                if (!cascade.contains(other)) cascade.add(other);
                conflict("write into a later tick's object");
            } else {
                conflict("write into another tick's object");
                other.conflict("another tick wrote into its object");
            }
            return;   // (the other's copy holds the state from before both)
            }
        }
        if (w != null) {
            conflict("write into a watched object");
            // (a sleeping mob's part, watched by entity sleep: never claimed, so two ticks could copy it at once and
            // their undos put back each other's values - a speculative tick stops before the write; a re-run writes)
            if (!serial) throw SpecAbort.INSTANCE;
        } else if (o instanceof Entity en && en != entity && !serial) {
            java.util.function.ToIntFunction<Entity> g = GROUP;
            if (group < 0 || g == null || g.applyAsInt(en) != group) {
                conflict("writes another entity");
                java.util.function.Consumer<Entity> v = VICTIM;
                if (v != null) v.accept(en);   // (its tick may have read the write half done: invalid too)
            }
        }
        if (!copy(o, root, w == null, seen)) wrote(o, U.getObject(o, WB.offset(root)), root);   // (lost the claim: as the winner's)
    }

    /** a run entity another tick wrote into (SpecTicks marks its tick invalid) */
    public static volatile java.util.function.Consumer<Entity> VICTIM;
    /** a transaction whose tick is committed (SpecTicks: released in one parallel pass after the commit loop) */
    public static volatile java.util.function.Predicate<SpecTx> COMMITTED;
    static final boolean YOUNG_REFS = !"false".equals(System.getProperty("lab.spec.youngRefs"));

    /** o's fields into the log; with mark, the object claimed for this tick (atomically: false when another tick won it) */
    private boolean copy(Object o, int root, boolean mark, Object expected) {
        int i = n++;
        if (i == objs.length) {
            int len = i * 2;
            objs = java.util.Arrays.copyOf(objs, len);
            shapes = java.util.Arrays.copyOf(shapes, len);
            prims = java.util.Arrays.copyOf(prims, len);
            refs = java.util.Arrays.copyOf(refs, len);
            roots = java.util.Arrays.copyOf(roots, len);
        }
        SpecUndo.Shape sh = SpecUndo.shape(o.getClass());
        int f = sh.f.length;
        long[] p = prims[i];
        Object[] r = refs[i];
        if (p == null || p.length < f) prims[i] = p = new long[Math.max(f, 8)];
        // a fresh array each copy: young, so its reference stores skip the collector's card marking (a reused one is old)
        refs[i] = r = YOUNG_REFS ? new Object[f] : r == null || r.length < f ? new Object[Math.max(f, 8)] : r;
        if (f > 0) sh.sn.both(o, p, r);
        objs[i] = o;
        shapes[i] = sh;
        roots[i] = mark ? root : -1;
        if (mark && !U.compareAndSwapObject(o, WB.offset(root), expected, tok)) {
            n--;   // (another tick claimed it between our read and now: our copy may be torn - dropped)
            return false;
        }
        return true;
    }

    /** an object about to be written past the write barrier (Unsafe, a memo's write-back): copied first */
    public static void touch(Object o) {
        SpecTx tx = current();
        if (tx == null || !tx.active) return;
        int root = WB.rootIndex(o.getClass());
        if (root < 0) return;
        Object w = U.getObject(o, WB.offset(root));
        if (w instanceof Token && mine(w)) return;
        tx.wrote(o, w, root);
    }

    // ------------------------------------------------------------------ collections, maps, atomics

    static void mutated(Object c) {
        SpecTx tx = current();
        if (tx != null && tx.active) tx.content(c);
    }

    private Object lastContent;

    void content(Object c) {
        if (c == lastContent) return;   // (the same collection again: a random's seed drawn again and again)
        if (c == levelSeed) {
            conflict("a draw from the level's random source");
            if (!serial) throw SpecAbort.INSTANCE;   // (before the draw: the level's sequence stays untouched)
            return;
        }
        Object b = SpecUndo.backing(c);
        if (b == null) {
            conflict("a mutation through a view of an unknown collection");
            return;
        }
        if (nc < 16) {
            for (int i = 0; i < nc; i++) if (cs.cObj[i] == b) return;
        } else {
            if (many == null) many = new java.util.IdentityHashMap<>();
            if (many.isEmpty()) for (int i = 0; i < nc; i++) many.put(cs.cObj[i], Boolean.TRUE);
            if (many.put(b, Boolean.TRUE) != null) return;
        }
        SpecUndo.addContent(cs, nc++, b);
        lastContent = c;
    }

    // ------------------------------------------------------------------ undo / release

    public static long restores;

    /** everything this tick changed put back as it was before it (watch words cleared) */
    public void restore() {
        restoreFields();
        restoreContentsAndRelease();
    }

    /**
     * the copied objects' fields put back (an object is in one transaction's log only - a claim is atomic - so groups
     * may do this in parallel); then, in walk order from the latest, restoreContentsAndRelease (a collection has no
     * claim: two ticks of different groups may both hold a copy of one)
     */
    public void restoreFields() {
        restores++;
        for (int i = n - 1; i >= 0; i--) {
            Object o = objs[i];
            SpecUndo.Shape sh = shapes[i];
            long[] p = prims[i];
            Object[] r = refs[i];
            if (sh.f.length > 0 && !sh.sn.same(o, p, r)) for (int k = 0; k < sh.f.length; k++) sh.sn.put(o, k, p[k], r[k]);
        }
    }

    public void restoreContentsAndRelease() {
        cs.nc = nc;
        SpecUndo.restoreContents(cs);
        release();
    }

    /** the objects' watch words cleared, references dropped (the tick's result stays) */
    public void release() {
        if (tok != null) tok.live = false;
        copiesMine += n;
        contentsMine += nc;   // (the objects' words go stale; the copies are overwritten by later ticks)
        n = 0;
        nc = 0;
        entity = null;
        cascade.clear();
    }

    public int objects() {
        return n;
    }

    public int contentsCopied() {
        return nc;
    }

    /** thrown out of a parallel tick that must not go on (a world write, a spawn, a draw from the level's random) */
    public static final class SpecAbort extends RuntimeException {
        public static final SpecAbort INSTANCE = new SpecAbort();

        private SpecAbort() {
            super("[sleep/spec] speculative tick aborted", null, false, false);
        }
    }
}
