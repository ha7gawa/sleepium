package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.lull.opt.BufferBuilderAccessor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

/**
 * exp/render-cache (client, "render sleep"): an entity's or block entity's renderer output recorded once in the
 * object's own space and replayed with the frame's pose, for any renderer that draws through MultiBufferSource.
 *
 * <p>Record: the renderer runs with an identity pose into a recording source (per render type a BufferBuilder, in the
 * order the types are asked for); the vertices are then in local space. Replay: per recorded type the real source's
 * consumer, the vertices appended with the pose the dispatcher built for this frame (camera-relative translation):
 * one copy plus an affine transform per vertex for a BufferBuilder, the VertexConsumer calls otherwise. The result can
 * differ from vanilla's in float rounding only (vanilla multiplies the translation into the matrix first) — accepted
 * for this engine, unlike every other render change (frame hash). 1.20.1: the level's pose stack carries the camera
 * rotation (1.21's is the identity there), so a replay takes the general transform, normals re-quantized after it
 * (frame: about 2 % of the entities' pixels differ by 1 in a channel); a resident segment's record carries that
 * rotation as its normal matrix (PartBatch mode 2 turns the normals on the GPU the same way).
 *
 * <p>Validity: the object's whole state (StateHash: every field of its class chain, hashed once per tick, tick counters
 * left out), the caller's key (synched data and equipment / block state), packed light, overlay, yaw argument; and a
 * renewal after REFRESH..2*REFRESH ticks spread over objects, the bound for anything no key covers. What a renderer
 * might read between frames is tested by observation whenever the state changed (and every PROBE_EVERY ticks): the
 * object is rendered again with another partial tick, a turned camera and another tickCount; different bytes mark it
 * animated (drawn the vanilla way for a while). A renderer that draws into the real buffer source past its recorder is
 * noticed (vertex counts) and left to vanilla. {@code -Dlab.rcache.verify=true}: every hit is also rendered fresh and
 * compared byte for byte (mismatches by class); {@code -Dlab.rcache.why=true}: which fields change the state.
 */
public final class RenderCache {
    public static final boolean VERIFY = Boolean.getBoolean("lab.rcache.verify");
    /** ticks an animated / unsupported object is drawn the vanilla way before it is probed again */
    private static final int VOLATILE_TICKS = 40;
    public static long translations, residentSegments;
    public static long hits, records, animated, unsupported, verified, mismatches, replayedVertices, vanilla, empty;
    private static final Map<Class<?>, long[]> BY_CLASS = new HashMap<>();   // hits, records, animated, unsupported, mismatches, learned
    /** RenderCacheClockMixin: the thread whose Blaze3D.getTime() is moved by CLOCK_SHIFT seconds (the probe's third look) */
    public static volatile Thread clockShiftThread;
    public static final double CLOCK_SHIFT = 0.35;
    /** a renderer class is drawn the vanilla way for good once this many of its recordings, and 7 in 8, were animated */
    private static final int LEARN_ANIMATED = Integer.getInteger("lab.rcache.learnAnimated", 32);

    private RenderCache() {}

    // ------------------------------------------------------------------ recording

    /** One object's recorded output: segments (render type, byte offset, vertex count) in request order. */
    static final class Rec {
        long tick, key, state, stateNow, hashTick = Long.MIN_VALUE, refreshAt, probedAt = Long.MIN_VALUE / 2;
        boolean recorded;
        long[] fields;
        /** learnFields: the per-field hashes the recording was made with */
        long[] fieldsAt;
        long keyNow, keyTick = Long.MIN_VALUE;
        int light, overlay;
        float yaw;
        long skipUntil = Long.MIN_VALUE;   // animated / unsupported: vanilla until then
        final List<RenderType> types = new ArrayList<>();
        int[] offs = new int[4], counts = new int[4];
        long mem;
        int size, cap;
        /** VertexTags runs: per segment its first run and run count; per run its first vertex (in the segment) and ids */
        int[] runFirst = new int[4], runCount = new int[4];
        int[] runAt = new int[8], runTag = new int[24];
        int runN;
        /** the native block, freed when the record is collected (its object left the world) */
        final long[] box = new long[1];
        /** per segment: first vertex in PartBatch's resident arena (-1: not uploaded); shared with the cleaner */
        final int[][] res = {new int[4], new int[4]};   // starts, counts

        Rec() {
            long[] b = box;
            int[][] r = res;
            java.util.Arrays.fill(r[0], -1);
            CLEANER.register(this, () -> {
                if (b[0] != 0) MemoryUtil.nmemFree(b[0]);
                for (int i = 0; i < r[0].length; i++) if (r[0][i] >= 0) PartBatch.RES_FREE.add(new int[] {r[0][i], r[1][i]});
            });
        }

        /** VRAM copies of the old contents released (render thread) before a new recording replaces them. */
        void releaseResident() {
            for (int i = 0; i < res[0].length; i++) {
                if (res[0][i] >= 0) PartBatch.residentRelease(res[0][i], res[1][i]);
                res[0][i] = -1;
            }
        }

        int resident(int i) {
            if (res[0].length <= i) {
                int c = Math.max(i + 1, res[0].length * 2);
                int[] s = java.util.Arrays.copyOf(res[0], c), n = java.util.Arrays.copyOf(res[1], c);
                java.util.Arrays.fill(s, res[0].length, c, -1);
                res[0] = s;
                res[1] = n;
            }
            if (res[0][i] < 0) {
                res[0][i] = PartBatch.residentUpload(mem + offs[i], counts[i]);
                res[1][i] = counts[i];
            }
            return res[0][i];
        }

        void clear() {
            releaseResident();
            types.clear();
            size = 0;
            runN = 0;
        }

        void add(RenderType t, ByteBuffer data, int vertices) {
            add(t, MemoryUtil.memAddress(data), data.remaining(), vertices);
        }

        void add(RenderType t, long src, int n, int vertices) {
            if (size + n > cap) {
                int c = Math.max(size + n, Math.max(256, cap * 2));
                mem = mem == 0 ? MemoryUtil.nmemAlloc(c) : MemoryUtil.nmemRealloc(mem, c);
                box[0] = mem;
                cap = c;
            }
            MemoryUtil.memCopy(src, mem + size, n);
            int i = types.size();
            if (i == offs.length) {
                offs = java.util.Arrays.copyOf(offs, i * 2);
                counts = java.util.Arrays.copyOf(counts, i * 2);
                runFirst = java.util.Arrays.copyOf(runFirst, i * 2);
                runCount = java.util.Arrays.copyOf(runCount, i * 2);
            }
            types.add(t);
            offs[i] = size;
            counts[i] = vertices;
            runFirst[i] = runN;
            runCount[i] = 0;
            size += n;
        }

        /** the last segment's VertexTags runs, from its recorder */
        void addRuns(RecordingBuilder b) {
            if (b.runs == 0) return;
            int need = runN + b.runs;
            if (need > runAt.length) {
                runAt = java.util.Arrays.copyOf(runAt, Math.max(need, runAt.length * 2));
                runTag = java.util.Arrays.copyOf(runTag, runAt.length * 3);
            }
            System.arraycopy(b.runAt, 0, runAt, runN, b.runs);
            System.arraycopy(b.runTag, 0, runTag, runN * 3, b.runs * 3);
            runCount[types.size() - 1] = b.runs;
            runN = need;
        }

        /** Same types, counts and element bytes (a format's padding bytes are never written: left out). */
        boolean sameAs(Rec o) {
            if (o.size != size || !o.types.equals(types) || o.runN != runN) return false;
            for (int i = 0; i < types.size(); i++) if (o.counts[i] != counts[i] || o.offs[i] != offs[i] || o.runCount[i] != runCount[i]) return false;
            if (!java.util.Arrays.equals(runAt, 0, runN, o.runAt, 0, runN) || !java.util.Arrays.equals(runTag, 0, runN * 3, o.runTag, 0, runN * 3)) return false;
            for (int i = 0; i < types.size(); i++) {
                VertexFormat f = types.get(i).format();
                boolean[] used = usedBytes(f);
                int vs = f.getVertexSize();
                long a = mem + offs[i], b = o.mem + offs[i];
                for (int v = 0; v < counts[i]; v++)
                    for (int k = 0; k < vs; k++)
                        if (used[k] && MemoryUtil.memGetByte(a + (long) v * vs + k) != MemoryUtil.memGetByte(b + (long) v * vs + k)) return false;
            }
            return true;
        }
    }

    /** A MultiBufferSource that keeps what a renderer draws, per render type, in local space. */
    /**
     * A MultiBufferSource that keeps what a renderer draws, per render type, in local space: one RecordingBuilder per
     * type kept across recordings (same bytes as a BufferBuilder, no objects per recording).
     */
    static final class Recorder implements MultiBufferSource {
        final Map<RenderType, RecordingBuilder> builders = new IdentityHashMap<>();
        final List<RenderType> order = new ArrayList<>();   // the types asked for in this recording, in order
        final java.util.Set<RenderType> used = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        boolean unsupported;

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            RecordingBuilder b = builders.get(type);
            if (b == null) {
                builders.put(type, b = new RecordingBuilder(type.format(), type.mode()));
                b.type = type;
            }
            if (used.add(type)) {
                b.reset();
                order.add(type);
                if ((type.mode() != VertexFormat.Mode.QUADS && type.mode() != VertexFormat.Mode.TRIANGLES) || !formatSupported(type.format())) unsupported = true;
            }
            return b;
        }

        /** Moves everything recorded into rec (cleared first). */
        void finish(Rec rec) {
            rec.clear();
            for (int i = 0; i < order.size(); i++) {   // (indexed: no iterator per recorded object)
                RenderType t = order.get(i);
                RecordingBuilder b = builders.get(t);
                if (b.n > 0) {
                    rec.add(t, b.mem, b.n * b.vertexSize, b.n);
                    rec.addRuns(b);
                }
            }
            order.clear();
            used.clear();
        }

        void reset() {
            order.clear();
            used.clear();
            unsupported = false;
        }
    }

    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();
    private static final Recorder RECORDER = new Recorder();
    private static final PoseStack LOCAL = new PoseStack();
    private static int recordDepth;
    private static final Rec PROBE = new Rec();
    private static final Map<Object, Rec> RECS = new WeakHashMap<>();

    /**
     * Renderers of animation libraries animate by time by design (their output changes every frame): marked learned
     * from the start, so the cache neither records nor probes them. The probe renders an object at a shifted time,
     * which for such a library moves its animation state (controllers, keyframe events) as if that time had come.
     */
    private static final String[] TIME_ANIMATED_LIBS = {"software.bernie.geckolib.renderer.GeoRenderer"};

    private static long[] classCounters(Class<?> rendererClass) {
        long[] cs = new long[7];
        for (String lib : TIME_ANIMATED_LIBS) if (ParallelRender.implementsNamed(rendererClass, lib)) cs[5] = 1;
        // players: few, always moving, and mods replace their rendering inside PlayerRenderer (RenderPlayerEvent, e.g. a
        // GeckoLib model driven by a controller) where the probe would move that animation's state too
        if (net.minecraft.client.renderer.entity.player.PlayerRenderer.class.isAssignableFrom(rendererClass)) cs[5] = 1;
        return cs;
    }

    private static final Map<VertexFormat, boolean[]> USED = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean[] usedBytes(VertexFormat f) {
        return USED.computeIfAbsent(f, x -> {
            boolean[] u = new boolean[x.getVertexSize()];
            var es = x.getElements();
            for (int i = 0; i < es.size(); i++) {
                VertexFormatElement e = es.get(i);
                if (e.getUsage() == VertexFormatElement.Usage.PADDING) continue;   // never written (1.21's formats have no padding element)
                for (int k = 0; k < e.getByteSize(); k++) u[x.getOffset(i) + k] = true;
            }
            return u;
        });
    }

    private static boolean formatSupported(VertexFormat f) {
        for (VertexFormatElement e : f.getElements())
            if (!e.equals(DefaultVertexFormat.ELEMENT_POSITION) && !e.equals(DefaultVertexFormat.ELEMENT_COLOR) && !e.equals(DefaultVertexFormat.ELEMENT_UV0)
                    && !e.equals(DefaultVertexFormat.ELEMENT_UV1) && !e.equals(DefaultVertexFormat.ELEMENT_UV2) && !e.equals(DefaultVertexFormat.ELEMENT_NORMAL)
                    && e.getUsage() != VertexFormatElement.Usage.PADDING) return false;
        return true;
    }

    /** The renderer call, with the object-local pose and a buffer source to draw into. */
    public interface Draw {
        void draw(PoseStack pose, MultiBufferSource buffers, float partialTick);
    }

    private static final int OK = 0, UNSUPPORTED = 1, BYPASS = 2;
    private static MultiBufferSource.BufferSource real;

    /** Vertices started in the real source: a renderer that draws there past its recorder shows up as a change. */
    private static long realVertices() {
        long n = 0;
        for (BufferBuilder b : ((dev.lull.opt.BufferSourceAccessor) real).sleep$started()) n += ((BufferBuilderAccessor) b).sleep$vertices();
        return n + ownBuildersUsed(real);
    }

    /**
     * A BufferSource subclass that keeps its own builders (Iris's FullyBufferedMultiBufferSource: vanilla's started map
     * stays empty) and reports the bytes in them with a {@code long getUsedSize()}: that number, else 0.
     */
    private static final ClassValue<java.lang.invoke.MethodHandle> USED_SIZE = new ClassValue<>() {
        @Override
        protected java.lang.invoke.MethodHandle computeValue(Class<?> c) {
            if (c == MultiBufferSource.BufferSource.class) return null;
            try {
                return java.lang.invoke.MethodHandles.publicLookup().findVirtual(c, "getUsedSize", java.lang.invoke.MethodType.methodType(long.class))
                        .asType(java.lang.invoke.MethodType.methodType(long.class, Object.class));
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    };

    private static long ownBuildersUsed(MultiBufferSource.BufferSource s) {
        java.lang.invoke.MethodHandle h = USED_SIZE.get(s.getClass());
        if (h == null) return 0;
        try {
            return (long) h.invokeExact((Object) s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Records one render into rec (only if OK; on BYPASS the recorded part is kept too). */
    /** -Dlab.rcache.spikes=true: time in recordings / their real-source checks / state hashes (ClientMeter prints them per slow frame) */
    public static final boolean SPIKES = Boolean.getBoolean("lab.rcache.spikes");
    public static long recordNanos, realNanos, hashNanos, hashes;
    /** SPIKES: recordings by reason - new, state, key, light / overlay / yaw, refresh */
    public static final long[] WHY_RECORD = new long[5];

    private static int record(Draw d, float pt, Rec rec) {
        long t0 = System.nanoTime();
        try {
            return record0(d, pt, rec);
        } finally {
            long dt = System.nanoTime() - t0;
            frameRecordNanos += dt;
            if (SPIKES) recordNanos += dt;
        }
    }

    /**
     * Recording time per frame is bounded: when many objects change at once (the server's periodic position resync of
     * every armor stand: 119 recordings with their probes, 15 ms in one frame under a shaderpack), the ones past the
     * budget are drawn the vanilla way in this frame and recorded in the next ones. Such a frame costs about a vanilla
     * one. -Dlab.rcache.recordBudgetMs (default 4).
     */
    private static final long RECORD_BUDGET = (long) (Double.parseDouble(System.getProperty("lab.rcache.recordBudgetMs", "4")) * 1e6);
    private static long frameRecordNanos;
    public static long deferred;

    /** RenderFrameEvent.Pre */
    public static void frame() {
        frameRecordNanos = 0;
        if (TIME) tFrames++;
    }

    /** ClientAuto: the measured window starts (the times are per frame of it) */
    public static void timeReset() {
        tRender = tReplay = tResidentOk = tGetBuffer = tQueue = tCopy = tFrames = 0;
        CPU_TYPES.clear();
    }

    private static long realVerticesTimed() {
        if (!SPIKES) return realVertices();
        long t0 = System.nanoTime();
        long n = realVertices();
        realNanos += System.nanoTime() - t0;
        return n;
    }

    private static int record0(Draw d, float pt, Rec rec) {
        RECORDER.reset();
        long before = realVerticesTimed();
        // one stack for the recordings (render thread) so its pose pool persists; a recording inside a recording (a
        // renderer rendering another object) gets its own
        PoseStack ps = recordDepth == 0 ? LOCAL : new PoseStack();
        ps.setIdentity();
        recordDepth++;
        try {
            d.draw(ps, RECORDER, pt);
        } finally {
            recordDepth--;
        }
        if (realVerticesTimed() != before) {
            RECORDER.finish(rec);
            return BYPASS;
        }
        if (RECORDER.unsupported) {
            RECORDER.reset();
            return UNSUPPORTED;
        }
        RECORDER.finish(rec);
        return OK;
    }

    /** An adapter's parametric memo of renderer output (KineticMemo), asked before the cache. */
    public static volatile BeMemo BE_MEMO;

    /** Vertices started in a buffer source (a memo checks that a recording drew nothing but its parts). */
    public static long vertices(MultiBufferSource.BufferSource source) {
        long n = 0;
        for (BufferBuilder b : ((dev.lull.opt.BufferSourceAccessor) source).sleep$started()) n += ((BufferBuilderAccessor) b).sleep$vertices();
        return n;
    }

    /** A part path (SuperByteBuffer from VRAM) would draw this: the recording is not the way for it - drawn directly instead. */
    public static void partsWanted() {
        if (recordDepth > 0) RECORDER.unsupported = true;
    }

    /** a recording or a probe of the cache is drawing now (its output not the frame's: a debug reading the bones skips it -
     *  10-20, the bone recorder: a probe's draw of the player, the shared bones still a foe's, had read as 143-degree snaps) */
    public static boolean recording() {
        return recordDepth > 0;
    }

    // ------------------------------------------------------------------ entry point

    /**
     * From the dispatchers, in place of renderer.render(...). key: whatever else the output depends on that the caller
     * knows (block state, synched data, yaw). camera: the entity dispatcher when its orientation can be probed.
     */
    /**
     * -Dlab.rcache.time=true: render thread nanos in render (all of it), replay, and inside replay the resident check,
     * getBuffer, the resident queue, the copy + transform; CPU-path segments and vertices by render type (report)
     */
    static final boolean TIME = Boolean.getBoolean("lab.rcache.time");
    static long tRender, tReplay, tResidentOk, tGetBuffer, tQueue, tCopy, tFrames;
    private static final java.util.Map<RenderType, long[]> CPU_TYPES = new java.util.IdentityHashMap<>();

    public static void render(Object obj, Class<?> rendererClass, Draw d, float pt, PoseStack pose, MultiBufferSource buffers, int light, int overlay,
            float yaw, long key, net.minecraft.client.renderer.entity.EntityRenderDispatcher camera) {
        if (!TIME) {
            render0(obj, rendererClass, d, pt, pose, buffers, light, overlay, yaw, key, camera);
            return;
        }
        long t0 = System.nanoTime();
        try {
            render0(obj, rendererClass, d, pt, pose, buffers, light, overlay, yaw, key, camera);
        } finally {
            tRender += System.nanoTime() - t0;
        }
    }

    private static void render0(Object obj, Class<?> rendererClass, Draw d, float pt, PoseStack pose, MultiBufferSource buffers, int light, int overlay,
            float yaw, long key, net.minecraft.client.renderer.entity.EntityRenderDispatcher camera) {
        var level = Minecraft.getInstance().level;
        BeMemo memo = BE_MEMO;
        if (memo != null && level != null && buffers instanceof MultiBufferSource.BufferSource && recordDepth == 0
                && memo.render(obj, rendererClass, d, pt, pose, buffers, light, overlay)) return;
        // any source but the outline one (a wrapper such as Iris's per-entity BufferSourceWrapper replays the same way:
        // the recorded getBuffer calls, through it)
        if (level == null || buffers instanceof net.minecraft.client.renderer.OutlineBufferSource || dev.lull.api.Compat.renderCacheExcluded(obj)) {
            vanilla++;
            d.draw(pose, buffers, pt);
            return;
        }
        // where a renderer drawing past its recorder ends up: the source itself, or the frame's one behind a wrapper
        real = buffers instanceof MultiBufferSource.BufferSource bs ? bs : Minecraft.getInstance().renderBuffers().bufferSource();
        long tick = level.getGameTime();
        long[] cs = BY_CLASS.computeIfAbsent(rendererClass, RenderCache::classCounters);
        Rec rec = RECS.get(obj);
        if (rec == null) RECS.put(obj, rec = new Rec());
        if (cs[5] != 0) rec.skipUntil = Long.MAX_VALUE;   // learned: animates by time (ParallelRender takes it too)
        if (rec.skipUntil > tick) {
            vanilla++;
            d.draw(pose, buffers, pt);
            return;
        }
        if (rec.hashTick != tick) {   // the whole state, once per tick (packets and ticks change it)
            long h0 = SPIKES ? System.nanoTime() : 0;
            rec.stateNow = StateHash.of(obj);
            if (SPIKES) {
                hashNanos += System.nanoTime() - h0;
                hashes++;
            }
            rec.hashTick = tick;
            if (WHY) why(obj, rec);
        }
        if (rec.recorded && rec.state == rec.stateNow && rec.key == key && rec.light == light && rec.overlay == overlay && rec.yaw == yaw && tick < rec.refreshAt) {
            if (VERIFY && record(d, pt, PROBE) == OK) {
                verified++;
                if (!PROBE.sameAs(rec)) {
                    mismatches++;
                    cs[4]++;
                }
            }
            replay(rec, buffers, pose);
            hits++;
            cs[0]++;
            return;
        }
        if (frameRecordNanos >= RECORD_BUDGET && recordDepth == 0) {   // the frame's recording time is spent: next frame
            deferred++;
            d.draw(pose, buffers, pt);
            return;
        }
        boolean changed = !rec.recorded || rec.state != rec.stateNow || rec.key != key;
        if (StateHash.LEARN && rec.recorded && rec.fieldsAt != null && rec.state != rec.stateNow && rec.key == key && rec.light == light
                && rec.overlay == overlay && rec.yaw == yaw && tick < rec.refreshAt) {
            // only the state changed: is the output another one? (learning which fields do not matter)
            long[] now = StateHash.fields(obj);
            if (record(d, pt, CHECK) == OK && CHECK.sameAs(rec)) {
                StateHash.unchanged(obj, rec.fieldsAt, now);
                rec.fieldsAt = now;
                rec.state = rec.stateNow;
                sameAfterChange++;
                replay(rec, buffers, pose);
                return;
            }
            StateHash.changedOutput(obj, rec.fieldsAt, now);
        }
        if (SPIKES) WHY_RECORD[!rec.recorded ? 0 : rec.state != rec.stateNow ? 1 : rec.key != key ? 2 : rec.light != light || rec.overlay != overlay || rec.yaw != yaw ? 3 : 4]++;
        rec.recorded = false;
        int r = record(d, pt, rec);
        if (r != OK) {
            rec.skipUntil = tick + VOLATILE_TICKS;
            unsupported++;
            cs[3]++;
            if (r == BYPASS) replay(rec, buffers, pose);   // its other part is already in the real source
            else d.draw(pose, buffers, pt);
            return;
        }
        records++;
        cs[1]++;
        if (rec.size == 0) {
            // nothing written into the buffers: the renderer draws nothing (cheap to call) or draws elsewhere - its own
            // vertex buffers drawn at a later render stage (Immersive Engineering's water wheels, windmills, bucket
            // wheels queue a job there), GL calls of its own - which only calling it every frame keeps. Its class is
            // drawn the vanilla way from now on (no more recordings: each one would queue such work once more)
            rec.recorded = false;
            empty++;
            if (cs[6]++ == 0 && cs[5] == 0) {
                cs[5] = 1;
                System.out.println("[sleep/opt] render cache: " + rendererClass.getSimpleName()
                        + " wrote nothing into the buffers: drawn the vanilla way from now on (it may draw elsewhere)");
            }
            d.draw(pose, buffers, pt);
            return;
        }
        // observation, when the state changed (and now and then): another partial tick, a turned camera, another tick
        // count; any difference = drawn per frame (vanilla) for a while
        if (changed || tick - rec.probedAt >= PROBE_EVERY) {
            rec.probedAt = tick;
            boolean animated = record(d, pt < 0.5f ? pt + 0.5f : pt - 0.5f, PROBE) != OK || !PROBE.sameAs(rec);
            if (!animated && camera != null) {
                Quaternionf q = camera.cameraOrientation();
                camera.overrideCameraOrientation(new Quaternionf(q).rotateY(1.3f).rotateX(0.4f));
                try {
                    animated = record(d, pt, PROBE) != OK || !PROBE.sameAs(rec);
                } finally {
                    camera.overrideCameraOrientation(q);
                }
            }
            if (!animated && obj instanceof net.minecraft.world.entity.Entity e) {
                e.tickCount += 7;
                try {
                    animated = record(d, pt, PROBE) != OK || !PROBE.sameAs(rec);
                } finally {
                    e.tickCount -= 7;
                }
            }
            if (!animated) {   // the wall clock (GLFW time), for renderers that animate by it
                clockShiftThread = Thread.currentThread();
                try {
                    animated = record(d, pt, PROBE) != OK || !PROBE.sameAs(rec);
                } finally {
                    clockShiftThread = null;
                }
            }
            if (animated) {
                rec.skipUntil = tick + VOLATILE_TICKS;
                RenderCache.animated++;
                cs[2]++;
                if (cs[5] == 0 && cs[2] >= LEARN_ANIMATED && cs[2] * 8 >= cs[1] * 7) {
                    cs[5] = 1;
                    System.out.println("[sleep/opt] render cache: " + rendererClass.getSimpleName() + " animated in " + cs[2] + " of "
                            + cs[1] + " recordings: drawn the vanilla way from now on, no more probes");
                }
                d.draw(pose, buffers, pt);
                return;
            }
        }
        rec.recorded = true;
        rec.state = rec.stateNow;
        if (StateHash.LEARN) rec.fieldsAt = StateHash.fields(obj);
        rec.light = light;
        rec.overlay = overlay;
        rec.yaw = yaw;
        rec.key = key;
        // safety net for what no key covers: recorded again after REFRESH ticks, spread over objects
        rec.refreshAt = tick + REFRESH + Math.floorMod(System.identityHashCode(obj), REFRESH);
        replay(rec, buffers, pose);
    }

    private static final boolean WHY = Boolean.getBoolean("lab.rcache.why");
    /** learnFields: a re-recording after a state change, compared with the recording it would replace */
    private static final Rec CHECK = new Rec();
    public static long sameAfterChange;
    private static final Map<String, long[]> WHY_COUNTS = new HashMap<>();

    /** Which fields changed since the last tick's hash, counted by class and field name. */
    private static void why(Object obj, Rec rec) {
        long[] now = StateHash.fields(obj);
        if (rec.fields != null && rec.fields.length == now.length)
            for (int i = 0; i < now.length; i++)
                if (now[i] != rec.fields[i]) WHY_COUNTS.computeIfAbsent(obj.getClass().getSimpleName() + " " + StateHash.name(obj, i), k -> new long[1])[0]++;
        rec.fields = now;
    }

    /** -Dlab.rcache.refresh=N: every recording is renewed after N..2N ticks at most (default 20) */
    private static final int REFRESH = Math.max(1, Integer.getInteger("lab.rcache.refresh", 20));
    /** -Dlab.rcache.probeEvery=N: an unchanged object is probed again after N ticks (default 200) */
    private static final int PROBE_EVERY = Integer.getInteger("lab.rcache.probeEvery", 200);

    /**
     * The caller's key as computed in this tick, or NO_KEY: the key (synched data, equipment) changes by ticks and
     * packets, so like the state hash it is taken once per tick.
     */
    public static final long NO_KEY = Long.MIN_VALUE + 17;

    public static long keyThisTick(Object obj) {
        Rec rec = RECS.get(obj);
        var level = Minecraft.getInstance().level;
        return rec != null && level != null && rec.keyTick == level.getGameTime() ? rec.keyNow : NO_KEY;
    }

    public static void keyComputed(Object obj, long key) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        Rec rec = RECS.get(obj);
        if (rec == null) RECS.put(obj, rec = new Rec());
        rec.keyNow = key;
        rec.keyTick = level.getGameTime();
    }

    /** ParallelRender: an object the cache currently draws the vanilla way (animated / unsupported). */
    static boolean animated(Object obj) {
        Rec rec = RECS.get(obj);
        var level = Minecraft.getInstance().level;
        return rec != null && level != null && rec.skipUntil > level.getGameTime();
    }

    /** ParallelRender: recorded camera-relative output appended as it is (no transform). */
    static void append(Rec rec, MultiBufferSource buffers) {
        M.identity();
        N.identity();
        replay(rec, buffers, null);
    }

    // ------------------------------------------------------------------ replay

    private static final Matrix4f M = new Matrix4f();
    private static final Matrix3f N = new Matrix3f(), IDENTITY3 = new Matrix3f();

    private static void replay(Rec rec, MultiBufferSource buffers, PoseStack pose) {
        if (!TIME) {
            replay0(rec, buffers, pose);
            return;
        }
        long t0 = System.nanoTime();
        replay0(rec, buffers, pose);
        tReplay += System.nanoTime() - t0;
    }

    private static void replay0(Rec rec, MultiBufferSource buffers, PoseStack pose) {
        boolean asIs = pose == null;
        if (!asIs) {
            M.set(pose.last().pose());
            N.set(pose.last().normal());
        }
        boolean normalIdentity = asIs || N.equals(IDENTITY3);
        boolean tags = rec.runN > 0;   // VertexTags: the state as it was put back after the replay
        if (tags) VertexTags.read(TAGS_SAVED, 0);
        for (int i = 0; i < rec.types.size(); i++) {
            RenderType t = rec.types.get(i);
            long ta = TIME ? System.nanoTime() : 0;
            VertexConsumer vc = builderOf(buffers.getBuffer(t));
            long tb = TIME ? System.nanoTime() : 0;
            if (TIME) tGetBuffer += tb - ta;
            long src = rec.mem + rec.offs[i];
            int n = rec.counts[i];
            // (1.20.1: the pose has the camera rotation in it - no identity normal matrix: the record turns the normals)
            boolean resident = !asIs && rec != PROBE && n % 4 == 0 && PartBatch.residentOk(t, vc, buffers);
            long tc = TIME ? System.nanoTime() : 0;
            if (TIME) tResidentOk += tc - tb;
            if (resident) {
                PartBatch.queueResident(t, (BufferBuilder) vc, rec.resident(i), n, M, N);
                if (TIME) {
                    tQueue += System.nanoTime() - tc;
                    long[] c = CPU_TYPES.computeIfAbsent(t, k -> new long[4]);
                    c[2]++;
                    c[3] += n;
                }
                replayedVertices += n;
                residentSegments++;
                continue;
            }
            VertexFormat f = t.format();
            // a BufferBuilder building this format and mode, between vertices (1.20.1: its element cursor at the first
            // element): one copy, as its own putBulkData(ByteBuffer) appends whole vertices
            if (vc instanceof BufferBuilder bb && ((BufferBuilderAccessor) bb).sleep$building() && ((BufferBuilderAccessor) bb).sleep$format() == f
                    && ((BufferBuilderAccessor) bb).sleep$mode() == t.mode() && ((BufferBuilderAccessor) bb).sleep$elementIndex() == 0) {
                BufferBuilderAccessor a = (BufferBuilderAccessor) bb;
                int vs = f.getVertexSize();
                a.sleep$ensureCapacity((n + 1) * vs);   // room for one more vertex after them, as endVertex keeps it
                int at = a.sleep$nextElementByte();
                long dst = MemoryUtil.memAddress0(a.sleep$buffer()) + at;
                MemoryUtil.memCopy(src, dst, (long) n * vs);
                if (!asIs) transform(dst, n, f, normalIdentity);
                a.sleep$setVertices(a.sleep$vertices() + n);
                a.sleep$setNextElementByte(at + n * vs);
                if (TIME) {
                    tCopy += System.nanoTime() - tc;
                    long[] c = CPU_TYPES.computeIfAbsent(t, k -> new long[4]);
                    c[0]++;
                    c[1] += n;
                }
            } else {
                if (WHY_GENERIC) whyGeneric(vc, f, t);
                generic(vc, src, n, f, rec, i);
            }
            replayedVertices += n;
        }
        if (tags) VertexTags.write(TAGS_SAVED, 0);
    }

    private static final int[] TAGS_SAVED = new int[3];

    private static void transform(long base, int n, VertexFormat f, boolean normalIdentity) {
        int vs = f.getVertexSize(), po = RecordingBuilder.offset(f, DefaultVertexFormat.ELEMENT_POSITION), no = RecordingBuilder.offset(f, DefaultVertexFormat.ELEMENT_NORMAL);
        if (normalIdentity && M.m00() == 1 && M.m11() == 1 && M.m22() == 1 && M.m01() == 0 && M.m02() == 0 && M.m10() == 0 && M.m12() == 0 && M.m20() == 0
                && M.m21() == 0 && M.m03() == 0 && M.m13() == 0 && M.m23() == 0 && M.m33() == 1) {
            // the usual pose here: the camera-relative translation only (the same sums the general form gives)
            float tx = M.m30(), ty = M.m31(), tz = M.m32();
            for (int i = 0; i < n; i++) {
                long v = base + (long) i * vs + po;
                MemoryUtil.memPutFloat(v, MemoryUtil.memGetFloat(v) + tx);
                MemoryUtil.memPutFloat(v + 4, MemoryUtil.memGetFloat(v + 4) + ty);
                MemoryUtil.memPutFloat(v + 8, MemoryUtil.memGetFloat(v + 8) + tz);
            }
            translations++;
            return;
        }
        for (int i = 0; i < n; i++) {
            long v = base + (long) i * vs;
            float x = MemoryUtil.memGetFloat(v + po), y = MemoryUtil.memGetFloat(v + po + 4), z = MemoryUtil.memGetFloat(v + po + 8);
            MemoryUtil.memPutFloat(v + po, M.m00() * x + M.m10() * y + M.m20() * z + M.m30());
            MemoryUtil.memPutFloat(v + po + 4, M.m01() * x + M.m11() * y + M.m21() * z + M.m31());
            MemoryUtil.memPutFloat(v + po + 8, M.m02() * x + M.m12() * y + M.m22() * z + M.m32());
            if (no >= 0 && !normalIdentity) {
                float nx = MemoryUtil.memGetByte(v + no) / 127f, ny = MemoryUtil.memGetByte(v + no + 1) / 127f, nz = MemoryUtil.memGetByte(v + no + 2) / 127f;
                float tx = N.m00() * nx + N.m10() * ny + N.m20() * nz, ty = N.m01() * nx + N.m11() * ny + N.m21() * nz, tz = N.m02() * nx + N.m12() * ny + N.m22() * nz;
                float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (len > 0) {
                    tx /= len;
                    ty /= len;
                    tz /= len;
                }
                MemoryUtil.memPutByte(v + no, (byte) ((int) (net.minecraft.util.Mth.clamp(tx, -1f, 1f) * 127f) & 0xFF));
                MemoryUtil.memPutByte(v + no + 1, (byte) ((int) (net.minecraft.util.Mth.clamp(ty, -1f, 1f) * 127f) & 0xFF));
                MemoryUtil.memPutByte(v + no + 2, (byte) ((int) (net.minecraft.util.Mth.clamp(tz, -1f, 1f) * 127f) & 0xFF));
            }
        }
    }

    /**
     * Any other consumer (outline, glint wrappers of a mod): the vertices through the VertexConsumer calls, in the
     * format's element order (1.20.1's consumers fill a vertex element by element: a call out of order is lost).
     */
    /**
     * The BufferBuilder a consumer writes into: the consumer itself, or a view of one that keeps its vertex state in the
     * builder - Embeddium's SodiumBufferBuilder, which its mixin returns from BufferSource.getBuffer in place of the
     * builder: a VertexBufferWriter (Sodium's API for bulk vertex writes) with a public no-argument method returning
     * the BufferBuilder (getOriginalBufferBuilder, as Embeddium's own endBatch unwraps it). Between vertices the view
     * holds nothing the builder does not, so whole vertices go into the builder as without it (copy and VRAM residents);
     * otherwise every record went the per-vertex way (client_entities with Embeddium: 137 fps, 75 % of the render
     * thread in it).
     */
    static VertexConsumer builderOf(VertexConsumer vc) {
        if (vc instanceof BufferBuilder) return vc;
        java.lang.invoke.MethodHandle h = VIEW_OF.get(vc.getClass());
        if (h == NO_VIEW) return vc;
        try {
            Object bb = h.invoke(vc);
            return bb instanceof BufferBuilder b ? b : vc;
        } catch (Throwable e) {
            return vc;
        }
    }

    private static final java.lang.invoke.MethodHandle NO_VIEW = java.lang.invoke.MethodHandles.constant(Object.class, null);
    private static final ClassValue<java.lang.invoke.MethodHandle> VIEW_OF = new ClassValue<>() {
        @Override
        protected java.lang.invoke.MethodHandle computeValue(Class<?> c) {
            boolean writer = false;
            for (Class<?> k = c; k != null && !writer; k = k.getSuperclass())
                for (Class<?> i : k.getInterfaces()) writer |= i.getName().equals("net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter");
            if (!writer) return NO_VIEW;
            for (java.lang.reflect.Method m : c.getMethods())
                if (m.getParameterCount() == 0 && m.getReturnType() == BufferBuilder.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                    try {
                        return java.lang.invoke.MethodHandles.publicLookup().unreflect(m).asType(java.lang.invoke.MethodType.methodType(Object.class, VertexConsumer.class));
                    } catch (Throwable e) {
                        return NO_VIEW;
                    }
            return NO_VIEW;
        }
    };

    private static final Map<VertexFormat, int[]> KINDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** per element: its kind (0 position, 1 color, 2 uv0, 3 uv1, 4 uv2, 5 normal, -1 other) and its offset */
    private static int[] kinds(VertexFormat f) {
        var es = f.getElements();
        int[] out = new int[2 * es.size()];
        for (int e = 0; e < es.size(); e++) {
            VertexFormatElement el = es.get(e);
            out[2 * e] = el.equals(DefaultVertexFormat.ELEMENT_POSITION) ? 0 : el.equals(DefaultVertexFormat.ELEMENT_COLOR) ? 1
                    : el.equals(DefaultVertexFormat.ELEMENT_UV0) ? 2 : el.equals(DefaultVertexFormat.ELEMENT_UV1) ? 3
                    : el.equals(DefaultVertexFormat.ELEMENT_UV2) ? 4 : el.equals(DefaultVertexFormat.ELEMENT_NORMAL) ? 5 : -1;
            out[2 * e + 1] = f.getOffset(e);
        }
        return out;
    }

    /** -Dlab.rcache.whyGeneric=true: why records went the per-vertex way (counts in the report) */
    private static final boolean WHY_GENERIC = Boolean.getBoolean("lab.rcache.whyGeneric");
    private static final Map<String, long[]> GENERIC_WHY = new java.util.concurrent.ConcurrentHashMap<>();

    private static void whyGeneric(VertexConsumer vc, VertexFormat f, RenderType t) {
        String why;
        if (!(vc instanceof BufferBuilder bb)) why = "consumer " + vc.getClass().getName();
        else {
            BufferBuilderAccessor a = (BufferBuilderAccessor) bb;
            why = !a.sleep$building() ? "not building" : a.sleep$format() != f ? "format " + a.sleep$format() + " vs " + f
                    : a.sleep$mode() != t.mode() ? "mode" : a.sleep$elementIndex() != 0 ? "element index " + a.sleep$elementIndex() : "?";
        }
        GENERIC_WHY.computeIfAbsent(why, k -> new long[1])[0]++;
    }

    private static void generic(VertexConsumer vc, long src, int n, VertexFormat f, Rec rec, int seg) {
        int vs = f.getVertexSize();
        int[] kinds = KINDS.computeIfAbsent(f, RenderCache::kinds);   // the elements' kinds, worked out once per format
        int ne = kinds.length / 2;
        int k = rec.runFirst[seg], kEnd = k + rec.runCount[seg];   // VertexTags runs of this segment
        for (int i = 0; i < n; i++) {
            if (k < kEnd && rec.runAt[k] == i) VertexTags.write(rec.runTag, 3 * k++);
            long v = src + (long) i * vs;
            for (int e = 0; e < ne; e++) {
                int kind = kinds[2 * e];
                long u = v + kinds[2 * e + 1];
                if (kind == 0) {
                    float x = MemoryUtil.memGetFloat(u), y = MemoryUtil.memGetFloat(u + 4), z = MemoryUtil.memGetFloat(u + 8);
                    vc.vertex(M.m00() * x + M.m10() * y + M.m20() * z + M.m30(), M.m01() * x + M.m11() * y + M.m21() * z + M.m31(),
                            M.m02() * x + M.m12() * y + M.m22() * z + M.m32());
                } else if (kind == 1) {
                    vc.color(MemoryUtil.memGetByte(u) & 0xFF, MemoryUtil.memGetByte(u + 1) & 0xFF, MemoryUtil.memGetByte(u + 2) & 0xFF, MemoryUtil.memGetByte(u + 3) & 0xFF);
                } else if (kind == 2) {
                    vc.uv(MemoryUtil.memGetFloat(u), MemoryUtil.memGetFloat(u + 4));
                } else if (kind == 3) {
                    vc.overlayCoords(MemoryUtil.memGetShort(u), MemoryUtil.memGetShort(u + 2));
                } else if (kind == 4) {
                    vc.uv2(MemoryUtil.memGetShort(u), MemoryUtil.memGetShort(u + 2));
                } else if (kind == 5) {
                    float nx = MemoryUtil.memGetByte(u) / 127f, ny = MemoryUtil.memGetByte(u + 1) / 127f, nz = MemoryUtil.memGetByte(u + 2) / 127f;
                    vc.normal(N.m00() * nx + N.m10() * ny + N.m20() * nz, N.m01() * nx + N.m11() * ny + N.m21() * nz, N.m02() * nx + N.m12() * ny + N.m22() * nz);
                }
            }
            vc.endVertex();
        }
    }

    public static String report() {
        StringBuilder w = new StringBuilder();
        if (WHY) WHY_COUNTS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(25)
                .forEach(e -> w.append("\n    changed ").append(e.getValue()[0]).append("x ").append(e.getKey()));
        StringBuilder sb = new StringBuilder(w.length() > 0 ? "render cache field changes:" + w + "\n" : "");
        if (PartBatch.WHY_RESIDENT) PartBatch.RESIDENT_WHY.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(12)
                .forEach(e -> sb.append("render cache not resident: ").append(e.getValue()[0]).append("x ").append(e.getKey()).append('\n'));
        if (WHY_GENERIC) GENERIC_WHY.forEach((k, v) -> sb.append("render cache per-vertex way: ").append(v[0]).append("x ").append(k.length() > 200 ? k.substring(0, 200) : k).append('\n'));
        sb.append(String.format(java.util.Locale.ROOT,
                "render cache: hits %d, records %d, animated %d, empty %d, unsupported %d, vanilla %d, deferred to next frames %d, replayed vertices %d%s%s", hits, records,
                animated, empty, unsupported, vanilla, deferred, replayedVertices, VERIFY ? ", verified " + verified + ", mismatches " + mismatches : "",
                StateHash.LEARN ? ", same after a state change " + sameAfterChange + ", fields left out " + StateHash.learned : ""));
        if (TIME && tFrames > 0) {
            double f = tFrames;
            sb.append(String.format(java.util.Locale.ROOT, "%n  render cache time per frame: render %.3f ms, replay %.3f (getBuffer %.3f, resident check %.3f, resident queue %.3f, copy + transform %.3f)",
                    tRender / f / 1e6, tReplay / f / 1e6, tGetBuffer / f / 1e6, tResidentOk / f / 1e6, tQueue / f / 1e6, tCopy / f / 1e6));
            CPU_TYPES.forEach((k, v) -> sb.append(String.format(java.util.Locale.ROOT, "%n    %s: cpu path %.1f segments, %.0f vertices; resident %.1f segments, %.0f vertices per frame", k.toString().substring(0, Math.min(60, k.toString().length())), v[0] / f, v[1] / f, v[2] / f, v[3] / f)));
        }
        BY_CLASS.forEach((c, v) -> sb.append(String.format(java.util.Locale.ROOT, "\n    %s: hits %d, records %d, animated %d, unsupported %d%s", c.getSimpleName(),
                v[0], v[1], v[2], v[3], (v[5] != 0 ? ", learned: animated" : "") + (VERIFY ? ", mismatches " + v[4] : ""))));
        return sb.toString();
    }
}
