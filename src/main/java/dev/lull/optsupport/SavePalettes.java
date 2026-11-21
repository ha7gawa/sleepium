package dev.lull.optsupport;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.IdMap;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;

/**
 * -Dlab.opt.SaveDirect (AsyncChunkSave): a section palette saved without a container copy and without DFU's codecs.
 *
 * <p>Main thread: instead of PalettedContainer.copy (a container, its data, its threading detector's semaphore, a copy
 * of the palette object, of the storage) a {@link Snapshot}: the storage's longs (cloned), its bits, and the palette's
 * values in id order (a global palette - the registry - is kept as it is). Vanilla palettes and storages of exactly
 * vanilla's classes only; anything else is copied as before.
 *
 * <p>Pool: PalettedContainer.pack done over the snapshot with per-thread scratch memory, step for step - the ids
 * unpacked, renumbered in order of first appearance by value identity (vanilla's HashMapPalette, its dummy resize
 * included: a value past 1 &lt;&lt; bits gets id 0 the first time), the serialized bits from the strategy, the data
 * packed by SimpleBitStorage - and the codec's tag ({palette: [element...], data: long[]}) built directly: each
 * element is a copy of what the very codec made for that value the first time it was seen (a template per value,
 * made by encoding a one-value container with the codec). The copy rebuilds each compound by putting the template's
 * keys in the template's iteration order into a new CompoundTag (both default-capacity HashMaps: the same buckets, the
 * same order within a bucket - the same bytes); strings and numbers are immutable and shared. The data's long[] is
 * the packed storage itself (the codec copies it once more through a LongStream).
 *
 * <p>{@code -Dlab.asyncsave.verify=true}: every palette also encoded by the codec on the main thread at save time and
 * compared byte for byte (AsyncChunkSave.fill); {@code -Dlab.asyncsave.verifyChunk=true}: whole chunks.
 */
public final class SavePalettes {
    private SavePalettes() {}

    public static final boolean ON = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.SaveDirect");

    public static final AtomicLong snapshots = new AtomicLong(), copies = new AtomicLong(), learned = new AtomicLong();

    /** value (BlockState, Holder) -> the codec's tag for it as a palette element; identity keys */
    private static final Map<Object, Tag> TEMPLATES = new ConcurrentHashMap<>();
    private static final int MAX_TEMPLATES = 1 << 16;

    /** what the pool needs of a container at save time */
    public static final class Snapshot {
        final IdMap<?> registry;
        final PalettedContainer.Strategy strategy;
        final int bits;
        /** the storage's longs (a clone), null for a zero-bit storage */
        final long[] raw;
        /** the palette's values by id (linear, hash map, single value palettes) */
        final Object[] values;
        /** a global palette (the registry: nothing to copy) */
        final Palette<?> global;

        Snapshot(IdMap<?> registry, PalettedContainer.Strategy strategy, int bits, long[] raw, Object[] values, Palette<?> global) {
            this.registry = registry;
            this.strategy = strategy;
            this.bits = bits;
            this.raw = raw;
            this.values = values;
            this.global = global;
        }

        Object valueFor(int id) {
            if (global != null) return global.valueFor(id);
            if (id >= 0 && id < values.length) return values[id];
            throw new MissingPaletteEntryException(id);
        }
    }

    /** main thread: a Snapshot, or (a storage or palette of another class) the container's copy as before */
    public static Object snapshot(PalettedContainer<?> pc) {
        PalettedContainer.Data<?> d = pc.data;
        BitStorage s = d.storage();
        Palette<?> p = d.palette();
        long[] raw;
        if (s.getClass() == SimpleBitStorage.class) raw = s.getRaw().clone();
        else if (s.getClass() == ZeroBitStorage.class) raw = null;
        else return copy(pc);
        Object[] values = null;
        Palette<?> global = null;
        Class<?> pk = p.getClass();
        if (pk == LinearPalette.class || pk == HashMapPalette.class) {
            int n = p.getSize();
            values = new Object[n];
            for (int i = 0; i < n; i++) values[i] = p.valueFor(i);
        } else if (pk == SingleValuePalette.class) {
            Object v;
            try {
                v = p.valueFor(0);
            } catch (IllegalStateException uninitialized) {
                return copy(pc);   // (copy throws as before)
            }
            values = new Object[] {v};
        } else if (pk == GlobalPalette.class) {
            global = p;
        } else {
            return copy(pc);
        }
        dev.lull.opt.SaveDirectAccessor a = (dev.lull.opt.SaveDirectAccessor) (Object) pc;
        snapshots.incrementAndGet();
        return new Snapshot(a.sleep$registry(), a.sleep$strategy(), s.getBits(), raw, values, global);
    }

    private static Object copy(PalettedContainer<?> pc) {
        copies.incrementAndGet();
        return pc.copy();
    }

    /** per pool thread: the ids, the renumbered values, the value -> new id map */
    private static final class Scratch {
        int[] ids = new int[4096];
        Object[] newValues = new Object[4096];
        /** a linear / hash map palette's old id -> its value's new id (-1: not asked yet) */
        int[] memo = new int[256];
        Reference2IntOpenHashMap<Object> map = newMap();

        static Reference2IntOpenHashMap<Object> newMap() {
            Reference2IntOpenHashMap<Object> m = new Reference2IntOpenHashMap<>();
            m.defaultReturnValue(-1);
            return m;
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** pool: the codec's tag for the snapshot */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static CompoundTag encode(Codec codec, Snapshot s) {
        Scratch sc = SCRATCH.get();
        int size = s.strategy.size();
        if (sc.ids.length < size) sc.ids = new int[size];
        if (sc.newValues.length < size) sc.newValues = new Object[size];
        int[] ids = sc.ids;
        Object[] newValues = sc.newValues;
        Reference2IntOpenHashMap<Object> map = sc.map;
        map.clear();
        int limit = 1 << s.bits, count = 0;
        if (s.raw == null) {
            // a zero-bit storage: every id 0 - vanilla's loop asks for one value, once
            Object v = s.valueFor(0);
            map.put(v, 0);
            newValues[count++] = v;
        } else {
            // PalettedContainer.pack: the storage unpacked
            new SimpleBitStorage(s.bits, size, s.raw).unpack(ids);
            // swapPalette with HashMapPalette(registry, storage bits, dummy resize).idFor(palette.valueFor(id)): new ids in
            // order of first appearance by value identity; an old id seen before answers its value's id directly (the same
            // answer idFor gives for a value it already holds)
            int[] memo = null;
            int memoLen = 0;
            if (s.values != null) {
                memoLen = s.values.length;
                if (sc.memo.length < s.values.length) sc.memo = new int[s.values.length];
                memo = sc.memo;
                java.util.Arrays.fill(memo, 0, s.values.length, -1);
            }
            int lastOld = -1, lastNew = -1;
            for (int k = 0; k < size; k++) {
                int l = ids[k];
                if (l != lastOld) {
                    lastOld = l;
                    int id = l >= 0 && l < memoLen ? memo[l] : -1;
                    if (id < 0) {
                        Object v = s.valueFor(l);
                        id = map.getInt(v);
                        if (id == -1) {
                            id = count;
                            map.put(v, id);
                            newValues[count++] = v;
                            if (memo != null) memo[l] = id;
                            if (id >= limit) id = 0;   // the dummy resize's answer, the first time only
                        } else if (memo != null) {
                            memo[l] = id;
                        }
                    }
                    lastNew = id;
                }
                ids[k] = lastNew;
            }
        }
        if (map.size() > 256) sc.map = Scratch.newMap();   // (clear walks the whole table: not a grown one every time)
        int j = ((dev.lull.opt.SaveDirectStrategyAccessor) (Object) s.strategy).sleep$bitsFor((IdMap) s.registry, count);
        ListTag palette = new ListTag();
        for (int i = 0; i < count; i++) {
            Object v = newValues[i];
            Tag t = TEMPLATES.get(v);
            if (t == null) t = learn(codec, s, v);
            palette.add(copy(t));
        }
        CompoundTag out = new CompoundTag();
        out.put("palette", palette);
        if (j != 0) {
            if (s.raw == null) java.util.Arrays.fill(ids, 0, size, 0);   // (not reached: one value packs to 0 bits)
            out.put("data", new LongArrayTag(new SimpleBitStorage(j, size, ids).getRaw()));
        }
        java.util.Arrays.fill(newValues, 0, count, null);
        return out;
    }

    /** the codec's element tag for a value: a one-value container encoded by the codec, its palette's element */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Tag learn(Codec codec, Snapshot s, Object v) {
        learned.incrementAndGet();
        DataResult r = codec.encodeStart(NbtOps.INSTANCE, new PalettedContainer((IdMap) s.registry, v, s.strategy));
        Tag enc = (Tag) r.getOrThrow();   // an element that does not encode fails the save, as the codec's whole encode would
        if (!(enc instanceof CompoundTag c && c.get("palette") instanceof ListTag l && l.size() == 1))
            throw new IllegalStateException("SaveDirect: unexpected palette encoding " + enc);
        Tag t = copy(l.get(0));
        if (r.isSuccess()) {
            if (TEMPLATES.size() > MAX_TEMPLATES) TEMPLATES.clear();
            TEMPLATES.putIfAbsent(v, t);
        }
        return t;
    }

    /** a copy with the same iteration order everywhere (see the class comment); immutable tags shared */
    static Tag copy(Tag t) {
        if (t instanceof CompoundTag c) {
            CompoundTag r = new CompoundTag();
            for (String k : c.getAllKeys()) r.put(k, copy(c.get(k)));
            return r;
        }
        if (t instanceof ListTag l) {
            ListTag r = new ListTag();
            for (int i = 0; i < l.size(); i++) r.add(copy(l.get(i)));
            return r;
        }
        if (t instanceof ByteArrayTag || t instanceof IntArrayTag || t instanceof LongArrayTag) return t.copy();
        return t;   // StringTag, numeric tags, EndTag: immutable
    }

    // ---- -Dlab.asyncsave.verify: a self test per strategy, the palettes a generated world rarely has
    private static final java.util.Set<Object> TESTED = ConcurrentHashMap.newKeySet();
    public static final AtomicLong selfTests = new AtomicLong(), selfTestFailures = new AtomicLong();

    /**
     * Once per strategy (with the first chunk saved): synthetic containers snapshot-encoded here and encoded by the
     * codec, compared byte for byte - 1 to 4096 distinct values (single value, linear, hash map and global palettes),
     * and containers read by the codec from tags whose palette names values more than once (a file's palette with
     * repeated or unknown entries: duplicates in a linear / hash map palette, collapsed into a global one).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void selfTest(Codec codec, Snapshot like) {
        if (!TESTED.add(like.strategy)) return;
        IdMap reg = like.registry;
        PalettedContainer.Strategy st = like.strategy;
        int size = st.size(), side = (int) Math.round(Math.cbrt(size)), n = reg.size();
        java.util.Random rnd = new java.util.Random(20261121L);
        for (int distinct : new int[] {1, 2, 3, 5, 16, 17, 40, 200, 256, 257, 600, 4096}) {
            int want = Math.min(Math.min(distinct, size), n);
            for (int rep = 0; rep < 3; rep++) {
                Object[] vals = pick(reg, want, rnd);
                PalettedContainer pc = new PalettedContainer(reg, vals[0], st);
                for (int i = 0; i < size; i++)
                    pc.getAndSetUnchecked(i % side, i / (side * side), (i / side) % side, vals[rep == 0 ? i % vals.length : rnd.nextInt(vals.length)]);
                compare(codec, pc, "distinct " + want + " rep " + rep);
            }
        }
        for (int entries : new int[] {3, 7, 20, 300}) {
            for (int rep = 0; rep < 3; rep++) {
                Object[] vals = pick(reg, Math.max(1, Math.min(entries - 1, n)), rnd);
                ListTag palette = new ListTag();
                for (int i = 0; i < entries; i++) {
                    Object v = vals[i < vals.length ? i : rnd.nextInt(vals.length)];   // the rest: repeats
                    palette.add(learn(codec, like, v));
                }
                int j = ((dev.lull.opt.SaveDirectStrategyAccessor) (Object) st).sleep$bitsFor(reg, entries);
                CompoundTag tag = new CompoundTag();
                tag.put("palette", palette);
                if (j != 0) {
                    int[] ids = new int[size];
                    for (int i = 0; i < size; i++) ids[i] = rnd.nextInt(entries);
                    tag.put("data", new LongArrayTag(new SimpleBitStorage(j, size, ids).getRaw()));
                }
                Object pc = codec.parse(NbtOps.INSTANCE, tag).getOrThrow();
                compare(codec, (PalettedContainer) pc, "repeated entries " + entries + " rep " + rep);
            }
        }
        System.out.println("[sleep/opt] SaveDirect self test (" + (size == 4096 ? "block states" : "biomes") + "): " + selfTests + " containers, "
                + selfTestFailures + " differ");
    }

    @SuppressWarnings("rawtypes")
    private static Object[] pick(IdMap reg, int want, java.util.Random rnd) {
        java.util.LinkedHashSet<Object> set = new java.util.LinkedHashSet<>();
        int n = reg.size();
        for (int tries = 0; set.size() < want && tries < want * 50; tries++) {
            Object v = reg.byId(rnd.nextInt(n));
            if (v != null) set.add(v);
        }
        return set.toArray();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void compare(Codec codec, PalettedContainer pc, String what) {
        selfTests.incrementAndGet();
        Object snap = snapshot(pc);
        if (!(snap instanceof Snapshot s)) {
            System.out.println("[sleep/opt] SaveDirect self test: " + what + " not snapshotted (" + pc.data.palette().getClass().getSimpleName() + ")");
            return;
        }
        byte[] mine = AsyncChunkSave.raw(encode(codec, s)), theirs = AsyncChunkSave.raw((CompoundTag) codec.encodeStart(NbtOps.INSTANCE, pc).getOrThrow());
        if (!java.util.Arrays.equals(mine, theirs) && selfTestFailures.incrementAndGet() <= 5)
            System.out.println("[sleep/opt] SaveDirect self test DIFFERS: " + what + " (" + pc.data.palette().getClass().getSimpleName() + ")");
    }

    /** a new server's registries bring new holders: forget the old ones */
    public static void clear() {
        TEMPLATES.clear();
    }

    public static String report() {
        return "palette snapshots " + snapshots + ", container copies " + copies + ", templates learned " + learned + " (kept " + TEMPLATES.size() + ")";
    }
}
