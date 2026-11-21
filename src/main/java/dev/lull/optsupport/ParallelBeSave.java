package dev.lull.optsupport;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * -Dlab.opt.ParallelBeSave (notes/88): a chunk's block entities made into NBT on the server's pool while the main thread
 * waits (fork-join at ChunkSerializer.write's block-entity loop). The world is not ticking then: every block entity's
 * state is what vanilla's serial loop would read. Modded factories mark their chunks dirty every tick and each such
 * chunk is saved every 10 s (ChunkMap's eager-save cooldown); the mods' saveAdditional was ~all of that save's main time.
 *
 * <p>The loop itself stays vanilla's: the same position set, iterated in the same order, each entry put in the list in
 * order; only the tags of trusted block entities come from the pool instead of being made at that moment.
 *
 * <p>Which block entities go to the pool - a per class verdict from evidence in this JVM:
 * <ul>
 *   <li>LEARNING: the first saves of a class are made on the pool AND then the vanilla way on the main thread at the loop
 *   (that one is saved); both tags compared byte for byte (NbtIo.write: key order too). After {@link #LEARN} chunk saves
 *   with at least {@link #LEARN_MIN} equal tags and no difference the class is TRUSTED.</li>
 *   <li>TRUSTED: made on the pool, the pool's tag saved.</li>
 *   <li>SERIAL forever (this JVM): any difference, any exception on the pool, any touch of the level from a pool save
 *   (Level.getBlockEntity, ServerChunkCache.getChunk / getChunkNow, a task handed to an event loop - off the main thread
 *   those return other values or block on the waiting main thread: ParallelBeSaveGuardMixin and friends throw
 *   {@link Touch} there). A failed pool save is redone the vanilla way at the loop.</li>
 * </ul>
 * Never on the pool: a chunk with a pending (not yet created) or removed block entity at a saved position (vanilla's
 * loop creates / drops them: side effects in the loop's order), a block entity with data attachments (other mods'
 * serializers), a LevelChunk subclass, LevelChunk.getBlockEntityNbtForSaving changed by another mod's mixin (its change
 * would be skipped), and anything off the server thread. Sleeping block entities (BE sleep) are materialized on the main
 * thread first, in the loop's order (the pool's saveWithFullMetadata hook then finds nothing to do).
 *
 * <p>-Dlab.besave.verify=true: every pool tag also compared with the vanilla save at the loop, and the vanilla tag saved.
 */
public final class ParallelBeSave {
    private ParallelBeSave() {}

    public static final boolean ON = Boolean.getBoolean("lab.opt.ParallelBeSave");
    public static final boolean VERIFY = Boolean.getBoolean("lab.besave.verify");
    /** chunk saves of a class compared before it is trusted */
    static final int LEARN = Integer.getInteger("lab.besave.learn", 3);
    /** equal tags of a class needed before it is trusted */
    static final int LEARN_MIN = Integer.getInteger("lab.besave.learnMin", 8);
    /** fewer pool-eligible block entities in a chunk: all serial (the fork costs more than it saves) */
    static final int MIN_FORK = Integer.getInteger("lab.besave.min", 4);
    /** pool helpers besides the main thread */
    static final int HELPERS = Integer.getInteger("lab.besave.helpers", 16);

    static final int LEARNING = 0, TRUSTED = 1, SERIAL = 2;
    private static final byte M_MAIN = 0, M_POOL = 1, M_LEARN = 2;

    public static final class TypeState {
        final String name;
        volatile int verdict = LEARNING;
        final AtomicInteger learnSaves = new AtomicInteger();
        final AtomicInteger equal = new AtomicInteger();
        volatile String why = "";

        TypeState(Class<?> c) {
            this.name = c.getName();
        }
    }

    private static final ClassValue<TypeState> TYPES = new ClassValue<>() {
        @Override
        protected TypeState computeValue(Class<?> type) {
            TypeState s = new TypeState(type);
            ALL.add(s);
            return s;
        }
    };
    private static final Set<TypeState> ALL = ConcurrentHashMap.newKeySet();

    public static final AtomicLong forks = new AtomicLong(), pooled = new AtomicLong(), learned = new AtomicLong(), learnEqual = new AtomicLong(),
            learnDiff = new AtomicLong(), failures = new AtomicLong(), touches = new AtomicLong(), redone = new AtomicLong(),
            verified = new AtomicLong(), verifyDiff = new AtomicLong(), notForked = new AtomicLong(), planMisses = new AtomicLong();

    /** thrown on a pool save's touch of the level (see the guard mixins); an Error so mods' catch (Exception) let it pass */
    public static final class Touch extends Error {
        Touch(String what) {
            super(what, null, false, false);
        }
    }

    /** true while a fork's bodies run (read first by the guards: a plain static, published by the pool's job handoff) */
    public static boolean active;
    private static final ThreadLocal<Boolean> IN_BODY = new ThreadLocal<>();

    /** the guards: called from Level.getBlockEntity, ServerChunkCache.getChunk / getChunkNow, BlockableEventLoop.tell */
    public static void touch(String what) {
        if (IN_BODY.get() != null) throw new Touch(what);
    }

    private static volatile Boolean usable;

    private static boolean usable() {
        Boolean u = usable;
        if (u == null) {
            boolean ok = !MixinTouch.touched("net/minecraft/world/level/chunk/LevelChunk", "getBlockEntityNbtForSaving")
                    && !MixinTouch.redirected("net/minecraft/world/level/chunk/storage/ChunkSerializer", "write", "getBlockEntityNbtForSaving")
                    && !MixinTouch.redirected("net/minecraft/world/level/chunk/storage/ChunkSerializer", "write", "getBlockEntitiesPos");
            if (!ok) System.out.println("[sleep/opt] parallel block entity saves off: another mod changes LevelChunk.getBlockEntityNbtForSaving or its call in ChunkSerializer.write");
            usable = u = ok;
        }
        return u;
    }

    /** one chunk's block-entity loop: positions in the loop's order, the pool's tags */
    private static final class Plan {
        final ChunkAccess chunk;
        final BlockPos[] pos;
        final BlockEntity[] be;
        final TypeState[] type;
        final byte[] mode;
        final CompoundTag[] out;
        final boolean[] failed;
        int cursor;

        Plan(ChunkAccess chunk, int n) {
            this.chunk = chunk;
            pos = new BlockPos[n];
            be = new BlockEntity[n];
            type = new TypeState[n];
            mode = new byte[n];
            out = new CompoundTag[n];
            failed = new boolean[n];
        }
    }

    /** the server thread's current plan (only the server thread makes plans) */
    private static Plan plan;

    /** ChunkSerializer.write's getBlockEntitiesPos: decide and run the pool part; the set is returned unchanged. */
    public static Set<BlockPos> prepare(ChunkAccess chunk, Set<BlockPos> set) {
        plan = null;
        if (!ON || set.size() < MIN_FORK || chunk.getClass() != LevelChunk.class || SaveWrite.vanillaNow()) return set;
        LevelChunk lc = (LevelChunk) chunk;
        if (!(lc.getLevel() instanceof ServerLevel sl) || !sl.getServer().isSameThread() || !usable()) return set;
        long t0 = SaveStats.ON ? System.nanoTime() : 0, b0 = SaveStats.ON ? SaveStats.bytes() : 0;
        java.util.Map<BlockPos, BlockEntity> map = lc.getBlockEntities();
        int n = set.size();
        Plan p = new Plan(chunk, n);
        int[] work = new int[n];
        int w = 0, i = 0;
        for (BlockPos pos : set) {
            BlockEntity be = map.get(pos);
            if (be == null || be.isRemoved()) {   // pending or removed: vanilla's loop creates / drops it - the whole chunk vanilla
                notForked.incrementAndGet();
                return set;
            }
            p.pos[i] = pos;
            p.be[i] = be;
            TypeState ts = TYPES.get(be.getClass());
            p.type[i] = ts;
            int v = ts.verdict;
            byte m = v == SERIAL || be.hasAttachments() ? M_MAIN : v == TRUSTED ? M_POOL : M_LEARN;
            p.mode[i] = m;
            if (m != M_MAIN) work[w++] = i;
            i++;
        }
        if (w < MIN_FORK) {
            notForked.incrementAndGet();
            return set;
        }
        // BE sleep: lazily advanced clocks put in place here, in the loop's order (the hook at saveWithFullMetadata's
        // head would do it on the pool)
        for (int k = 0; k < w; k++)
            if (p.be[work[k]] instanceof dev.lull.optsupport.sleep.SleeperHolder) dev.lull.optsupport.sleep.SleepEngine.materialize(p.be[work[k]]);
        HolderLookup.Provider reg = lc.getLevel().registryAccess();
        final int[] jobs = work;
        forks.incrementAndGet();
        active = true;
        try {
            ServerReactor.pool().forRange(w, 1, HELPERS, k -> body(p, jobs[k], reg));
        } finally {
            active = false;
        }
        plan = p;
        if (SaveStats.ON) SaveStats.add(SaveStats.BE, b0, t0);
        return set;
    }

    private static void body(Plan p, int i, HolderLookup.Provider reg) {
        long t = SaveStats.ON ? System.nanoTime() : 0, b = SaveStats.ON ? SaveStats.bytes() : 0;
        IN_BODY.set(Boolean.TRUE);
        try {
            CompoundTag tag = p.be[i].saveWithFullMetadata(reg);
            tag.putBoolean("keepPacked", false);
            p.out[i] = tag;
        } catch (Touch e) {
            p.failed[i] = true;
            touches.incrementAndGet();
            serial(p.type[i], "touched the level on the pool: " + e.getMessage());
        } catch (Throwable e) {
            p.failed[i] = true;
            failures.incrementAndGet();
            serial(p.type[i], "threw on the pool: " + e);
        } finally {
            IN_BODY.remove();
        }
        if (SaveStats.ON) SaveStats.type(p.be[i].getClass(), b, t, true);
    }

    private static void serial(TypeState ts, String why) {
        if (ts.verdict != SERIAL) {
            ts.verdict = SERIAL;
            ts.why = why;
            System.out.println("[sleep/opt] parallel block entity saves: " + ts.name + " serial from now on (" + why + ")");
        }
    }

    /**
     * ChunkSerializer.write's getBlockEntityNbtForSaving, at the loop: the pool's tag of a trusted block entity, else
     * vanilla's call (and, learning or verifying, the two compared).
     */
    public static CompoundTag nbt(ChunkAccess chunk, BlockPos pos, java.util.function.Supplier<CompoundTag> vanilla) {
        Plan p = plan;
        if (p == null || p.chunk != chunk) return timed(chunk, pos, vanilla);
        int i = p.cursor;
        if (i >= p.pos.length || !p.pos[i].equals(pos)) {   // not the loop we planned for (should not happen): vanilla from here
            planMisses.incrementAndGet();
            plan = null;
            return timed(chunk, pos, vanilla);
        }
        p.cursor = i + 1;
        if (p.cursor == p.pos.length) plan = null;
        byte m = p.mode[i];
        if (m == M_MAIN) return timed(chunk, pos, vanilla);
        if (p.failed[i]) {
            redone.incrementAndGet();
            return timed(chunk, pos, vanilla);
        }
        if (m == M_POOL && !VERIFY) {
            pooled.incrementAndGet();
            return p.out[i];
        }
        CompoundTag v = timed(chunk, pos, vanilla);
        boolean same = v != null && java.util.Arrays.equals(AsyncChunkSave.raw(v), AsyncChunkSave.raw(p.out[i]));
        TypeState ts = p.type[i];
        if (m == M_LEARN) {
            learned.incrementAndGet();
            if (same) {
                learnEqual.incrementAndGet();
                ts.equal.incrementAndGet();
            } else {
                learnDiff.incrementAndGet();
                serial(ts, "pool tag differs from the vanilla save" + diff(v, p.out[i]));
            }
            // the last of this class in this chunk counts one learning save
            boolean last = true;
            for (int k = i + 1; k < p.pos.length; k++) if (p.type[k] == ts && p.mode[k] == M_LEARN) { last = false; break; }
            if (last && ts.verdict == LEARNING && ts.learnSaves.incrementAndGet() >= LEARN && ts.equal.get() >= LEARN_MIN) ts.verdict = TRUSTED;
        } else {
            verified.incrementAndGet();
            if (!same && verifyDiff.incrementAndGet() <= 10)
                System.out.println("[sleep/opt] parallel block entity saves VERIFY: " + ts.name + " at " + pos + " differs" + diff(v, p.out[i]));
        }
        return v;
    }

    private static CompoundTag timed(ChunkAccess chunk, BlockPos pos, java.util.function.Supplier<CompoundTag> vanilla) {
        if (!SaveStats.ON) return vanilla.get();
        long t = System.nanoTime(), b = SaveStats.bytes();
        try {
            return vanilla.get();
        } finally {
            BlockEntity be = chunk instanceof LevelChunk lc ? lc.getBlockEntities().get(pos) : null;
            SaveStats.type(be == null ? Void.class : be.getClass(), b, t, false);
            SaveStats.add(SaveStats.BE, b, t);
        }
    }

    private static String diff(CompoundTag a, CompoundTag b) {
        if (a == null || b == null) return ": " + (a == null ? "vanilla null" : "pool null");
        try {
            String d = ChunkIo.diff(ChunkIo.canonical(a), ChunkIo.canonical(b));
            return d.isEmpty() ? ": same content, other key order" : ": " + (d.length() > 300 ? d.substring(0, 300) + "..." : d);
        } catch (Exception e) {
            return ": " + e;
        }
    }

    public static String report() {
        int tr = 0, se = 0, le = 0;
        StringBuilder serial = new StringBuilder();
        for (TypeState s : ALL) {
            if (s.verdict == TRUSTED) tr++;
            else if (s.verdict == SERIAL) {
                se++;
                if (serial.length() < 2000) serial.append("\n  serial ").append(s.name).append(": ").append(s.why);
            } else le++;
        }
        return "parallel block entity saves: forks " + forks + ", pool tags saved " + pooled + ", learning compared " + learned + " (equal " + learnEqual
                + ", differing " + learnDiff + "), pool failures " + failures + ", level touches " + touches + ", redone " + redone + ", chunks not forked "
                + notForked + ", plan misses " + planMisses + (VERIFY ? ", verified " + verified + " differing " + verifyDiff : "")
                + "; classes trusted " + tr + ", learning " + le + ", serial " + se + serial;
    }
}
