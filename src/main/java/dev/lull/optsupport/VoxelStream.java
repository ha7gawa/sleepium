package dev.lull.optsupport;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dlab.voxel.stream=true (client, prototype, notes/61): the GPU mesher (VoxelCompute's shader, its exactness checked
 * there) kept current while playing - only the sections vanilla marks dirty (LevelRenderer.setSectionDirty: block
 * changes, light, chunk loads) are meshed again, a budget per frame, without stalling the CPU on the GPU.
 *
 * <p>The grid is toroidal (world coordinates modulo its size, one ring of chunks beyond the render distance): moving
 * copies nothing; chunks entering the ring are uploaded, leaving ones cleared. A section's blocks and light are
 * uploaded before a batch meshes it or a neighbour of it. The block-state library grows on first sight (the face pair
 * table gets the new state's row and column). A batch: count per section and bucket -> fence -> next frame the sizes
 * are read and blocks allocated in the face buffer -> write -> fence -> publish (until then the section's previous
 * faces are drawn). A face record names its section's slot; the slot table holds the section's world origin, and the
 * draw subtracts the camera in integers first (no precision loss far from the origin).
 */
public final class VoxelStream {
    public static final boolean ON = Boolean.getBoolean("lab.voxel.stream");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    private static final int BUDGET = Integer.getInteger("lab.voxel.stream.budget", 32);   // sections per batch
    private static final int FLIGHT = Integer.getInteger("lab.voxel.stream.flight", 3);    // batches in flight at once
    /** -Dlab.voxel.stream.vertices: the opaque layers meshed into ready 16-byte vertices (fixed-function fetch), not face records */
    private static final boolean VERTS = !"false".equals(System.getProperty("lab.voxel.stream.vertices"));   // default on (11-09: parity on the N100)
    private static final int CAPACITY = Integer.getInteger("lab.voxel.stream.capacity", VERTS ? 5 << 20 : 6 << 20);   // faces
    private static final int TINT_CAPACITY = Integer.getInteger("lab.voxel.stream.tints", 8 << 20);   // colours (uints)
    private static final int NMAX = 2048;   // states in the library (the pair table is NMAX^2 x 6 bits)
    private static final int QF = 40, FACE_UINTS = 16, BUCKETS = 24;   // a face's slot: a record (8 uints) or 4 vertices (16)

    private static boolean active, ready;
    private static int d, dm, chunks, nx, ny, minY, secY;
    private static int gridTex, lightTex;
    private static int camCx = Integer.MIN_VALUE, camCz;
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet RESIDENT = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet DIRTY = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet(), STALE = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private static long windowStart;

    private VoxelStream() {}

    // ---- the library, grown on first sight ----

    private static final Map<BlockState, Integer> LOCAL = new IdentityHashMap<>();
    private static final List<BlockState> STATES = new ArrayList<>();
    private static final List<int[]> LIB = new ArrayList<>(), ENTRIES = new ArrayList<>(), VARIANTS = new ArrayList<>();
    private static final List<float[]> QUADS = new ArrayList<>(), OFFSETS = new ArrayList<>();
    private static final List<int[]> PROPS = new ArrayList<>();
    private static int[] pairs = new int[0];
    private static boolean libDirty;
    private static int libBuf, entryBuf, variantBuf, quadBuf, propBuf, pairBuf, offsetBuf, aoBuf;
    private static java.lang.reflect.Field wlist, mselectors;

    private static int localOf(BlockState st) {
        if (st.isAir()) return 0;
        Integer i = LOCAL.get(st);
        if (i != null) return i;
        if (STATES.size() >= NMAX) return 0;
        return addState(st);
    }

    private static int addState(BlockState st) {
        long t0 = System.nanoTime();
        try {
            return addState0(st);
        } finally {
            cpuAddState += System.nanoTime() - t0;
            addedStates++;
        }
    }

    /** for the far LOD: a full cube its model draws (a cull-face quad on every side, nothing translucent) */
    private static boolean lodSolid(BlockState st, net.minecraft.world.level.Level level, BlockPos pos) {
        if (st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL || !st.isCollisionShapeFullBlock(level, pos)) return false;
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(st);
        RandomSource rnd = RandomSource.create(42);
        if (model.getRenderTypes(st, rnd, ModelData.EMPTY).contains(RenderType.translucent())) return false;
        for (Direction d : Direction.values()) {
            rnd.setSeed(42);
            if (model.getQuads(st, d, rnd, ModelData.EMPTY, null).isEmpty()) return false;
        }
        return true;
    }

    private static int addState0(BlockState st) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        int i = STATES.size();
        STATES.add(st);
        LOCAL.put(st, i);
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(0, 64, 0);
        int f = 0;
        if (st.isViewBlocking(level, mp) && st.getLightBlock(level, mp) != 0) f |= 1;
        if (st.isSolidRender(level, mp)) f |= 2;
        if (st.emissiveRendering(level, mp)) f |= 4;
        f |= (st.getLightEmission(level, mp) & 15) << 8;
        if (LOD > 0 && lodSolid(st, level, mp)) {
            f |= 4096;
            if (!Minecraft.getInstance().getBlockRenderer().getBlockModel(st).getRenderTypes(st, RandomSource.create(42), ModelData.EMPTY).contains(RenderType.solid()))
                f |= 8192;   // nothing opaque in its model (leaves): the LOD draws its cutout quads opaque
        }
        PROPS.add(new int[] {f, Float.floatToRawIntBits(st.getShadeBrightness(level, mp))});
        // its row and column of vanilla's Block.shouldRenderFace
        long tp0 = System.nanoTime();
        VoxelCompute.PairWorld pw = new VoxelCompute.PairWorld();
        if (PAIR_CLASSES)   // every state in the library its class (some come in without addState0: air)
            while (STATE_CLASS.size() < STATES.size()) STATE_CLASS.add(pairClass(STATES.get(STATE_CLASS.size()), pw));
        int ci = PAIR_CLASSES ? STATE_CLASS.getInt(i) : -1;
        for (int other = 0; other <= i; other++)
            for (Direction dir : Direction.values()) {
                pw.posB = BlockPos.ZERO.relative(dir);
                if (PAIR_CLASSES) {   // the pair of behaviour classes decides: computed once per pair of classes
                    int co = STATE_CLASS.getInt(other), d = dir.get3DDataValue();
                    setPair(i, other, dir, classPair(pw, ci, co, d, st, STATES.get(other), dir));
                    setPair(other, i, dir, classPair(pw, co, ci, d, STATES.get(other), st, dir));
                    continue;
                }
                pw.a = st;
                pw.b = STATES.get(other);
                setPair(i, other, dir, renderFace(pw, dir));
                pw.a = STATES.get(other);
                pw.b = st;
                setPair(other, i, dir, renderFace(pw, dir));
            }
        long tp1 = System.nanoTime();
        LIB.add(libraryEntry(st, level, mc.getBlockRenderer()));
        long tp2 = System.nanoTime();
        FPROPS.add(fluidProps(st, level));
        long tp3 = System.nanoTime();
        addPairsNs += tp1 - tp0;
        addEntryNs += tp2 - tp1;
        addFluidNs += tp3 - tp2;
        libDirty = true;
        return i;
    }

    // ---- Block.shouldRenderFace line by line, with a cache that does not thrash: the face occlusion shapes reduced to
    // canonical ids (their boxes), the shape join cached by the pair of ids (vanilla's 2048-entry LRU per thread is
    // flushed by a library's thousands of new pairs) ----
    private static final Map<BlockState, int[]> FACE_SHAPE = new java.util.IdentityHashMap<>();
    private static final Map<List<net.minecraft.world.phys.AABB>, Integer> SHAPE_ID = new HashMap<>();
    private static final List<net.minecraft.world.phys.shapes.VoxelShape> SHAPES = new ArrayList<>();
    private static final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap JOIN = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

    private static int faceShape(BlockState st, Direction face, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        int[] ids = FACE_SHAPE.computeIfAbsent(st, k -> {
            int[] a = new int[6];
            java.util.Arrays.fill(a, -1);
            return a;
        });
        int f = face.get3DDataValue();
        if (ids[f] < 0) {
            var shape = st.getFaceOcclusionShape(level, pos, face);
            ids[f] = SHAPE_ID.computeIfAbsent(shape.toAabbs(), k -> {
                SHAPES.add(shape);
                return SHAPES.size() - 1;
            });
        }
        return ids[f];
    }

    /**
     * -Dlab.voxel.stream.pairClasses: a new state's row of face pairs by behaviour class. A block whose class (below Block)
     * declares none of skipRendering / hidesNeighborFace / supportsExternalFaceHiding answers shouldRenderFace from
     * canOcclude, supportsExternalFaceHiding and its six face occlusion shapes alone: states alike in those share a class,
     * and a pair of classes is computed once. Any other block is a class of its own state. On the N100 a new state's ~4700
     * shouldRenderFace calls took ~8 ms (hitches when terrain with new blocks comes in); check with .verifyPairs.
     */
    /**
     * The stream's working parts (compact, bakeTints, skipDark, pairClasses) and the LOD (6 / 10 chunks, -Dlab.voxel.stream.lodNear:
     * 4 / 8; .lod=0 off) are on with -Dlab.voxel.stream: one switch (the settings screen's "GPU terrain"); each can still
     * be set to false for an A/B. -Dlab.voxel.stream.cone: the LOD cone (lodCone=2).
     */
    static boolean on(String flag) {
        return !"false".equals(System.getProperty(flag));
    }

    static final boolean PAIR_CLASSES = on("lab.voxel.stream.pairClasses");
    private static final it.unimi.dsi.fastutil.ints.IntArrayList STATE_CLASS = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private static final Map<Object, Integer> CLASS_KEYS = new HashMap<>();
    private static final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap CLASS_PAIRS = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
    private static final Map<Class<?>, Boolean> CUSTOM_FACES = new java.util.IdentityHashMap<>();
    public static long classPairHits, classPairMisses, classPairDirect;
    /** classes of blocks with their own face logic (one state each): their pairs are never shared - their answer may
     *  depend on which block the other side is (same-block checks), which a class of plain blocks does not keep */
    private static final java.util.BitSet CUSTOM_CLASS = new java.util.BitSet();

    private static boolean customFaces(Class<?> c) {
        return CUSTOM_FACES.computeIfAbsent(c, k -> {
            for (Class<?> x = k; x != null && x != Block.class && x != net.minecraft.world.level.block.state.BlockBehaviour.class; x = x.getSuperclass())
                for (java.lang.reflect.Method m : x.getDeclaredMethods())
                    if (m.getName().equals("skipRendering") || m.getName().equals("hidesNeighborFace") || m.getName().equals("supportsExternalFaceHiding")) return true;
            return false;
        });
    }

    private static int pairClass(BlockState st, VoxelCompute.PairWorld pw) {
        Object key;
        if (customFaces(st.getBlock().getClass())) key = st;   // its own class
        else {
            pw.a = st;
            int[] k = new int[8];
            for (Direction d : Direction.values()) k[d.get3DDataValue()] = faceShape(st, d, pw, BlockPos.ZERO);
            k[6] = st.canOcclude() ? 1 : 0;
            k[7] = st.supportsExternalFaceHiding() ? 1 : 0;
            key = java.util.Arrays.stream(k).boxed().toList();
        }
        int c = CLASS_KEYS.computeIfAbsent(key, x -> CLASS_KEYS.size());
        if (key == st) CUSTOM_CLASS.set(c);
        return c;
    }

    private static boolean classPair(VoxelCompute.PairWorld pw, int ca, int cb, int d, BlockState a, BlockState b, Direction dir) {
        if (CUSTOM_CLASS.get(ca) || CUSTOM_CLASS.get(cb)) {   // own face logic on a side: always asked directly
            classPairDirect++;
            pw.a = a;
            pw.b = b;
            return renderFace(pw, dir);
        }
        long key = ((long) ca << 32 | cb) * 6 + d;
        byte r = CLASS_PAIRS.getOrDefault(key, (byte) 127);
        if (r == 127) {
            classPairMisses++;
            pw.a = a;
            pw.b = b;
            r = (byte) (renderFace(pw, dir) ? 1 : 0);
            CLASS_PAIRS.put(key, r);
        } else {
            classPairHits++;
            if (VERIFY_PAIRS) {
                pw.a = a;
                pw.b = b;
                pairChecks++;
                if ((r != 0) != Block.shouldRenderFace(a, pw, BlockPos.ZERO, dir, pw.posB)) pairMismatches++;
            }
        }
        return r != 0;
    }

    /** pw holds a at ZERO and b at posB (the face's neighbour): Block.shouldRenderFace(a, pw, ZERO, face, posB) */
    private static boolean renderFace(VoxelCompute.PairWorld pw, Direction face) {
        BlockState a = pw.a, b = pw.b;
        if (a.skipRendering(b, face)) return false;
        if (b.hidesNeighborFace(pw, pw.posB, a, face.getOpposite()) && a.supportsExternalFaceHiding()) return false;
        if (!b.canOcclude()) return true;
        int sa = faceShape(a, face, pw, BlockPos.ZERO);
        if (SHAPES.get(sa).isEmpty()) return true;
        int sb = faceShape(b, face.getOpposite(), pw, pw.posB);
        long key = (long) sa << 32 | sb;
        byte r = JOIN.getOrDefault(key, (byte) 127);
        if (r == 127) {
            r = (byte) (net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(SHAPES.get(sa), SHAPES.get(sb), net.minecraft.world.phys.shapes.BooleanOp.ONLY_FIRST) ? 1 : 0);
            JOIN.put(key, r);
        }
        if (VERIFY_PAIRS) {
            pairChecks++;
            if ((r != 0) != Block.shouldRenderFace(a, pw, BlockPos.ZERO, face, pw.posB)) pairMismatches++;
        }
        return r != 0;
    }

    /** -Dlab.voxel.stream.verifyPairs: every pair also by Block.shouldRenderFace, mismatches counted */
    private static final boolean VERIFY_PAIRS = Boolean.getBoolean("lab.voxel.stream.verifyPairs");
    private static long pairChecks, pairMismatches;

    private static void setPair(int a, int b, Direction dir, boolean drawn) {
        int bit = (a * NMAX + b) * 6 + dir.get3DDataValue();
        if (drawn) pairs[bit >>> 5] |= 1 << (bit & 31);
        else pairs[bit >>> 5] &= ~(1 << (bit & 31));
        PAIR_DIRTY.set(bit >>> 5);
    }

    // ---- the library uploaded incrementally: buffers grow by doubling, only new rows (and changed pair words) go up ----
    private static final java.util.BitSet PAIR_DIRTY = new java.util.BitSet();
    private static final int[] LIB_CAP = new int[8], LIB_SENT = new int[8];

    private static int syncRows(int buf, int slot, List<?> rows, int width) {
        int n = rows.size();
        if (buf != 0 && n <= LIB_CAP[slot] && n == LIB_SENT[slot]) return buf;
        int from = LIB_SENT[slot];
        if (buf == 0 || n > LIB_CAP[slot]) {   // a larger buffer: everything again
            if (buf != 0) GlTrack.delBuf(buf);
            LIB_CAP[slot] = Math.max(64, n * 2);
            buf = dynamicBuffer(4L * width * LIB_CAP[slot], null);
            from = 0;
        }
        if (n > from) {
            java.nio.ByteBuffer b = MemoryUtil.memAlloc(4 * width * (n - from));
            for (int i = from; i < n; i++) {
                Object r = rows.get(i);
                if (r instanceof int[] ir) for (int k = 0; k < width; k++) b.putInt(k < ir.length ? ir[k] : 0);
                else {
                    float[] fr = (float[]) r;
                    for (int k = 0; k < width; k++) b.putFloat(k < fr.length ? fr[k] : 0f);
                }
            }
            b.flip();
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buf);
            GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 4L * width * from, b);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            MemoryUtil.memFree(b);
        }
        LIB_SENT[slot] = n;
        return buf;
    }

    /** the changed words of the pair table, in runs */
    private static void syncPairs() {
        if (PAIR_DIRTY.isEmpty()) return;
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, pairBuf);
        for (int w = PAIR_DIRTY.nextSetBit(0); w >= 0; ) {
            int end = PAIR_DIRTY.nextClearBit(w);
            IntBuffer b = MemoryUtil.memAllocInt(end - w);
            b.put(pairs, w, end - w).flip();
            timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 4L * w, b);
            MemoryUtil.memFree(b);
            w = PAIR_DIRTY.nextSetBit(end);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        PAIR_DIRTY.clear();
    }

    /** VoxelCompute's library for one state (entries, variants, quads, offsets, seed offset) */
    private static int[] libraryEntry(BlockState st, net.minecraft.world.level.Level level, net.minecraft.client.renderer.block.BlockRenderDispatcher br) {
        Direction[] sides = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
        RandomSource rnd = RandomSource.create();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(0, 64, 0);
        boolean smooth = Minecraft.useAmbientOcclusion();
        int eStart = ENTRIES.size(), offIdx = 0, seedOff = 512 | 2 | 2 << 3 | 2 << 6;
        boolean dataModel = false;
        try {
            if (STATES.indexOf(st) > 0 && st.getRenderShape() == RenderShape.MODEL) {
                var model = br.getBlockModel(st);
                dataModel = MODS_FALLBACK && takesModelData(model, 0);
                boolean collisionFull = st.isCollisionShapeFullBlock(level, mp);
                int so = VoxelCompute.seedOffset(st);
                if (so != 0) seedOff = so;
                offIdx = offsetTable(st, level);
                boolean dependent = VoxelCompute.seedDependent(model, st, rnd, sides);
                if (dependent && model instanceof net.minecraft.client.resources.model.MultiPartBakedModel mpm) {
                    var selectors = (List<?>) mselectors.get(mpm);
                    java.util.BitSet selected = mpm.getSelectors(st);
                    rnd.setSeed(42);
                    for (RenderType rt : model.getRenderTypes(st, rnd, ModelData.EMPTY)) {
                        int layer = VoxelCompute.layerOf(rt);
                        boolean ao = VoxelCompute.aoOf(model, st, rt, smooth, level, mp);
                        for (Direction side : sides)
                            for (int j = 0; j < selected.length(); j++) {
                                if (!selected.get(j)) continue;
                                var part = (net.minecraft.client.resources.model.BakedModel) ((org.apache.commons.lang3.tuple.Pair<?, ?>) selectors.get(j)).getRight();
                                rnd.setSeed(42);
                                if (!part.getRenderTypes(st, rnd, ModelData.EMPTY).contains(rt)) continue;
                                int vStart = VARIANTS.size(), total = 0, mode = 1;
                                List<net.minecraft.client.resources.model.BakedModel> pv = new ArrayList<>();
                                List<Integer> pws = new ArrayList<>();
                                if (part instanceof net.minecraft.client.resources.model.WeightedBakedModel && VoxelCompute.seedDependent(part, st, rnd, sides)) {
                                    for (Object o : (List<?>) wlist.get(part)) {
                                        var w = (net.minecraft.util.random.WeightedEntry.Wrapper<?>) o;
                                        pv.add((net.minecraft.client.resources.model.BakedModel) w.getData());
                                        pws.add(w.getWeight().asInt());
                                    }
                                } else {
                                    pv.add(part);
                                    pws.add(1);
                                    mode = 0;
                                }
                                for (int v = 0; v < pv.size(); v++) {
                                    int qs = QUADS.size();
                                    rnd.setSeed(42);
                                    for (BakedQuad q : pv.get(v).getQuads(st, side, rnd, ModelData.EMPTY, rt)) addQuad(q, side, layer, ao, collisionFull, offIdx);
                                    VARIANTS.add(new int[] {qs, QUADS.size() - qs, pws.get(v), 0});
                                    total += pws.get(v);
                                }
                                ENTRIES.add(new int[] {vStart, VARIANTS.size() - vStart, total, mode});
                            }
                    }
                } else {
                    List<net.minecraft.client.resources.model.BakedModel> vm = new ArrayList<>();
                    List<Integer> vw = new ArrayList<>();
                    if (dependent && model instanceof net.minecraft.client.resources.model.WeightedBakedModel) {
                        for (Object o : (List<?>) wlist.get(model)) {
                            var w = (net.minecraft.util.random.WeightedEntry.Wrapper<?>) o;
                            vm.add((net.minecraft.client.resources.model.BakedModel) w.getData());
                            vw.add(w.getWeight().asInt());
                        }
                    } else {
                        vm.add(model);
                        vw.add(1);
                    }
                    int vStart = VARIANTS.size(), total = 0;
                    for (int v = 0; v < vm.size(); v++) {
                        var m = vm.get(v);
                        int qs = QUADS.size();
                        rnd.setSeed(42);
                        for (RenderType rt : m.getRenderTypes(st, rnd, ModelData.EMPTY)) {
                            int layer = VoxelCompute.layerOf(rt);
                            boolean ao = VoxelCompute.aoOf(model, st, rt, smooth, level, mp);
                            for (Direction side : sides) {
                                rnd.setSeed(42);
                                for (BakedQuad q : m.getQuads(st, side, rnd, ModelData.EMPTY, rt)) addQuad(q, side, layer, ao, collisionFull, offIdx);
                            }
                        }
                        VARIANTS.add(new int[] {qs, QUADS.size() - qs, vw.get(v), 0});
                        total += vw.get(v);
                    }
                    ENTRIES.add(new int[] {vStart, VARIANTS.size() - vStart, total, 0});
                }
            }
        } catch (ReflectiveOperationException e) {
            LOG.error("[sleep/voxel] stream: library entry for {} failed", st, e);
        }
        int tintMask = 0;   // the tint indices its quads use (BlockColors are asked for those only)
        for (int e = eStart; e < ENTRIES.size(); e++)
            for (int v = ENTRIES.get(e)[0]; v < ENTRIES.get(e)[0] + ENTRIES.get(e)[1]; v++)
                for (int q = VARIANTS.get(v)[0]; q < VARIANTS.get(v)[0] + VARIANTS.get(v)[1]; q++) {
                    int ti = (int) QUADS.get(q)[20];
                    if (ti >= 0 && ti < 4) tintMask |= 1 << ti;
                }
        int flags = tintMask;
        for (int e = eStart; e < ENTRIES.size(); e++)
            for (int v = ENTRIES.get(e)[0]; v < ENTRIES.get(e)[0] + ENTRIES.get(e)[1]; v++)
                for (int q = VARIANTS.get(v)[0]; q < VARIANTS.get(v)[0] + VARIANTS.get(v)[1]; q++)
                    if ((int) QUADS.get(q)[23] == 3) flags |= 16;
        if (!st.getFluidState().isEmpty()) flags |= familyOf(st.getFluidState()) > 0 ? 64 : 32;   // the GPU's fluid (tinted per cell) or vanilla's
        if (dataModel) flags |= VANILLA_MODEL;
        return new int[] {eStart, ENTRIES.size() - eStart, offIdx | seedOff << 16, flags};
    }

    /**
     * Library flag: the state's model computes ModelData from the world (connected textures, Create's casings, Framed
     * Blocks...) - read here with ModelData.EMPTY it would lose that look, so a section holding it is meshed by vanilla.
     */
    static final int VANILLA_MODEL = 128;
    /**
     * Sections whose blocks a mod draws in a way the GPU cannot (VANILLA_MODEL states, block entities' model data, a
     * mod's own section geometry): vanilla's compile meshes them and its opaque layers are drawn for them
     * (VoxelLod.far keeps them; every other section's opaque layers are the GPU's)
     */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet VANILLA_OWNED = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /**
     * vanilla's opaque layers drawn for this section: left to vanilla for mods, and at full detail on the GPU (which skips
     * those blocks there). A section the GPU holds as far LOD is the GPU's alone - its cells take the mods' blocks from
     * their base quads (far away a connected texture's seams do not show).
     */
    public static boolean vanillaOwns(BlockPos origin) {
        if (VANILLA_OWNED.isEmpty()) return false;
        long key = SectionPos.asLong(origin.getX() >> 4, origin.getY() >> 4, origin.getZ() >> 4);
        if (!VANILLA_OWNED.contains(key)) return false;
        Rec r = RECS.get(key);
        return r == null || r.lod == 0;
    }

    private static void owned(long key, boolean owned) {
        if (owned ? VANILLA_OWNED.add(key) : VANILLA_OWNED.remove(key)) ChunkMultiDraw.invalidateLayerCaches();
    }
    /** -Dlab.voxel.stream.modsFallback=false: mods' sections drawn by the GPU anyway (to see what the fallback fixes) */
    static final boolean MODS_FALLBACK = !"false".equals(System.getProperty("lab.voxel.stream.modsFallback"));
    public static long leftForMods, leftForModelData, leftForGeometry;

    /** the model (or a part / variant of it) overrides IBakedModelExtension.getModelData: its quads depend on the world */
    private static boolean takesModelData(net.minecraft.client.resources.model.BakedModel m, int depth) {
        if (m == null || depth > 4) return false;
        try {
            if (m instanceof net.minecraft.client.resources.model.MultiPartBakedModel mpm) {
                for (Object sel : (List<?>) mselectors.get(mpm))
                    if (takesModelData((net.minecraft.client.resources.model.BakedModel) ((org.apache.commons.lang3.tuple.Pair<?, ?>) sel).getRight(), depth + 1)) return true;
                return false;
            }
            if (m instanceof net.minecraft.client.resources.model.WeightedBakedModel) {
                for (Object o : (List<?>) wlist.get(m))
                    if (takesModelData((net.minecraft.client.resources.model.BakedModel) ((net.minecraft.util.random.WeightedEntry.Wrapper<?>) o).getData(), depth + 1)) return true;
                return false;
            }
        } catch (ReflectiveOperationException e) {
            return true;   // cannot look inside: vanilla's mesh, to be safe
        }
        for (Class<?> c = m.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (java.lang.reflect.Method me : c.getDeclaredMethods())
                if (me.getName().equals("getModelData") && me.getParameterCount() == 4) return true;
        return false;
    }

    /** AddSectionGeometryEvent has listeners (mods' own section geometry), asked per section only then */
    private static boolean geometryListened() {
        return false;   // Forge 1.20.1 has no AddSectionGeometryEvent: no mod adds section geometry that way
    }

    private static void addQuad(BakedQuad q, Direction side, int layer, boolean ao, boolean collisionFull, int offIdx) {
        float[] rec = VoxelCompute.quadRecord(q, side, layer, ao, collisionFull);
        rec[37] = offIdx;
        QUADS.add(rec);
    }

    /** VoxelCompute.offsetTable's observation, into this library's tables */
    private static int offsetTable(BlockState st, net.minecraft.world.level.Level level) {
        float[] t = new float[48];
        boolean[] seen = new boolean[48];
        boolean any = false;
        List<float[]> obs = new ArrayList<>();
        for (int x = -40; x < 40; x += 3)
            for (int z = -40; z < 40; z += 3) {
                int y = 64 + ((x * 7 + z * 13) & 31);
                var o = st.getOffset(level, new BlockPos(x, y, z));
                long i = net.minecraft.util.Mth.getSeed(x, 0, z);
                float ox = (float) o.x, oy = (float) o.y, oz = (float) o.z;
                if (ox != 0 || oy != 0 || oz != 0) any = true;
                obs.add(new float[] {(int) (i & 15L), (int) (i >> 4 & 15L), (int) (i >> 8 & 15L), ox, oy, oz});
            }
        if (!any) return 0;
        for (float[] o : obs)
            for (int axis = 0; axis < 3; axis++) {
                int slot = axis * 16 + (int) o[axis];
                if (!seen[slot]) {
                    seen[slot] = true;
                    t[slot] = o[3 + axis];
                }
            }
        for (float[] o : obs)
            if (t[(int) o[0]] != o[3] || t[16 + (int) o[1]] != o[4] || t[32 + (int) o[2]] != o[5]) return 0;
        OFFSETS.add(t);
        return OFFSETS.size();
    }

    // ---- fluids (plan point 4): LiquidBlockRenderer on the GPU, from observed properties of the states ----

    private static final List<int[]> FPROPS = new ArrayList<>();
    /** per family: still, flowing, overlay sprite (u0 u1 v0 v1 each), the still sprite's uvShrinkRatio, overlay?, layer */
    private static final List<float[]> FAMILIES = new ArrayList<>();
    private static final Map<net.minecraft.world.level.material.Fluid, Integer> FAMILY_OF = new HashMap<>();
    private static final List<net.minecraft.world.level.material.FluidState> FAMILY_REP = new ArrayList<>();
    private static int fpropBuf, famBuf;

    /**
     * The fluids the GPU draws: every FlowingFluid source whose client extension leaves renderFluid to vanilla's
     * LiquidBlockRenderer (at most 4: the per-state hide bits are 4 x 6); others stay vanilla's (library flag 32).
     */
    private static void fluidFamilies(net.minecraft.world.level.Level level) {
        FAMILIES.clear();
        FAMILY_OF.clear();
        FAMILY_REP.clear();
        FAMILIES.add(new float[16]);
        FAMILY_REP.add(null);
        for (var f : net.minecraft.core.registries.BuiltInRegistries.FLUID) {
            if (!(f instanceof net.minecraft.world.level.material.FlowingFluid ff) || ff.getSource() != f || FAMILIES.size() > 4) continue;
            var rep = f.defaultFluidState();
            try {
                var spr = net.minecraftforge.client.ForgeHooksClient.getFluidSprites(level, BlockPos.ZERO, rep);
                float[] row = new float[16];
                for (int k = 0; k < 3; k++) {
                    var sp = k < spr.length ? spr[k] : null;
                    if (sp == null) continue;
                    row[k * 4] = sp.getU0();
                    row[k * 4 + 1] = sp.getU1();
                    row[k * 4 + 2] = sp.getV0();
                    row[k * 4 + 3] = sp.getV1();
                }
                row[12] = spr[0].uvShrinkRatio();
                row[13] = spr.length > 2 && spr[2] != null ? 1 : 0;
                row[14] = VoxelCompute.layerOf(net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderLayer(rep));
                FAMILY_OF.put(f, FAMILIES.size());
                FAMILIES.add(row);
                FAMILY_REP.add(rep);
            } catch (RuntimeException e) {
                LOG.warn("[sleep/voxel] stream: fluid {} left to vanilla", f, e);
            }
        }
    }

    private static int familyOf(net.minecraft.world.level.material.FluidState fs) {
        if (fs.isEmpty()) return 0;
        var t = fs.getType();
        var src = t instanceof net.minecraft.world.level.material.FlowingFluid ff ? ff.getSource() : t;
        return FAMILY_OF.getOrDefault(src, 0);
    }

    /**
     * A state's part in LiquidBlockRenderer, observed: [0] family (bits 0-3), isSolid 16, blocksMotion 32, as the block
     * below a fluid its top covers the face 128, as the block above its bottom covers a full-height face 256,
     * isFaceOccludedBySelf per face (bits 9-14), the family's layer translucent 1 << 15, the family has an overlay
     * 1 << 16, any fluid 1 << 22; [1] getOwnHeight; [2] shouldHideAdjacentFluidFace per family and face; [3]
     * shouldDisplayFluidOverlay per family; [4..7] as the side neighbour (N S W E): the greatest fluid height its
     * occlusion shape still covers (Shapes.blockOccudes), -1 when none.
     */
    private static int[] fluidProps(BlockState st, net.minecraft.world.level.Level level) {
        int[] fp = new int[8];
        var fs = st.getFluidState();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(0, 64, 0);
        int x = 0;
        if (st.isSolid()) x |= 16;
        if (st.blocksMotion()) x |= 32;
        boolean occl = st.canOcclude();
        var shape = occl ? st.getOcclusionShape(level, mp) : net.minecraft.world.phys.shapes.Shapes.empty();
        var block = net.minecraft.world.phys.shapes.Shapes.block();
        if (occl && net.minecraft.world.phys.shapes.Shapes.blockOccudes(net.minecraft.world.phys.shapes.Shapes.box(0, 0, 0, 1, 0.8888889F, 1), shape, Direction.DOWN)) x |= 128;
        if (occl && net.minecraft.world.phys.shapes.Shapes.blockOccudes(block, shape, Direction.UP)) x |= 256;
        for (Direction face : Direction.values())
            if (occl && net.minecraft.world.phys.shapes.Shapes.blockOccudes(block, shape, face.getOpposite())) x |= 1 << (9 + face.get3DDataValue());
        if (!fs.isEmpty()) x |= 1 << 22;
        int fam = familyOf(fs);
        x |= fam;
        if (fam > 0) {
            if (FAMILIES.get(fam)[14] == 3) x |= 1 << 15;
            if (FAMILIES.get(fam)[13] != 0) x |= 1 << 16;
        }
        fp[0] = x;
        fp[1] = Float.floatToRawIntBits(fs.getOwnHeight());
        for (int f = 1; f < FAMILY_REP.size(); f++) {
            var rep = FAMILY_REP.get(f);
            for (Direction d : Direction.values())
                // 1.20.1: LiquidBlockRenderer.isNeighborSameFluid (NeoForge's default of shouldHideAdjacentFluidFace)
                if (st.getFluidState().getType().isSame(rep.getType())) fp[2] |= 1 << ((f - 1) * 6 + d.get3DDataValue());
            if (st.shouldDisplayFluidOverlay(level, mp, rep)) fp[3] |= 1 << (f - 1);
        }
        Direction[] sides = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (int k = 0; k < 4; k++) fp[4 + k] = Float.floatToRawIntBits(occl ? coveredHeight(shape, sides[k]) : -1f);
        return fp;
    }

    /** the greatest h with Shapes.blockOccudes(box(0, 0, 0, 1, h, 1), shape, side) (monotone: a taller face is harder to cover) */
    private static float coveredHeight(net.minecraft.world.phys.shapes.VoxelShape shape, Direction side) {
        if (net.minecraft.world.phys.shapes.Shapes.blockOccudes(net.minecraft.world.phys.shapes.Shapes.block(), shape, side)) return 1f;
        if (!net.minecraft.world.phys.shapes.Shapes.blockOccudes(net.minecraft.world.phys.shapes.Shapes.box(0, 0, 0, 1, 1e-4, 1), shape, side)) return -1f;
        double lo = 1e-4, hi = 1;
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) / 2;
            if (net.minecraft.world.phys.shapes.Shapes.blockOccudes(net.minecraft.world.phys.shapes.Shapes.box(0, 0, 0, 1, mid, 1), shape, side)) lo = mid;
            else hi = mid;
        }
        return (float) lo;
    }

    private static int intBuffer(int old, List<int[]> rows, int width, int min) {
        if (old != 0) GlTrack.delBuf(old);
        IntBuffer b = MemoryUtil.memAllocInt(Math.max(min, rows.size()) * width);
        for (int[] r : rows) b.put(r, 0, width);
        while (b.hasRemaining()) b.put(0);
        b.flip();
        int id = VoxelCompute.storage(b);
        MemoryUtil.memFree(b);
        return id;
    }

    private static int floatBuffer(int old, List<float[]> rows, int width) {
        if (old != 0) GlTrack.delBuf(old);
        FloatBuffer b = MemoryUtil.memAllocFloat(Math.max(1, rows.size()) * width);
        for (float[] r : rows) b.put(r, 0, width);
        while (b.hasRemaining()) b.put(0f);
        b.flip();
        int id = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, b, 0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        MemoryUtil.memFree(b);
        return id;
    }

    private static void uploadLibrary() {
        long t0 = System.nanoTime();
        try {
            uploadLibrary0();
        } finally {
            cpuLibUpload += System.nanoTime() - t0;
            libUploads++;
        }
    }

    private static void uploadLibrary0() {
        libBuf = syncRows(libBuf, 0, LIB, 4);
        entryBuf = syncRows(entryBuf, 1, ENTRIES, 4);
        variantBuf = syncRows(variantBuf, 2, VARIANTS, 4);
        propBuf = syncRows(propBuf, 3, PROPS, 2);
        fpropBuf = syncRows(fpropBuf, 4, FPROPS, 8);
        famBuf = syncRows(famBuf, 5, FAMILIES, 16);
        quadBuf = syncRows(quadBuf, 6, QUADS, QF);
        offsetBuf = syncRows(offsetBuf, 7, OFFSETS, 48);
        syncPairs();
        libDirty = false;
    }

    // ---- the grid: sections uploaded on demand ----

    private static int imod(int a, int n) {
        return Math.floorMod(a, n);
    }

    private static ShortBuffer upBlocks;
    /** -Dlab.voxel.stream.uploads: section uploads per frame at most (a batch takes fewer sections when it would pass it) */
    private static final int UPLOADS = Integer.getInteger("lab.voxel.stream.uploads", 48);
    private static int framesUploads;
    private static ByteBuffer upLight;

    private static java.lang.reflect.Field pcData, dlData, dlDefault;
    private static java.lang.reflect.Method storageAcc, paletteAcc;
    private static final int[] PAL_MAP = new int[4096];

    /** a section's blocks (local states) and light into the toroidal grid (zeros where nothing is loaded) */
    private static void uploadSection(long key) {
        long tu = System.nanoTime();
        try {
            uploadSection0(key);
        } finally {
            long d = System.nanoTime() - tu;
            cpuUploads += d;
            fRead += d;
        }
    }

    private static void uploadSection0(long key) {
        var level = Minecraft.getInstance().level;
        int cx = SectionPos.x(key), sy = SectionPos.y(key), cz = SectionPos.z(key);
        int si = sy - level.getMinSection();
        if (si < 0 || si >= secY) return;
        LevelChunk c = RESIDENT.contains(ChunkKey(cx, cz)) ? level.getChunkSource().getChunk(cx, cz, false) : null;
        LevelChunkSection sec = c == null ? null : c.getSections()[si];
        int gx = imod(cx * 16, nx), gy = si * 16, gz = imod(cz * 16, nx);
        try {
            packet(key, c, sec, cx, sy, cz, gx, gy, gz, false);
        } catch (ReflectiveOperationException e) {
            LOG.error("[sleep/voxel] stream: section read failed", e);
        }
        uploads++;
        framesUploads++;
    }

    // packets: the frame's section data as the chunk holds it (palette + bit storage + light nibbles), unpacked on the GPU
    private static IntBuffer pk;
    private static final List<Integer> PK_STARTS = new ArrayList<>();
    private static int packetBuf, packetIndexBuf, unpackProg;
    private static final int PK_CAPACITY = 64 * 2400;   // uints: 64 sections of the largest packet

    private static void packet(long key, LevelChunk c, LevelChunkSection sec, int cx, int sy, int cz, int gx, int gy, int gz, boolean lightOnly) throws ReflectiveOperationException {
        if (pk == null) pk = MemoryUtil.memAllocInt(PK_CAPACITY);
        if (pk.position() + 2400 > PK_CAPACITY) flushUnpack();
        var level = Minecraft.getInstance().level;
        int start = pk.position();
        PK_STARTS.add(start);
        int flags = 0, bits = 0, psz = 0, tintMask = 0;
        net.minecraft.util.BitStorage storage = null;
        net.minecraft.world.level.chunk.Palette<BlockState> palette = null;
        if (c == null) flags |= 8;   // not resident: zeros
        else if (lightOnly) flags |= 32;
        else if (sec != null && !sec.hasOnlyAir()) {
            Object data = pcData.get(sec.getStates());
            if (storageAcc == null) {   // PalettedContainer.Data's accessors, opened once
                var comps = data.getClass().getRecordComponents();
                storageAcc = comps[1].getAccessor();
                paletteAcc = comps[2].getAccessor();
                storageAcc.setAccessible(true);
                paletteAcc.setAccessible(true);
            }
            storage = (net.minecraft.util.BitStorage) storageAcc.invoke(data);
            @SuppressWarnings("unchecked")
            var pal = (net.minecraft.world.level.chunk.Palette<BlockState>) paletteAcc.invoke(data);
            palette = pal;
            bits = storage.getBits();
            if (palette instanceof net.minecraft.world.level.chunk.GlobalPalette) {   // direct: local ids per cell
                flags |= 16;
                NEEDS_VANILLA.put(key, true);
            }
            else {
                psz = palette.getSize();
                for (int i = 0; i < psz; i++) {
                    PAL_MAP[i] = localOf(palette.valueFor(i));
                    tintMask |= LIB.get(PAL_MAP[i])[3];
                }
                NEEDS_VANILLA.put(key, (tintMask & 32) != 0);
            }
        } else if ((flags & 32) == 0) {
            psz = 1;   // all air: palette {0}, no storage
            NEEDS_VANILLA.put(key, false);
        }
        if (c == null) NEEDS_VANILLA.remove(key);
        tintMask &= 15 | 64;
        if (lightOnly) psz = 0;
        if (psz == 1 && storage == null) PAL_MAP[0] = 0;
        // light
        var skyL = level.getLightEngine().getLayerListener(LightLayer.SKY);
        var blockL = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        DataLayer sky = c == null ? null : skyL.getDataLayerData(SectionPos.of(cx, sy, cz)), blk = c == null ? null : blockL.getDataLayerData(SectionPos.of(cx, sy, cz));
        byte[] sd = sky == null ? null : (byte[]) dlData.get(sky), bd = blk == null ? null : (byte[]) dlData.get(blk);
        int sDef = sky == null ? 0 : dlDefault.getInt(sky), bDef = blk == null ? 0 : dlDefault.getInt(blk);
        if (c != null && sky == null) flags |= 2;   // the sky light per column
        if (sd != null) flags |= 1;
        if (bd != null) flags |= 4;
        int header = start;
        pk.position(start + 7);   // header written last (the offsets)
        // palette: local ids as ushort pairs
        if ((flags & 16) == 0) {
            for (int i = 0; i < psz; i += 2) pk.put((PAL_MAP[i] & 0xFFFF) | (i + 1 < psz ? (PAL_MAP[i + 1] & 0xFFFF) << 16 : 0));
        }
        int offS = pk.position() - start;
        if ((flags & 16) != 0) {   // a global palette (over 256 states in the section): the cells' local ids, ushort pairs
            for (int i = 0; i < 4096; i += 2) {
                int a = localOf(palette.valueFor(storage.get(i))), b = localOf(palette.valueFor(storage.get(i + 1)));
                pk.put((a & 0xFFFF) | (b & 0xFFFF) << 16);
            }
        } else if (storage != null && bits > 0) {
            long[] raw = storage.getRaw();
            for (long w : raw) pk.put((int) w).put((int) (w >>> 32));
        }
        int offSky = pk.position() - start;
        if (sd != null) packBytes(sd);
        int offCol = pk.position() - start;
        if ((flags & 2) != 0) {
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (int i = 0; i < 256; i += 4) {
                int w = 0;
                for (int k = 0; k < 4; k++) {
                    int col = i + k, x = col & 15, z = col >> 4;
                    w |= (skyL.getLightValue(mp.set(cx * 16 + x, sy * 16 + 15, cz * 16 + z)) & 255) << (k * 8);
                }
                pk.put(w);
            }
        }
        int offBlk = pk.position() - start;
        if (bd != null) packBytes(bd);
        int end = pk.position();
        pk.put(header, gx | gy << 10 | gz << 20);
        pk.put(header + 1, bits | psz << 8 | flags << 20);
        pk.put(header + 2, sDef | bDef << 4);
        pk.put(header + 3, offS);
        pk.put(header + 4, offSky);
        pk.put(header + 5, offBlk);
        pk.put(header + 6, offCol);
        pk.position(end);
        if (lightOnly) return;
        // the tints need the cells on the CPU: only sections whose palette holds a tinted state
        if (tintMask != 0 && storage != null) {
            long tt = System.nanoTime();
            if ((flags & 16) != 0) {   // a direct palette (rare): the cells' local ids decoded here
                short[] cells = new short[4096];
                for (int i = 0; i < 4096; i++) {
                    int x = i & 15, z = i >> 4 & 15, y = i >> 8;
                    cells[x + 16 * y + 256 * z] = (short) localOf(palette.valueFor(storage.get(i)));
                }
                tintsAsync(key, cx, sy, cz, tintMask, cells, null, 0, null);
            } else {
                long[] raw = bits == 0 ? null : storage.getRaw();
                long h = 1469598103934665603L ^ bits ^ (long) tintMask << 8;
                if (raw != null) for (long w : raw) h = (h ^ w) * 1099511628211L;
                for (int i = 0; i < psz; i++) h = (h ^ PAL_MAP[i]) * 1099511628211L;
                // the colours depend on the cells' states and positions only (the client's biomes do not change): the
                // same contents keep their tints
                if (TINTS.containsKey(key) && TINT_HASH.getOrDefault(key, 0L) == h) tintsKept++;
                else {
                    TINT_HASH.put(key, h);
                    tintsAsync(key, cx, sy, cz, tintMask, null, raw == null ? null : raw.clone(), bits, java.util.Arrays.copyOf(PAL_MAP, psz));
                }
            }
            cpuTints += System.nanoTime() - tt;
        } else {
            TINT_GEN.addTo(key, 1);   // a pending result for older contents is dropped
            TINT_PENDING.remove(key);
            TINT_HASH.remove(key);
            clearTints(key);
        }
    }

    private static void packBytes(byte[] b) {
        for (int i = 0; i < b.length; i += 4) pk.put((b[i] & 255) | (b[i + 1] & 255) << 8 | (b[i + 2] & 255) << 16 | (b[i + 3] & 255) << 24);
    }

    /** the frame's packets to the GPU, unpacked into the grid and the light (one work group per section) */
    private static void flushUnpack() {
        if (PK_STARTS.isEmpty()) return;
        long tf = System.nanoTime();
        try {
            flushUnpack0();
        } finally {
            fFlush += System.nanoTime() - tf;
        }
    }

    private static void flushUnpack0() {
        pk.flip();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, packetBuf);
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, pk);
        int[] starts = new int[PK_STARTS.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = PK_STARTS.get(i);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, packetIndexBuf);
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, starts);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        GlStateManager._glUseProgram(unpackProg);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, packetBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, packetIndexBuf);
        org.lwjgl.opengl.GL42C.glBindImageTexture(0, gridTex, 0, true, 0, org.lwjgl.opengl.GL15C.GL_WRITE_ONLY, GL30C.GL_R16UI);
        org.lwjgl.opengl.GL42C.glBindImageTexture(1, lightTex, 0, true, 0, org.lwjgl.opengl.GL15C.GL_WRITE_ONLY, GL30C.GL_R8UI);
        elapsedBegin(2);
        GL43C.glDispatchCompute(starts.length, 1, 1);
        elapsedEnd();
        GL43C.glMemoryBarrier(org.lwjgl.opengl.GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT | org.lwjgl.opengl.GL42C.GL_TEXTURE_FETCH_BARRIER_BIT | org.lwjgl.opengl.GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        org.lwjgl.opengl.GL42C.glBindImageTexture(0, 0, 0, true, 0, org.lwjgl.opengl.GL15C.GL_WRITE_ONLY, GL30C.GL_R16UI);
        org.lwjgl.opengl.GL42C.glBindImageTexture(1, 0, 0, true, 0, org.lwjgl.opengl.GL15C.GL_WRITE_ONLY, GL30C.GL_R8UI);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, 0);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, 0);
        GlStateManager._glUseProgram(0);
        net.minecraft.client.renderer.GameRenderer.getPositionShader().clear();
        PK_STARTS.clear();
        pk.clear();
        unpacks += starts.length;
    }

    private static long unpacks;

    private static final String UNPACK = """
                #version 430
                layout(local_size_x = 256) in;
                layout(std430, binding = 0) buffer Packets { uint pk[]; };
                layout(std430, binding = 1) buffer Index { uint starts[]; };
                layout(r16ui, binding = 0) uniform writeonly uimage3D GridImg;
                layout(r8ui, binding = 1) uniform writeonly uimage3D LightImg;
                uint u16(uint at, uint i) { uint w = pk[at + (i >> 1u)]; return (i & 1u) == 0u ? (w & 0xFFFFu) : (w >> 16u); }
                void main() {
                    uint base = starts[gl_WorkGroupID.x];
                    uint cpos = pk[base], h = pk[base + 1u], defs = pk[base + 2u];
                    uint offS = base + pk[base + 3u], offSky = base + pk[base + 4u], offBlk = base + pk[base + 5u], offCol = base + pk[base + 6u];
                    ivec3 g = ivec3(int(cpos & 1023u), int((cpos >> 10u) & 1023u), int((cpos >> 20u) & 1023u));
                    uint bits = h & 255u, flags = h >> 20u;
                    for (uint i = gl_LocalInvocationIndex; i < 4096u; i += 256u) {
                        uint x = i & 15u, z = (i >> 4u) & 15u, y = i >> 8u;   // the container's order: y << 8 | z << 4 | x
                        uint local = 0u, s = 0u, b = 0u;
                        if ((flags & 8u) == 0u) {
                            if ((flags & 16u) != 0u) local = u16(offS, i);
                            else {
                                uint pidx = 0u;
                                if (bits > 0u) {   // SimpleBitStorage: values never cross a 64-bit word
                                    uint vpl = 64u / bits, cell = i / vpl, sh = (i - cell * vpl) * bits;
                                    uint lo = pk[offS + cell * 2u], hi = pk[offS + cell * 2u + 1u], v;
                                    if (sh >= 32u) v = hi >> (sh - 32u);
                                    else if (sh + bits > 32u) v = (lo >> sh) | (hi << (32u - sh));
                                    else v = lo >> sh;
                                    pidx = v & ((1u << bits) - 1u);
                                }
                                local = u16(base + 7u, pidx);
                            }
                            if ((flags & 1u) != 0u) s = (pk[offSky + (i >> 3u)] >> ((i & 7u) * 4u)) & 15u;
                            else if ((flags & 2u) != 0u) { uint col = z * 16u + x; s = (pk[offCol + (col >> 2u)] >> ((col & 3u) * 8u)) & 255u; }
                            else s = defs & 15u;
                            if ((flags & 4u) != 0u) b = (pk[offBlk + (i >> 3u)] >> ((i & 7u) * 4u)) & 15u;
                            else b = (defs >> 4u) & 15u;
                        }
                        ivec3 at = g + ivec3(int(x), int(y), int(z));
                        if ((flags & 32u) == 0u) imageStore(GridImg, at, uvec4(local));
                        imageStore(LightImg, at, uvec4((s << 4u) | b));
                    }
                }
                """;

    private static final java.util.concurrent.ExecutorService TINT_POOL = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sleep-voxel-tint");
        t.setDaemon(true);
        return t;
    });
    /** the frame's tint jobs, handed to the two workers in two batches at the frame's end (one wake-up each, not one per section) */
    private static final List<Runnable> TINT_JOBS = new ArrayList<>();

    private static void flushTintJobs() {
        if (TINT_JOBS.isEmpty()) return;
        int half = (TINT_JOBS.size() + 1) / 2;
        List<Runnable> a = new ArrayList<>(TINT_JOBS.subList(0, half)), b = new ArrayList<>(TINT_JOBS.subList(half, TINT_JOBS.size()));
        TINT_JOBS.clear();
        TINT_POOL.execute(() -> a.forEach(Runnable::run));
        if (!b.isEmpty()) TINT_POOL.execute(() -> b.forEach(Runnable::run));
    }

    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap TINT_HASH = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private static long tintsKept;
    private static int[] tintLibMask = new int[0];
    private static BlockState[] tintLibStates = new BlockState[0];
    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> TINT_DONE = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap TINT_GEN = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static final it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap NEEDS_VANILLA = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();

    /**
     * The section's tinted cells coloured by vanilla's own BlockColors (biome blending included, every mod's colours
     * too), per tint index its quads use: blocks of 4096 colours (RGB, cell order x + 16 y + 256 z) in the tint arena.
     * Computed off the render thread (vanilla's meshing workers call BlockColors too); drainTints uploads the newest.
     */
    private static void tintsAsync(long key, int cx, int sy, int cz, int mask, short[] given, long[] raw, int bits, int[] pmap) {
        int gen = TINT_GEN.addTo(key, 1) + 1;
        TINT_PENDING.add(key);
        if (tintLibMask.length != LIB.size()) {   // the library's tint masks and states, as arrays the workers may read
            int[] lm = new int[LIB.size()];
            BlockState[] ls = new BlockState[LIB.size()];
            for (int i = 0; i < lm.length; i++) {
                lm[i] = LIB.get(i)[3] & (15 | 64);
                ls[i] = STATES.get(i);
            }
            tintLibStates = ls;
            tintLibMask = lm;
        }
        int[] libMask = tintLibMask;
        BlockState[] libStates = tintLibStates;
        var level = Minecraft.getInstance().level;
        var colors = Minecraft.getInstance().getBlockColors();
        TINT_JOBS.add(() -> {
            // the cells' local ids (grid order x + 16 y + 256 z) from the section's own bit storage words
            short[] cells = given;
            if (cells == null) {
                cells = new short[4096];
                int vpl = bits == 0 ? 1 : 64 / bits;
                long m1 = bits == 0 ? 0 : (1L << bits) - 1;
                for (int i = 0; i < 4096; i++) {
                    int idx = bits == 0 ? 0 : (int) ((raw[i / vpl] >>> ((i % vpl) * bits)) & m1);
                    int x = i & 15, z = i >> 4 & 15, y = i >> 8;
                    cells[x + 16 * y + 256 * z] = (short) (idx < pmap.length ? pmap[idx] : 0);
                }
            }
            int[] libMasks = new int[4096];
            BlockState[] sts = new BlockState[4096];
            for (int i = 0; i < 4096; i++) {
                int local = cells[i] & 0xFFFF, m = local < libMask.length ? libMask[local] : 0;
                libMasks[i] = m;
                if (m != 0) sts[i] = libStates[local];
            }
            int n = Integer.bitCount(mask & 15) + ((mask & 64) != 0 ? 1 : 0), map = 0xFFFFFFFF, k = 0;
            for (int ti = 0; ti < 4; ti++) if ((mask >> ti & 1) != 0) map = map & ~(0xFF << (ti * 8)) | k++ << (ti * 8);
            int fk = (mask & 64) != 0 ? k : 0xFF;
            int count = 0;
            int[] idxOf = new int[4096];
            for (int i = 0; i < 4096; i++) idxOf[i] = libMasks[i] != 0 ? count++ : -1;
            int[] out = new int[256 + n * count];
            for (int i = 0; i < 4096; i++) if (libMasks[i] != 0) out[i >> 5] |= 1 << (i & 31);
            for (int w = 0, run = 0; w < 128; w++) {
                out[128 + w] = run;
                run += Integer.bitCount(out[w]);
            }
            java.util.Arrays.fill(out, 256, out.length, 0xFFFFFFFF);
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            try {
                for (int i = 0; i < 4096; i++) {
                    int m = libMasks[i];
                    if (m == 0) continue;
                    mp.set(cx * 16 + (i & 15), sy * 16 + (i >> 4 & 15), cz * 16 + (i >> 8));
                    for (int ti = 0; ti < 4; ti++)
                        if ((m >> ti & 1) != 0) out[256 + (map >> (ti * 8) & 0xFF) * count + idxOf[i]] = colors.getColor(sts[i], level, mp, ti) & 0xFFFFFF;
                    if ((m & 64) != 0) {
                        var fs = sts[i].getFluidState();
                        out[256 + fk * count + idxOf[i]] = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fs).getTintColor(fs, level, mp);
                    }
                }
            } catch (RuntimeException e) {
                return;   // the world changed under it: a newer upload will ask again
            }
            TINT_DONE.add(new Object[] {key, gen, out, n, map, fk, count});
        });
    }

    /** the finished colours: uploaded when still the newest for a resident section */
    private static void drainTints() {
        Object[] r;
        while ((r = TINT_DONE.poll()) != null) {
            long key = (Long) r[0];
            if (TINT_GEN.get(key) == (Integer) r[1]) TINT_PENDING.remove(key);
            if (TINT_GEN.get(key) != (Integer) r[1] || !RESIDENT.contains(ChunkKey(SectionPos.x(key), SectionPos.z(key)))) continue;
            int[] out = (int[]) r[2];
            int map = (Integer) r[4], fk = (Integer) r[5], count = (Integer) r[6];
            clearTints(key);
            int start = allocTint(out.length);
            if (start < 0) continue;
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, tintBuf);
            IntBuffer tb = MemoryUtil.memAllocInt(out.length);
            tb.put(out).flip();
            timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 4L * start, tb);
            MemoryUtil.memFree(tb);
            TINTS.put(key, new int[] {start, out.length});
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, sectionTintBuf);
            timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 16L * slotOf(key), new int[] {start, map, fk, count});
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            if (BAKE && VERTS) {
                Rec rec = RECS.get(key);
                if (rec != null && rec.alloc >= 0) bakeAdd(rec, true);
            }
        }
        if (BAKE && VERTS) bakeFlush();
    }

    // ---- -Dlab.voxel.stream.bakeTints (with .vertices): tint x shade baked into the opaque vertices as RGB8, as vanilla's
    //      vertex colour - the vertex stage reads it instead of looking the tint up (3-4 dependent loads per vertex) ----
    private static final boolean BAKE = on("lab.voxel.stream.bakeTints");
    /** sections with a tint job for newer contents in flight: their tinted vertices wait for it (a baked vertex loses its cell) */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet TINT_PENDING = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static final it.unimi.dsi.fastutil.ints.IntArrayList BAKE_RANGES = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private static final int[] BAKE_BUFS = new int[3];
    private static final long[] BAKE_CAPS = new long[3];
    private static int bakeProg, bakeRing;
    private static long baked;
    private static final String BAKE_CS = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 13) buffer Out { uint counter; uint capacity; uint pad0; uint pad1; uint faces[]; };
            layout(std430, binding = 3) buffer SectionTints { uvec4 sectionTints[]; };
            layout(std430, binding = 6) buffer Tints { uint tints[]; };
            layout(std430, binding = 8) buffer Ranges { uvec4 rg[]; };   // first face, faces, slot, tints valid
            uint tintAt(uvec4 st, uint k, uint c) {
                uint w = c >> 5u, bit = c & 31u;
                uint m = tints[st.x + w];
                if (((m >> bit) & 1u) == 0u) return 0xFFFFFFFFu;
                return tints[st.x + 256u + k * st.w + tints[st.x + 128u + w] + bitCount(m & ((1u << bit) - 1u))];
            }
            void main() {
                uvec4 r = rg[gl_WorkGroupID.x];
                uvec4 st = sectionTints[r.z];
                for (uint v = gl_LocalInvocationID.x; v < r.y * 4u; v += 64u) {
                    uint b = (r.x * 4u + v) * 4u;
                    uint w3 = faces[b + 3u];
                    if ((w3 >> 24u) != 0u) continue;   // baked already
                    uint w1 = faces[b + 1u];
                    uint ct = w1 >> 16u, code = ct >> 12u;
                    vec3 t = vec3(1.0);
                    if (code != 0u) {
                        if (r.w == 0u) continue;   // newer tints on the way
                        uint k = code == 5u ? (st.z & 0xFFu) : (st.y >> ((code - 1u) * 8u)) & 0xFFu;
                        if (k == 0xFFu) continue;   // no tints for the section (yet): the vertex stage looks it up
                        uint col = tintAt(st, k, ct & 4095u);
                        t = vec3(float((col >> 16u) & 255u), float((col >> 8u) & 255u), float(col & 255u)) / 255.0;
                    }
                    uvec3 c8 = uvec3(clamp(round(t * (float(w3 & 255u) / 255.0) * 255.0), vec3(0.0), vec3(255.0)));
                    faces[b + 1u] = (w1 & 0xFFFFu) | (c8.r | c8.g << 8u) << 16u;
                    faces[b + 3u] = (w3 & 0x00FFFF00u) | c8.b | 1u << 24u;
                }
            }
            """;

    private static void bakeAdd(Rec r, boolean tintsValid) {
        for (int q = 0; q < 18; q++) {
            if (r.count[q] == 0) continue;
            BAKE_RANGES.add(r.base[q]);
            BAKE_RANGES.add(r.count[q]);
            BAKE_RANGES.add(r.slot);
            BAKE_RANGES.add(tintsValid ? 1 : 0);
        }
    }

    private static void bakeFlush() {
        int n = BAKE_RANGES.size() / 4;
        if (n == 0) return;
        if (bakeProg == 0) bakeProg = VoxelCompute.program(BAKE_CS, null, null);
        int ri = bakeRing++ % 3;
        if (16L * n > BAKE_CAPS[ri]) {
            if (BAKE_BUFS[ri] != 0) GlTrack.delBuf(BAKE_BUFS[ri]);
            BAKE_CAPS[ri] = 16L * (n + n / 2 + 256);
            BAKE_BUFS[ri] = dynamicBuffer(BAKE_CAPS[ri], null);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, BAKE_BUFS[ri]);
        IntBuffer rb = MemoryUtil.memAllocInt(n * 4);
        rb.put(BAKE_RANGES.elements(), 0, n * 4).flip();
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, rb);
        MemoryUtil.memFree(rb);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        BAKE_RANGES.clear();
        int prog = GlStateManager._getInteger(GL20C.GL_CURRENT_PROGRAM);
        GlStateManager._glUseProgram(bakeProg);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 13, outBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, sectionTintBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tintBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 8, BAKE_BUFS[ri]);
        GL43C.glDispatchCompute(n, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        for (int bnd : new int[] {13, 3, 6, 8}) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bnd, 0);
        GlStateManager._glUseProgram(prog);
        baked += n;
    }

    private static void clearTints(long key) {
        int[] old = TINTS.remove(key);
        if (old != null) releaseTint(old[0], old[1]);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, sectionTintBuf);
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 16L * slotOf(key), new int[] {0, 0xFFFFFFFF, 0xFFFFFFFF, 0});
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
    }

    // ---- vanilla's section compile taken over (plan point 3) ----

    private static java.lang.reflect.Method updateGlobalM;
    private static java.lang.reflect.Field csVisibility, csRenderable;
    private static long addPairsNs, addEntryNs, addFluidNs;
    private static long takenOver, leftToVanilla, cpuTakeOver, cpuAddState, addedStates, cpuLibUpload, libUploads, addMark, libMark;

    /**
     * ChunkRenderDispatcher.RenderChunk.rebuildSectionAsync / compileSync: a section the GPU meshes, without
     * fluids or translucent blocks (those still need vanilla's mesh), gets its compiled state here instead of a CPU
     * mesh - vanilla's own VisGraph over its opaque cells (the occlusion graph goes on working) and the block entities
     * with a renderer, sorted as SectionCompiler.handleBlockEntity does. True: vanilla's compile is skipped.
     */
    public static boolean takeOver(Object renderSection) {
        if (!active || !ready || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return false;
        long t0 = System.nanoTime();
        try {
            return takeOver0(renderSection);
        } finally {
            cpuTakeOver += System.nanoTime() - t0;
        }
    }

    private static boolean takeOver0(Object renderSection) {
        var rs = (net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk) renderSection;
        BlockPos o = rs.getOrigin();
        var mc = Minecraft.getInstance();
        var camPos = mc.gameRenderer.getMainCamera().getPosition();   // not camCx: compileSections runs before this frame's moved()
        if (Math.abs((o.getX() >> 4) - (net.minecraft.util.Mth.floor(camPos.x) >> 4)) > d || Math.abs((o.getZ() >> 4) - (net.minecraft.util.Mth.floor(camPos.z) >> 4)) > d)
            return false;   // beyond the GPU's area
        var level = mc.level;
        try {
            if (updateGlobalM == null) {
                Class<?> rsc = net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk.class;
                updateGlobalM = rsc.getDeclaredMethod(dev.lull.Names.method("net/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk", "updateGlobalBlockEntities", "(Ljava/util/Collection;)V"), java.util.Collection.class);
                updateGlobalM.setAccessible(true);
                Class<?> csc = net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.CompiledChunk.class;
                csVisibility = csc.getDeclaredField(dev.lull.Names.field("net/minecraft/client/renderer/chunk/ChunkRenderDispatcher$CompiledChunk", "visibilitySet"));
                csVisibility.setAccessible(true);
                csRenderable = csc.getDeclaredField(dev.lull.Names.field("net/minecraft/client/renderer/chunk/ChunkRenderDispatcher$CompiledChunk", "renderableBlockEntities"));
                csRenderable.setAccessible(true);
            }
            LevelChunk c = level.getChunkSource().getChunk(o.getX() >> 4, o.getZ() >> 4, false);
            if (c == null) return false;
            int si = (o.getY() >> 4) - level.getMinSection();
            LevelChunkSection sec = si >= 0 && si < secY ? c.getSections()[si] : null;
            long okey = SectionPos.asLong(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4);
            // models that take their look from the world (VANILLA_MODEL): vanilla meshes and draws the section
            if (sec != null && !sec.hasOnlyAir() && sec.maybeHas(st -> (LIB.get(localOf(st))[3] & VANILLA_MODEL) != 0)) {
                leftForMods++;
                owned(okey, true);
                return false;
            }
            // the contents now (not as last uploaded): fluids and translucent quads still need vanilla's mesh (its
            // translucent layer; the opaque ones stay the GPU's); a direct (global) palette answers "maybe"
            if (sec != null && !sec.hasOnlyAir() && sec.maybeHas(st -> (LIB.get(localOf(st))[3] & 32) != 0 || STATES.size() >= NMAX)) {
                leftToVanilla++;
                owned(okey, false);
                return false;
            }
            // mods: block entities' model data in this section (their models draw with it), or a mod's own geometry for
            // it (AddSectionGeometryEvent) - vanilla's compile draws both, the GPU path would lose them
            var chunkData = MODS_FALLBACK ? level.getModelDataManager().getAt(new net.minecraft.world.level.ChunkPos(o)) : null;
            it.unimi.dsi.fastutil.longs.LongArrayList modelData = new it.unimi.dsi.fastutil.longs.LongArrayList();
            if (chunkData != null)
                for (BlockPos bp0 : chunkData.keySet()) if (bp0.getY() >= o.getY() && bp0.getY() < o.getY() + 16) modelData.add(bp0.asLong());
            if (!modelData.isEmpty()) {
                // the states that block entities hand model data: from now on drawn by vanilla's mesh only (the GPU's
                // mesher skips them; sections it meshed with them already are meshed again)
                boolean marked = false;
                for (long bp : modelData) {
                    BlockState bs = level.getBlockState(BlockPos.of(bp));
                    int li = localOf(bs);
                    if (li > 0 && (LIB.get(li)[3] & VANILLA_MODEL) == 0) {
                        LIB.get(li)[3] |= VANILLA_MODEL;
                        marked = true;
                    }
                }
                if (marked) {
                    libDirty = true;
                    DIRTY.addAll(RECS.keySet());
                }
                leftForModelData++;
                owned(okey, true);
                return false;
            }
            owned(okey, false);
            List<net.minecraft.world.level.block.entity.BlockEntity> renderable = new ArrayList<>();
            List<net.minecraft.world.level.block.entity.BlockEntity> global = new ArrayList<>();
            var dispatcher = mc.getBlockEntityRenderDispatcher();
            for (var be : c.getBlockEntities().values()) {
                int by = be.getBlockPos().getY();
                if (by < o.getY() || by >= o.getY() + 16) continue;
                var renderer = dispatcher.getRenderer(be);
                if (renderer == null) continue;
                if (renderer.shouldRenderOffScreen(be)) global.add(be);
                else renderable.add(be);
            }
            long key = SectionPos.asLong(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4);
            int gen = TAKE_GEN.addTo(key, 1) + 1;
            takenOver++;
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos().set(o);
            if (sec == null || sec.hasOnlyAir()) {   // nothing opaque: resolved here (VisGraph's own fast path)
                applyTakeOver(rs, key, gen, new net.minecraft.client.renderer.chunk.VisGraph().resolve(), renderable, global);
                return true;
            }
            Object data = pcData.get(sec.getStates());
            if (storageAcc == null) {
                var comps = data.getClass().getRecordComponents();
                storageAcc = comps[1].getAccessor();
                paletteAcc = comps[2].getAccessor();
                storageAcc.setAccessible(true);
                paletteAcc.setAccessible(true);
            }
            var storage = (net.minecraft.util.BitStorage) storageAcc.invoke(data);
            var palette = (net.minecraft.world.level.chunk.Palette<?>) paletteAcc.invoke(data);
            if (palette instanceof net.minecraft.world.level.chunk.GlobalPalette) {   // a direct palette (rare): here, cell by cell
                var vis = new net.minecraft.client.renderer.chunk.VisGraph();
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            mp.set(o.getX() + x, o.getY() + y, o.getZ() + z);
                            if (sec.getBlockState(x, y, z).isSolidRender(level, mp)) vis.setOpaque(mp);
                        }
                applyTakeOver(rs, key, gen, vis.resolve(), renderable, global);
                return true;
            }
            // the opaque palette entries and a copy of the bit storage words: the cells and the flood fill on a worker
            int psz = palette.getSize(), bits = storage.getBits();
            boolean[] opaque = new boolean[psz];
            boolean any = false;
            for (int i = 0; i < psz; i++) any |= opaque[i] = ((BlockState) palette.valueFor(i)).isSolidRender(level, mp);
            if (!any) {
                applyTakeOver(rs, key, gen, new net.minecraft.client.renderer.chunk.VisGraph().resolve(), renderable, global);
                return true;
            }
            long[] raw = bits == 0 ? null : storage.getRaw().clone();
            net.minecraft.Util.backgroundExecutor().execute(() -> {
                long[] opq = OPQ_TL.get();
                java.util.Arrays.fill(opq, 0L);
                int vpl = bits == 0 ? 1 : 64 / bits, count = 0;
                long m1 = bits == 0 ? 0 : (1L << bits) - 1;
                for (int i = 0; i < 4096; i++) {   // the storage's index order (y << 4 | z) << 4 | x is VisGraph's index too
                    int idx = bits == 0 ? 0 : (int) ((raw[i / vpl] >>> ((i % vpl) * bits)) & m1);
                    if (idx < opaque.length && opaque[idx]) {
                        opq[i >> 6] |= 1L << i;
                        count++;
                    }
                }
                long[] copy = VERIFY_VIS ? opq.clone() : null;
                var mine = resolveVisibility(opq, count);
                if (VERIFY_VIS) {   // VisGraph's own answer for the same cells, all 36 face pairs compared
                    var vg = new net.minecraft.client.renderer.chunk.VisGraph();
                    BlockPos.MutableBlockPos wp = new BlockPos.MutableBlockPos();
                    for (int i = 0; i < 4096; i++) if ((copy[i >> 6] >>> i & 1L) != 0) vg.setOpaque(wp.set(i & 15, i >> 8, i >> 4 & 15));
                    var ref = vg.resolve();
                    boolean same = true;
                    for (Direction a : Direction.values()) for (Direction b2 : Direction.values()) same &= ref.visibilityBetween(a, b2) == mine.visibilityBetween(a, b2);
                    visChecks.incrementAndGet();
                    if (!same) visMismatches.incrementAndGet();
                }
                TAKE_DONE.add(new Object[] {rs, key, gen, mine, renderable, global});
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[sleep/voxel] stream: take-over failed for {}", o, e);
            return false;
        }
    }

    /** -Dlab.voxel.stream.verifyVis: every take-over's visibility also by VisGraph, mismatches counted */
    private static final boolean VERIFY_VIS = Boolean.getBoolean("lab.voxel.stream.verifyVis");
    private static final java.util.concurrent.atomic.AtomicLong visChecks = new java.util.concurrent.atomic.AtomicLong(), visMismatches = new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadLocal<long[]> OPQ_TL = ThreadLocal.withInitial(() -> new long[64]);
    private static final ThreadLocal<int[]> QUEUE_TL = ThreadLocal.withInitial(() -> new int[4096]);
    private static final Direction[] FACE_OF_BIT = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

    /**
     * VisGraph.resolve, the same answer without its allocations (a queue per region, grown): under 256 opaque cells all
     * faces see each other, all opaque none; otherwise a flood fill from every edge cell not yet reached, each region's
     * faces joined (a 6-bit mask per region, each mask added once - VisibilitySet.add is a union). opq: the opaque cells
     * by VisGraph's index (x | y << 8 | z << 4); it is used as the reached set.
     */
    static net.minecraft.client.renderer.chunk.VisibilitySet resolveVisibility(long[] opq, int opaqueCount) {
        var set = new net.minecraft.client.renderer.chunk.VisibilitySet();
        if (opaqueCount < 256) {
            set.setAll(true);
            return set;
        }
        if (opaqueCount == 4096) {
            set.setAll(false);
            return set;
        }
        int[] queue = QUEUE_TL.get();
        long seen = 0;   // the region masks met
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    if (x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15) continue;
                    int start = x | y << 8 | z << 4;
                    if ((opq[start >> 6] >>> start & 1L) != 0) continue;
                    opq[start >> 6] |= 1L << start;
                    int head = 0, tail = 0, mask = 0;
                    queue[tail++] = start;
                    while (head < tail) {
                        int i = queue[head++], cx = i & 15, cy = i >> 8 & 15, cz = i >> 4 & 15;
                        if (cx == 0) mask |= 1; else if (cx == 15) mask |= 2;
                        if (cy == 0) mask |= 4; else if (cy == 15) mask |= 8;
                        if (cz == 0) mask |= 16; else if (cz == 15) mask |= 32;
                        if (cy > 0) tail = visit(opq, queue, tail, i - 256);
                        if (cy < 15) tail = visit(opq, queue, tail, i + 256);
                        if (cz > 0) tail = visit(opq, queue, tail, i - 16);
                        if (cz < 15) tail = visit(opq, queue, tail, i + 16);
                        if (cx > 0) tail = visit(opq, queue, tail, i - 1);
                        if (cx < 15) tail = visit(opq, queue, tail, i + 1);
                    }
                    seen |= 1L << mask;
                }
        for (int m = 1; m < 64; m++) {
            if ((seen >>> m & 1L) == 0) continue;
            java.util.EnumSet<Direction> faces = java.util.EnumSet.noneOf(Direction.class);
            for (int b = 0; b < 6; b++) if ((m >> b & 1) != 0) faces.add(FACE_OF_BIT[b]);
            set.add(faces);
        }
        return set;
    }

    private static int visit(long[] opq, int[] queue, int tail, int j) {
        if ((opq[j >> 6] >>> j & 1L) == 0) {
            opq[j >> 6] |= 1L << j;
            queue[tail++] = j;
        }
        return tail;
    }

    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap TAKE_GEN = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> TAKE_DONE = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** a taken-over section's compiled state: set when still the newest for a render section still at that place */
    private static void applyTakeOver(net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk rs, long key, int gen,
            net.minecraft.client.renderer.chunk.VisibilitySet vis, List<net.minecraft.world.level.block.entity.BlockEntity> renderable,
            List<net.minecraft.world.level.block.entity.BlockEntity> global) {
        if (TAKE_GEN.get(key) != gen) return;
        BlockPos o = rs.getOrigin();   // vanilla moves render sections around as the camera moves
        if (SectionPos.asLong(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4) != key) return;
        try {
            var cs = new net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.CompiledChunk();
            csVisibility.set(cs, vis);
            @SuppressWarnings("unchecked")
            var list = (List<net.minecraft.world.level.block.entity.BlockEntity>) csRenderable.get(cs);
            list.addAll(renderable);
            updateGlobalM.invoke(rs, global);
            rs.compiled.set(cs);
            // 1.20.1: vanilla's compile task ends here too - the occlusion graph goes on from this chunk (without it the
            // search stays stopped at the chunk it saw uncompiled: at a low camera only the nearest few dozen were drawn)
            Minecraft.getInstance().levelRenderer.addRecentlyCompiledChunk(rs);
        } catch (ReflectiveOperationException e) {
            LOG.warn("[sleep/voxel] stream: take-over failed for {}", o, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void drainTakeOvers() {
        Object[] r;
        while ((r = TAKE_DONE.poll()) != null)
            applyTakeOver((net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk) r[0], (Long) r[1], (Integer) r[2],
                    (net.minecraft.client.renderer.chunk.VisibilitySet) r[3], (List<net.minecraft.world.level.block.entity.BlockEntity>) r[4],
                    (List<net.minecraft.world.level.block.entity.BlockEntity>) r[5]);
    }

    /** per section: where its tint colours are (start in the tint arena, colours per tint index used) */
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> TINTS = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final TreeMap<Integer, Integer> TINT_FREE = new TreeMap<>();
    private static int tintBuf, sectionTintBuf;

    // ---- buffer writes timed: a write into a buffer the GPU still reads may wait for it (implicit synchronisation) ----
    private static long fGl, cpuGl, glCalls, tintAllocFails;

    private static long tintUsed() {
        long free = 0;
        for (int v : TINT_FREE.values()) free += v;
        return TINT_CAPACITY - free;
    }

    private static void timedSub(int target, long off, IntBuffer b) {
        long t = System.nanoTime();
        GL15C.glBufferSubData(target, off, b);
        t = System.nanoTime() - t;
        fGl += t;
        cpuGl += t;
        glCalls++;
    }

    private static void timedSub(int target, long off, int[] b) {
        long t = System.nanoTime();
        GL15C.glBufferSubData(target, off, b);
        t = System.nanoTime() - t;
        fGl += t;
        cpuGl += t;
        glCalls++;
    }

    private static void timedGet(int target, long off, int[] b) {
        long t = System.nanoTime();
        GL15C.glGetBufferSubData(target, off, b);
        t = System.nanoTime() - t;
        fGl += t;
        cpuGl += t;
        glCalls++;
    }

    private static int allocTint(int n) {
        for (var e : TINT_FREE.entrySet())
            if (e.getValue() >= n) {
                int start = e.getKey(), len = e.getValue();
                TINT_FREE.remove(start);
                if (len > n) TINT_FREE.put(start + n, len - n);
                return start;
            }
        tintAllocFails++;
        return -1;
    }

    private static void releaseTint(int start, int n) {
        var lo = TINT_FREE.floorEntry(start);
        if (lo != null && lo.getKey() + lo.getValue() == start) {
            start = lo.getKey();
            n += lo.getValue();
            TINT_FREE.remove(lo.getKey());
        }
        Integer hi = TINT_FREE.get(start + n);
        if (hi != null) {
            TINT_FREE.remove(start + n);
            n += hi;
        }
        TINT_FREE.put(start, n);
    }

    private static long ChunkKey(int cx, int cz) {
        return net.minecraft.world.level.ChunkPos.asLong(cx, cz);
    }

    private static int tex3d(int internal) {
        int tex = GlTrack.tex(GL11C.glGenTextures());
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 9);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, tex);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        org.lwjgl.opengl.GL42C.glTexStorage3D(GL12C.GL_TEXTURE_3D, 1, internal, nx, ny, nx);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        if (org.lwjgl.opengl.GL44C.glGetError() == 0) org.lwjgl.opengl.GL44C.glClearTexImage(tex, 0, GL30C.GL_RED_INTEGER, internal == GL30C.GL_R16UI ? GL11C.GL_UNSIGNED_SHORT : GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return tex;
    }

    // ---- sections, the face buffer, batches ----

    private static final class Rec {
        long key;
        int slot, alloc = -1, size;
        int nonEmpty, transCount;   // the opaque buckets with faces (18 bits: layer * 6 + direction), the translucent faces
        int lod;                    // meshed as the far LOD: 0 full detail, 1 cells of 2 x 2 x 2, 2 cells of 4 x 4 x 4
        final int[] base = new int[BUCKETS], count = new int[BUCKETS];
    }

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Rec> RECS = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final TreeMap<Integer, Integer> FREE = new TreeMap<>();   // start -> length, faces
    private static int outBuf, slotBuf, cmdBuf, quadIndexBuf, vao, meshProg, drawSolid, drawCutout, drawTrans, sortProg, drawFlat;
    private static int vaoV, drawSolidV, drawCutoutV, drawFlatV;
    private static final int[] U_SOLID_V = new int[6], U_CUTOUT_V = new int[6], U_FLAT_V = new int[6];
    private static final int[] U_FLAT = new int[6];

    // ---- -Dlab.voxel.stream.drawBench: the terrain draw split - normal / flat fragment shader / depth only / no rasterising ----
    private static final boolean DRAW_BENCH = Boolean.getBoolean("lab.voxel.stream.drawBench");
    private static final String[] BENCH_MODES = {"normal", "flat fragment shader", "depth only", "rasterizer discard (fetch + vertex shader)",
            "16-byte vertices, fixed-function fetch, discard", "the same faces as one draw (contiguous, discard)",
            "8-byte vertices, fixed-function fetch, discard", "32-byte vertices, fixed-function fetch, discard",
            "16-byte face records pulled (vertex id / 4), discard",
            "compacted (index pass + one draw per layer; with .compact)", "compacted, discard (with .compact)",
            "compacted: the index pass only (with .compact)", "compacted, flat fragment shader, discard (with .compact)",
            "multi-draw, full programs, discard",
            "compacted, lean outputs (uv, colour, distance), discard (with .compact)",
            "compacted, lean, no tint lookup, discard (with .compact)", "compacted, lean, no lightmap fetch, discard (with .compact)"};
    /** bench modes 14-16: compact vertex stages with only the outputs the solid fragment stage reads, and ablations */
    private static final int[] LEAN_C = new int[3];
    private static final int[][] U_LEAN_C = new int[3][6];
    private static int benchNow = -1;
    private static final int BM = BENCH_MODES.length;
    private static final int[][] BENCH_Q = new int[BM][4];
    private static final boolean[][] BENCH_QUSED = new boolean[BM][4];
    /** the floor kinds: 16-, 8- and 32-byte vertices through the fixed-function fetch, 16-byte face records read in the vertex shader */
    private static final int[] FLOOR_BYTES = {16, 8, 32, 16};
    private static final int[] floorVao = new int[4], floorProg = new int[4], floorU = new int[4], floorBuf = new int[4];
    private static int floorFaces;
    private static final List<List<Long>> BENCH_T = new ArrayList<>();
    private static long benchFrame, benchFaces, lastDrawnFaces;
    private static boolean drawBenchDone;

    /** the draw bench's floor: as many quads as the normal draw from a static buffer - vertices of 16, 8 or 32 bytes read by
     *  the fixed-function fetch (kinds 0-2), or one 16-byte record per face read by the vertex shader at vertex id / 4 (kind 3) */
    private static void floorDraw(Matrix4f vp, int kind) {
        if (floorFaces == 0) floorFaces = (int) lastDrawnFaces;
        if (floorVao[kind] == 0) {
            String head = "#version 430\nuniform mat4 ViewProj;\nout vec2 uv;\nout vec4 col;\n";
            String vs = switch (kind) {
                case 0 -> head + """
                        layout(location = 0) in uvec4 a;
                        layout(location = 1) in uvec2 b;
                        layout(location = 2) in vec4 c;
                        void main() { uv = vec2(b) / 65535.0; col = c; gl_Position = ViewProj * vec4(vec3(a.xyz) / 2048.0, 1.0); }
                        """;
                case 1 -> head + """
                        layout(location = 0) in uvec4 a;
                        void main() { uv = vec2(a.w & 255u, a.w >> 8) / 255.0; col = vec4(uv, 0.0, 1.0); gl_Position = ViewProj * vec4(vec3(a.xyz) / 2048.0, 1.0); }
                        """;
                case 2 -> head + """
                        layout(location = 0) in uvec4 a;
                        layout(location = 1) in uvec2 b;
                        layout(location = 2) in vec4 c;
                        layout(location = 3) in uvec4 d;
                        void main() { uv = vec2(b) / 65535.0; col = c + vec4(d) * 1e-9; gl_Position = ViewProj * vec4(vec3(a.xyz) / 2048.0, 1.0); }
                        """;
                default -> head + """
                        layout(std430, binding = 11) buffer Faces { uvec4 rec[]; };
                        void main() {
                            uint v = uint(gl_VertexID), k = v & 3u;
                            uvec4 r = rec[v >> 2u];
                            vec3 p = vec3(r.x & 0xFFFFu, r.x >> 16, r.y & 0xFFFFu) / 2048.0 + vec3(k & 1u, 0.0, k >> 1);
                            uv = vec2(r.z & 0xFFFFu, r.z >> 16) / 65535.0 + vec2(k & 1u, k >> 1) / 64.0;
                            col = unpackUnorm4x8(r.w);
                            gl_Position = ViewProj * vec4(p, 1.0);
                        }
                        """;
            };
            floorProg[kind] = VoxelCompute.program(null, vs, """
                    #version 430
                    in vec2 uv;
                    in vec4 col;
                    out vec4 f;
                    void main() { f = col + vec4(uv, 0.0, 0.0); }
                    """);
            floorU[kind] = GL20C.glGetUniformLocation(floorProg[kind], "ViewProj");
            floorVao[kind] = GlTrack.vao(GL30C.glGenVertexArrays());
            GlStateManager._glBindVertexArray(floorVao[kind]);
            floorBuf[kind] = GlTrack.buf(GL15C.glGenBuffers());
            int stride = FLOOR_BYTES[kind], n = kind == 3 ? floorFaces : floorFaces * 4;
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, floorBuf[kind]);
            java.nio.ByteBuffer data = MemoryUtil.memCalloc(n * stride);
            for (int i = 0; i < n; i++) data.putShort(i * stride, (short) (i & 0x7FFF)).putShort(i * stride + 2, (short) (i >> 3 & 0x7FFF));
            GL44C.glBufferStorage(GL15C.GL_ARRAY_BUFFER, data, 0);
            MemoryUtil.memFree(data);
            if (kind != 3) {
                GL30C.glVertexAttribIPointer(0, 4, GL11C.GL_UNSIGNED_SHORT, stride, 0);
                if (kind != 1) {
                    GL30C.glVertexAttribIPointer(1, 2, GL11C.GL_UNSIGNED_SHORT, stride, 8);
                    GL20C.glVertexAttribPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, true, stride, 12);
                }
                if (kind == 2) GL30C.glVertexAttribIPointer(3, 4, GL11C.GL_UNSIGNED_INT, stride, 16);
                for (int k = 0; k < (kind == 1 ? 1 : kind == 2 ? 4 : 3); k++) GL20C.glEnableVertexAttribArray(k);
            }
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, quadIndexBuf);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        }
        GlStateManager._glBindVertexArray(floorVao[kind]);
        GlStateManager._glUseProgram(floorProg[kind]);
        if (kind == 3) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 11, floorBuf[kind]);
        GL20C.glUniformMatrix4fv(floorU[kind], false, vp.get(MAT));
        for (int f = 0; f < floorFaces; f += MAX_QUADS_PER_DRAW) {
            int take = Math.min(MAX_QUADS_PER_DRAW, floorFaces - f);
            GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, take * 6, GL11C.GL_UNSIGNED_INT, 0L, f * 4);
        }
        if (kind == 3) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 11, 0);
        GlStateManager._glBindVertexArray(vao);
    }

    private static int drawBenchMode() {
        if (!DRAW_BENCH || drawBenchDone || firstViewAt == 0 || System.nanoTime() - firstViewAt < 3_000_000_000L || !FLIGHTS.isEmpty()) return -1;
        return (int) (benchFrame % BM);
    }

    private static void drawBenchBegin(int m) {
        if (BENCH_Q[0][0] == 0) {
            for (int[] q : BENCH_Q) GlTrack.queries(q);
            for (int i = 0; i < BM; i++) BENCH_T.add(new ArrayList<>());
        }
        int slot = (int) (benchFrame / BM % 4);
        int q = BENCH_Q[m][slot];
        if (BENCH_QUSED[m][slot] && GL15C.glGetQueryObjecti(q, GL15C.GL_QUERY_RESULT_AVAILABLE) != 0)
            BENCH_T.get(m).add(GL33C.glGetQueryObjecti64(q, GL15C.GL_QUERY_RESULT));
        BENCH_QUSED[m][slot] = true;
        if (m == 2) GL11C.glColorMask(false, false, false, false);
        if (m >= 3 && m != 9) GL11C.glEnable(GL30C.GL_RASTERIZER_DISCARD);
        GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, q);
    }

    private static void drawBenchEnd(int m) {
        GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        if (m == 2) GL11C.glColorMask(true, true, true, true);
        if (m >= 3 && m != 9) GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD);
        benchFrame++;
        if (m == 0) benchFaces += lastDrawnFaces;
        if (benchFrame >= BM * 200L) {
            drawBenchDone = true;
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "[sleep/voxel] draw bench (GPU ms, median; %.0f faces drawn per frame):", benchFaces / 200.0));
            for (int i = 0; i < BM; i++) {
                List<Long> t = BENCH_T.get(i);
                java.util.Collections.sort(t);
                sb.append(String.format(Locale.ROOT, "%n  %-44s %.3f ms (%d samples)", BENCH_MODES[i], t.isEmpty() ? 0 : t.get(t.size() / 2) / 1e6, t.size()));
            }
            LOG.info(sb.toString());
        }
    }
    private static int sortListBuf, sortedBuf;
    private static long sortListCapacity, sortedCapacity;
    private static final int[] U_TRANS = new int[6];
    private static int uSortCamBlock, uSortCamFrac;
    private static long transFaces, transSections, transFrames, transUnsorted, transVanilla;
    private static final boolean CMD_RING = !"false".equals(System.getProperty("lab.voxel.stream.cmdRing"));   // default on

    // ---- -Dlab.voxel.stream.gpuCommands: the draw commands written by a compute pass from the visible slots ----
    private static final boolean GPU_CMDS = Boolean.getBoolean("lab.voxel.stream.gpuCommands");
    private static final int MAXC = Integer.getInteger("lab.voxel.stream.maxCommands", 24576);   // commands per layer (Intel walks all of them)
    private static int rangeBuf, gcmdBuf, gcountBuf, cmdGenProg, uCgN, uCgCamBlock, uCgCamFrac, uCgMaxC, uCgMaxQ, uCgVerts;
    private static final int[] VIS_BUFS = new int[3];
    private static final long[] VIS_CAPS = new long[3];
    private static int[] visSlots = new int[0];
    private static final int[] RANGE_ROW = new int[2 * BUCKETS];

    private static void setRanges(Rec r) {
        if (!GPU_CMDS) return;
        for (int q = 0; q < BUCKETS; q++) {
            RANGE_ROW[2 * q] = r.base[q];
            RANGE_ROW[2 * q + 1] = r.alloc < 0 ? 0 : r.count[q];
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, rangeBuf);
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 8L * BUCKETS * r.slot, RANGE_ROW);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void zeroRanges(int slot) {
        if (!GPU_CMDS) return;
        java.util.Arrays.fill(RANGE_ROW, 0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, rangeBuf);
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 8L * BUCKETS * slot, RANGE_ROW);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static final String CMDGEN = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 2) buffer Slots { ivec4 slots[]; };
            layout(std430, binding = 8) buffer Ranges { uvec2 ranges[]; };
            layout(std430, binding = 9) buffer Vis { uint vis[]; };
            layout(std430, binding = 10) buffer Cmds { uint cmds[]; };
            layout(std430, binding = 11) buffer Counts { uint counters[]; };
            uniform uint N;
            uniform ivec3 CamBlock;
            uniform vec3 CamFrac;
            uniform uint MaxC;
            uniform uint MaxQ;
            uniform uint Verts;
            // the camera above / below / ... a section's faces of one direction: the ChunkMultiDraw / CPU path's test
            bool above(int cb, float cf, int v) { return cb > v || (cb == v && cf > 0.0); }
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= N) return;
                uint slot = vis[i];
                ivec3 o = slots[slot].xyz;
                uint m = 0u;
                if (CamBlock.y < o.y + 16) m |= 1u;
                if (above(CamBlock.y, CamFrac.y, o.y)) m |= 2u;
                if (CamBlock.z < o.z + 16) m |= 4u;
                if (above(CamBlock.z, CamFrac.z, o.z)) m |= 8u;
                if (CamBlock.x < o.x + 16) m |= 16u;
                if (above(CamBlock.x, CamFrac.x, o.x)) m |= 32u;
                for (uint layer = 0u; layer < 3u; layer++)
                    for (uint dd = 0u; dd < 6u; dd++) {
                        if ((m >> dd & 1u) == 0u) continue;
                        uvec2 r = ranges[slot * 24u + layer * 6u + dd];
                        uint c = r.y, bs = r.x;
                        while (c > 0u) {
                            uint take = min(c, MaxQ);
                            uint k = atomicAdd(counters[layer], 1u);
                            if (k < MaxC) {
                                uint o5 = (layer * MaxC + k) * 5u;
                                cmds[o5] = take * 6u;
                                cmds[o5 + 1u] = 1u;
                                cmds[o5 + 2u] = 0u;
                                cmds[o5 + 3u] = bs * 4u;
                                cmds[o5 + 4u] = Verts != 0u ? slot : 0u;
                            }
                            bs += take;
                            c -= take;
                        }
                    }
            }
            """;

    private static void gpuCommandsInit() {
        int slots = chunks * chunks * secY;
        rangeBuf = dynamicBuffer(8L * BUCKETS * slots, null);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, rangeBuf);
        org.lwjgl.opengl.GL43C.glClearBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, (int[]) null);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        gcmdBuf = dynamicBuffer(20L * MAXC * 3, null);
        gcountBuf = dynamicBuffer(16, null);
        cmdGenProg = VoxelCompute.program(CMDGEN, null, null);
        if (cmdGenProg == 0) throw new IllegalStateException("command generation shader failed (see the log)");
        uCgN = GL20C.glGetUniformLocation(cmdGenProg, "N");
        uCgCamBlock = GL20C.glGetUniformLocation(cmdGenProg, "CamBlock");
        uCgCamFrac = GL20C.glGetUniformLocation(cmdGenProg, "CamFrac");
        uCgMaxC = GL20C.glGetUniformLocation(cmdGenProg, "MaxC");
        uCgMaxQ = GL20C.glGetUniformLocation(cmdGenProg, "MaxQ");
        uCgVerts = GL20C.glGetUniformLocation(cmdGenProg, "Verts");
    }

    /** the visible slots up (a ring of three buffers), the counters cleared on the GPU, the commands written by the compute pass */
    private static void gpuCommands(java.util.List<net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk> visible,
            int bx, int by, int bz, net.minecraft.world.phys.Vec3 cam) {
        int sz = visible.size(), vn = 0;
        if (visSlots.length < sz) visSlots = new int[sz + sz / 2 + 256];
        for (int i = 0; i < sz; i++) {
            var o = visible.get(i).getOrigin();
            visSlots[vn++] = slotOf(SectionPos.asLong(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4));
        }
        int ri = (int) (drawFrames % 3);
        if (4L * Math.max(vn, 1) > VIS_CAPS[ri]) {
            if (VIS_BUFS[ri] != 0) GlTrack.delBuf(VIS_BUFS[ri]);
            VIS_CAPS[ri] = 4L * (vn + vn / 2 + 256);
            VIS_BUFS[ri] = dynamicBuffer(VIS_CAPS[ri], null);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, VIS_BUFS[ri]);
        if (vn > 0) {
            IntBuffer vb = MemoryUtil.memAllocInt(vn);
            vb.put(visSlots, 0, vn).flip();
            timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, vb);
            MemoryUtil.memFree(vb);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, gcountBuf);
        org.lwjgl.opengl.GL43C.glClearBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, (int[]) null);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        int[][] binds = {{2, slotBuf}, {8, rangeBuf}, {9, VIS_BUFS[ri]}, {10, gcmdBuf}, {11, gcountBuf}};
        for (int[] bb : binds) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bb[0], bb[1]);
        GlStateManager._glUseProgram(cmdGenProg);
        GL30C.glUniform1ui(uCgN, vn);
        GL20C.glUniform3i(uCgCamBlock, bx, by, bz);
        GL20C.glUniform3f(uCgCamFrac, (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
        GL30C.glUniform1ui(uCgMaxC, MAXC);
        GL30C.glUniform1ui(uCgMaxQ, MAX_QUADS_PER_DRAW);
        GL30C.glUniform1ui(uCgVerts, VERTS ? 1 : 0);
        GL43C.glDispatchCompute((vn + 63) / 64, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | org.lwjgl.opengl.GL42C.GL_COMMAND_BARRIER_BIT);
        for (int[] bb : binds) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bb[0], 0);
    }
    private static final int[] CMD_BUFS = new int[3];
    private static final long[] CMD_CAPS = new long[3];
    private static int samplerMip, samplerFlat;
    private static int uDims, uStates, uMinY, uPass;
    private static final int[] U_SOLID = new int[6], U_CUTOUT = new int[6];

    private static int allocate(int n) {
        if (n == 0) return -1;
        for (var e : FREE.entrySet())
            if (e.getValue() >= n) {
                int start = e.getKey(), len = e.getValue();
                FREE.remove(start);
                if (len > n) FREE.put(start + n, len - n);
                return start;
            }
        return -2;   // full
    }

    private static void release(int start, int n) {
        if (start < 0 || n == 0) return;
        var lo = FREE.floorEntry(start);
        if (lo != null && lo.getKey() + lo.getValue() == start) {
            start = lo.getKey();
            n += lo.getValue();
            FREE.remove(lo.getKey());
        }
        Integer hi = FREE.get(start + n);
        if (hi != null) {
            FREE.remove(start + n);
            n += hi;
        }
        FREE.put(start, n);
    }

    private static int slotOf(long key) {
        int cx = imod(SectionPos.x(key), chunks), cz = imod(SectionPos.z(key), chunks);
        int si = SectionPos.y(key) - minSec;
        return (cx * chunks + cz) * secY + si;
    }

    private static Rec[] SLOT_REC = new Rec[0];
    private static int minSec;

    /** a section's record by its slot (an array, not a hash lookup) - this frame's lookups go through here */
    private static Rec recAt(int sx, int sy, int sz) {
        int si = sy - minSec;
        if (si < 0 || si >= secY) return null;
        int s = (imod(sx, chunks) * chunks + imod(sz, chunks)) * secY + si;
        Rec r = SLOT_REC[s];
        return r != null && r.key == SectionPos.asLong(sx, sy, sz) ? r : null;
    }

    /** a batch in flight: counted (fence 1), then written (fence 2), then published */
    private static final class Batch {
        long[] keys;
        int n, phase, slotIdx;
        long fence;
        int[] counts, allocs, bases;
        int[] lod;
    }

    private static final java.util.ArrayDeque<Batch> FLIGHTS = new java.util.ArrayDeque<>();
    private static int[] countsBufs, basesBufs, sectionBufs;
    private static final boolean[] SLOT_BUSY = new boolean[16];
    private static long meshed, batches, uploads, fullStalls, waitFrames;
    private static long firstViewAt;

    // ---- per frame ----

    /** -Dlab.voxel.stream.early: started at the join (ClientAuto), so the measured window sees a running stream, not its start */
    public static final boolean EARLY = !"false".equals(System.getProperty("lab.voxel.stream.early"));   // default on
    private static boolean started;

    // ---- lifecycle: a player's game, not only ClientAuto's runs ----

    private static Object lifeLevel, lifeModels;
    private static int lifeDistance, lifeHeight, lifeMinY;
    public static int restarts;

    /**
     * Every frame with a level (afterTerrain): started for a level; stopped and started again when the level object,
     * the view distance, the level's height or the baked models (a resource reload: the library holds their quads)
     * change. Leaving a world frees everything at the next level's first frame (or stays held in the menu).
     */
    private static void lifecycle() {
        if (!ON) return;
        if (ASIDE != null) {
            if (!asideLogged) LOG.info("[sleep/voxel] the GPU terrain stands aside: {}; vanilla's (or that mod's) terrain is drawn", ASIDE);
            asideLogged = true;
            return;
        }
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return;
        Object models = mc.getModelManager().getMissingModel();
        int dist = mc.options.getEffectiveRenderDistance();
        if (started && (level != lifeLevel || models != lifeModels || dist != lifeDistance || level.getHeight() != lifeHeight
                || level.getMinBuildHeight() != lifeMinY)) {
            stop();
            restarts++;
        }
        if (!started) {
            lifeLevel = level;
            lifeModels = models;
            lifeDistance = dist;
            lifeHeight = level.getHeight();
            lifeMinY = level.getMinBuildHeight();
            start();
        }
    }

    /**
     * Why the GPU terrain does not start, or null. It cuts vanilla's opaque draws in chunk multi-draw's renderChunkLayer and takes
     * over the section compile (rebuildChunkAsync, compileSync): when multi-draw is off, or another mod's mixin changes one of them (a
     * replaced chunk renderer - Embeddium, Sodium and the shader mods on them), both would draw or the GPU's sections
     * would never be handed over. Decided once from the mixins (MixinTouch), as multi-draw's own give-way.
     */
    static final String ASIDE = aside();
    private static boolean asideLogged;

    private static String aside() {
        if (!ON) return null;
        if (!(Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ChunkMultiDrawMixin")))
            return "chunk multi-draw is off (the GPU terrain cuts vanilla's opaque draws there)";
        if (!ChunkMultiDraw.ACTIVE) return "another mod changes LevelRenderer.renderChunkLayer (its own chunk renderer)";
        for (String m : new String[] {"rebuildChunkAsync", "compileSync"})
            if (MixinTouch.touched("net/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk", m)) return "another mod changes the section compile (" + m + ")";
        return null;
    }

    /** everything the stream holds on the GPU freed, vanilla's drawing back; a start builds it all again */
    public static void stop() {
        if (!started) return;
        active = ready = started = false;
        VoxelLod.cutAll = VoxelLod.cutting = false;
        int freed = GlTrack.freeAll(VoxelStream.class, VoxelCompute.class, VoxelLod.class);
        VANILLA_OWNED.clear();
        RECS.clear();
        DIRTY.clear();
        for (Batch b : FLIGHTS) if (b.fence != 0) org.lwjgl.opengl.GL32C.glDeleteSync(b.fence);   // batches in flight
        java.util.Arrays.fill(TQ_USED, false);   // the GPU timers' slots: their queries are new names now
        // buffers sized by capacity: their names are 0 now, the capacities must say so (else no new buffer is made)
        java.util.Arrays.fill(BAKE_CAPS, 0);
        java.util.Arrays.fill(VIS_CAPS, 0);
        java.util.Arrays.fill(CC_CAPS, 0);
        occSlotCap = occCmdCap = 0;
        java.util.Arrays.fill(LIB_CAP, 0);
        java.util.Arrays.fill(LIB_SENT, 0);
        FLIGHTS.clear();
        ChunkMultiDraw.invalidateLayerCaches();
        LOG.info("[sleep/voxel] stream stopped: {} GL objects freed", freed);
        var lr = Minecraft.getInstance().levelRenderer;
        if (Minecraft.getInstance().level != null) lr.allChanged();   // vanilla meshes the sections the GPU had taken over
    }

    /** LevelRenderer's visible chunks this frame, as render chunks (a view over its RenderChunkInfo list, no copy) */
    static java.util.List<net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk> visibleChunks(Minecraft mc) {
        var infos = ((dev.lull.opt.VoxelFrustumAccessor) mc.levelRenderer).sleep$frustum();
        return new java.util.AbstractList<>() {
            @Override
            public net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk get(int i) {
                return ChunkMultiDraw.chunkOf(infos.get(i));
            }

            @Override
            public int size() {
                return infos.size();
            }
        };
    }

    /** the measured window begins: with an early start only the report's counters are cleared */
    public static void window() {
        if (!started) {
            start();
            return;
        }
        windowStart = System.nanoTime();
        cpuMoved = cpuProgress = cpuBegin = cpuDraw = cpuFrames = gpuMesh = gpuDraw = gpuFrames = cpuUploads = cpuTints = 0;
        gpuBusyMesh = gpuBusyUnpack = gpuBusyDraw = 0;
        cpuGl = glCalls = tintAllocFails = tintsKept = 0;
        takenOver = leftToVanilla = cpuTakeOver = cpuTrans = cpuAddState = addedStates = cpuLibUpload = libUploads = addMark = libMark = 0;
        pointWrites = pointFallbacks = lightUploads = 0;
        transFaces = transSections = transFrames = transUnsorted = transVanilla = transResorts = 0;
        meshed = batches = uploads = waitFrames = fullStalls = 0;
        drawnFaces = drawFrames = 0;
        lastAfter = 0;
        spikesLogged = 0;
        firstViewAt = DIRTY.isEmpty() && FLIGHTS.isEmpty() ? windowStart : 0;
    }

    public static void start() {
        if (started) return;   // the lifecycle started it already (ClientAuto calls this too)
        windowStart = System.nanoTime();
        active = true;
        started = true;
        try {
            setup();
            ready = true;
        } catch (RuntimeException | ReflectiveOperationException e) {
            LOG.error("[sleep/voxel] stream setup failed", e);
        }
    }

    /**
     * -Dlab.voxel.stream.skipDark: while the camera is under the sky (sky light > 0 at its block), sections with no sky light
     * at all (caves and rock not joined to the sky: 56 % of the meshed faces in the N100 flight) farther than one chunk
     * are not meshed and their faces are freed; when the camera goes dark they are all meshed again, a light change in
     * one brings it back. Pictures differ only where a dark cave is seen from the sky's side (a long straight shaft).
     */
    static final boolean SKIP_DARK = on("lab.voxel.stream.skipDark");
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet SKIPPED = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    public static long darkSkips, darkRestores;

    /** no sky light anywhere in the section (a light layer that is all zeros) */
    private static boolean noSky(net.minecraft.world.level.lighting.LayerLightEventListener sky, long k) {
        var layer = sky.getDataLayerData(SectionPos.of(k));
        if (layer == null) return false;
        if (layer.isEmpty()) return true;
        for (byte v : layer.getData()) if (v != 0) return false;
        return true;
    }

    /** dark around: no sky light in the section nor in its six neighbours - a section's faces are lit from the cells in front
     *  of them, often in the next section (a solid section under the surface has no sky light of its own, yet its top shows) */
    private static boolean noSkyAround(net.minecraft.world.level.lighting.LayerLightEventListener sky, long k) {
        if (!noSky(sky, k) || mayHoldFluid(k)) return false;
        for (Direction d : Direction.values()) {
            long n = SectionPos.offset(k, d.getStepX(), d.getStepY(), d.getStepZ());
            if (!noSky(sky, n) || mayHoldFluid(n)) return false;
        }
        return true;
    }

    /** a fluid in the section's palette (or the section unknown): light fades in water while sight goes through it - a deep
     *  lake's floor has no sky light yet shows, so a section next to any fluid is never taken for dark */
    private static boolean mayHoldFluid(long k) {
        var level = Minecraft.getInstance().level;
        int si = SectionPos.y(k) - level.getMinSection();
        if (si < 0 || si >= level.getSectionsCount()) return false;
        LevelChunk c = level.getChunkSource().getChunk(SectionPos.x(k), SectionPos.z(k), false);
        if (c == null) return true;
        LevelChunkSection sec = c.getSections()[si];
        return !sec.hasOnlyAir() && sec.getStates().maybeHas(st -> !st.getFluidState().isEmpty());
    }

    private static void dropRec(long k) {
        Rec r = RECS.remove(k);
        if (r != null) {
            release(r.alloc, r.size);
            zeroRanges(r.slot);
            if (SLOT_REC[r.slot] == r) SLOT_REC[r.slot] = null;
        }
    }

    /** LevelRenderer.setSectionDirty: vanilla would mesh this section again (blocks, light, a chunk loaded) */
    public static void dirty(int x, int y, int z) {
        if (!active || !ready) return;
        long key = SectionPos.asLong(x, y, z);
        if (SKIP_DARK) SKIPPED.remove(key);   // looked at again (it may have light now)
        if (cause == CAUSE_POINT) {}   // the grid already holds the change: the sections around only meshed again
        else if (cause == CAUSE_LIGHT) {
            if (!STALE.contains(key)) STALE_LIGHT.add(key);
        } else STALE.add(key);
        if (within(x, z, d)) DIRTY.add(key);
    }

    // ---- point updates (plan point 2): a changed block written into its cell, a light change uploads the light only ----

    private static final int CAUSE_NONE = 0, CAUSE_POINT = 1, CAUSE_LIGHT = 2;
    private static int cause;
    /** sections whose blocks on the GPU are current but whose light changed */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet STALE_LIGHT = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static long pointWrites, pointFallbacks, lightUploads;
    private static final short[] ONE = new short[1];

    /**
     * LevelRenderer.blockChanged / setBlockDirty(pos, old, new): when the section's blocks on the GPU are current and
     * neither state is tinted (the tints are per section), the new state goes into its one cell; the sections vanilla
     * marks dirty around it are then meshed again without an upload. Otherwise the usual whole-section upload.
     */
    public static void pointBegin(BlockPos pos, BlockState old, BlockState now) {
        cause = CAUSE_NONE;
        if (!active || !ready || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return;
        var level = Minecraft.getInstance().level;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4, si = (pos.getY() >> 4) - level.getMinSection();
        long key = SectionPos.asLong(cx, pos.getY() >> 4, cz);
        if (si < 0 || si >= secY || !RESIDENT.contains(ChunkKey(cx, cz)) || STALE.contains(key)) return;
        int lo = localOf(old), ln = localOf(now);
        if ((!now.isAir() && ln == 0) || ((LIB.get(lo)[3] | LIB.get(ln)[3]) & (15 | 64)) != 0) {
            pointFallbacks++;
            return;
        }
        if (libDirty) uploadLibrary();
        ONE[0] = (short) ln;
        org.lwjgl.opengl.GL44C.glClearTexSubImage(gridTex, 0, imod(pos.getX(), nx), pos.getY() - level.getMinBuildHeight(), imod(pos.getZ(), nx), 1, 1, 1,
                GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_SHORT, ONE);
        pointWrites++;
        cause = CAUSE_POINT;
    }

    /** ClientChunkCache.onLightUpdate: the section vanilla marks gets its light uploaded, its blocks stay */
    public static void lightBegin() {
        cause = active && ready ? CAUSE_LIGHT : CAUSE_NONE;
    }

    public static void causeEnd() {
        cause = CAUSE_NONE;
    }

    private static void uploadLight(long key) {
        var level = Minecraft.getInstance().level;
        int cx = SectionPos.x(key), sy = SectionPos.y(key), cz = SectionPos.z(key);
        int si = sy - level.getMinSection();
        if (si < 0 || si >= secY) return;
        LevelChunk c = RESIDENT.contains(ChunkKey(cx, cz)) ? level.getChunkSource().getChunk(cx, cz, false) : null;
        if (c == null) return;
        long tu = System.nanoTime();
        try {
            packet(key, c, null, cx, sy, cz, imod(cx * 16, nx), si * 16, imod(cz * 16, nx), true);
        } catch (ReflectiveOperationException e) {
            LOG.error("[sleep/voxel] stream: section read failed", e);
        }
        cpuUploads += System.nanoTime() - tu;
        fRead += System.nanoTime() - tu;
        lightUploads++;
        framesUploads++;
    }

    /** the section is in the GPU's area (its grid): beyond it vanilla's sections are drawn (1.20.1 lists the view's edge ring too) */
    public static boolean meshes(BlockPos origin) {
        return within(origin.getX() >> 4, origin.getZ() >> 4, d);
    }

    private static boolean within(int cx, int cz, int r) {
        return Math.abs(cx - camCx) <= r && Math.abs(cz - camCz) <= r;
    }

    /** renderLevel after the terrain layers: residency, the batch in flight, a new batch, the draw */
    private static int fogLogs;

    public static void afterTerrain(Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        lifecycle();
        if (!active || !ready) return;
        if (Boolean.getBoolean("lab.voxel.stream.fogLog") && fogLogs % 1200 == 1199) {
            LOG.info("[sleep/voxel] report: {}", report());
            var vis = visibleChunks(Minecraft.getInstance());
            int withRec = 0, noRec = 0, uncompiled = 0;
            for (var rc : vis) {
                var o = rc.getOrigin();
                if (recAt(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4) != null) withRec++;
                else noRec++;
                if (rc.compiled.get() == net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.CompiledChunk.UNCOMPILED) uncompiled++;
            }
            LOG.info("[sleep/voxel] visible {}: with a GPU record {}, without {}, uncompiled {}; RECS {}, d {}", vis.size(), withRec, noRec, uncompiled, RECS.size(), d);
        }
        if (Boolean.getBoolean("lab.voxel.stream.fogLog") && fogLogs++ % 600 == 0)
            LOG.info("[sleep/voxel] fog at the terrain draw: start {} end {} shape {}, render distance {} blocks", RenderSystem.getShaderFogStart(),
                    RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogShape(), Minecraft.getInstance().gameRenderer.getRenderDistance());
        var mc = Minecraft.getInstance();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        int ccx = net.minecraft.util.Mth.floor(cam.x) >> 4, ccz = net.minecraft.util.Mth.floor(cam.z) >> 4;
        long t0 = System.nanoTime();
        spikeCheck(t0);
        if (LOD_CONE > 0) {
            double yaw = Math.toRadians(mc.gameRenderer.getMainCamera().getYRot());
            coneLx = -Math.sin(yaw);
            coneLz = Math.cos(yaw);
        }
        if (ccx != camCx || ccz != camCz) moved(ccx, ccz);
        else if (LOD_CONE > 0 && coneLx * coneDoneX + coneLz * coneDoneZ < CONE_TURN) relod(ccx, ccz);
        long t1 = System.nanoTime();
        framesUploads = 0;
        drainTints();
        drainTakeOvers();
        gpuMark(0);
        progress();
        long t2 = System.nanoTime();
        if (FLIGHTS.size() < FLIGHT && !DIRTY.isEmpty()) begin();
        flushUnpack();
        long t3 = System.nanoTime();
        gpuMark(1);
        if (firstViewAt == 0 && DIRTY.isEmpty() && FLIGHTS.isEmpty() && meshed > 0) firstViewAt = System.nanoTime();
        draw(frustumMatrix, projectionMatrix);
        gpuMark(2);
        if (MESH_BENCH && !benchDone && firstViewAt != 0 && System.nanoTime() - firstViewAt > 3_000_000_000L && FLIGHTS.isEmpty()) meshBench();
        if (VERTEX_STUDY && VERTS && !studyDone && firstViewAt != 0 && System.nanoTime() - firstViewAt > 3_000_000_000L && FLIGHTS.isEmpty()) vertexStudy(frustumMatrix, projectionMatrix);
        long t4 = System.nanoTime();
        cpuMoved += t1 - t0;
        cpuProgress += t2 - t1;
        cpuBegin += t3 - t2;
        flushTintJobs();
        cpuDraw += t4 - t3;
        cpuFrames++;
        lastParts[0] = t1 - t0;
        lastParts[1] = t2 - t1;
        lastParts[2] = t3 - t2;
        lastParts[3] = t4 - t3;
        lastF[0] = fBegin;
        lastF[1] = fPick;
        lastF[2] = fRead;
        lastF[3] = fFlush;
        lastF[4] = fDispatch;
        lastF[5] = fDrawBuild;
        lastF[6] = fDrawUp;
        lastF[7] = fDrawState;
        lastF[8] = fDrawSubmit;
        fDrawBuild = fDrawUp = fDrawState = fDrawSubmit = 0;
        lastGl = fGl;
        fBegin = fPick = fRead = fFlush = fDispatch = fGl = 0;
        gpuCollect();
    }

    // ---- spikes: a frame interval over 50 ms logged with what of ours ran in it ----

    private static long lastAfter, lastGcMs, takeOverMark, takeOverCountMark, transMark, spikesLogged, cpuTrans;
    private static final long[] lastParts = new long[4];
    private static long fBegin, fPick, fRead, fFlush, fDispatch, fDrawBuild, fDrawUp, fDrawState, fDrawSubmit;
    private static final long[] lastF = new long[9];
    private static long lastGl;

    private static long gcMillis() {
        long t = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, gc.getCollectionTime());
        return t;
    }

    private static void spikeCheck(long now) {
        long gc = gcMillis();
        if (lastAfter != 0 && now - lastAfter > 50_000_000L && spikesLogged < 40) {
            spikesLogged++;
            LOG.info(String.format(Locale.ROOT,
                    "[sleep/voxel] spike: %.1f ms between frames; in it: take-over %.1f ms (%d sections), translucent %.1f ms, GC %d ms, new library states %.1f ms, library uploads %.1f ms; the frame before: moved %.1f, batches %.1f, picking + uploads %.1f (begin %.1f: picking %.1f, section reads %.1f, packet upload + unpack %.1f, mesh dispatches %.1f), draw %.1f (build %.1f, upload %.1f, state %.1f, submit %.1f) ms; buffer writes / reads in that frame %.1f ms",
                    (now - lastAfter) / 1e6, (cpuTakeOver - takeOverMark) / 1e6, takenOver + leftToVanilla - takeOverCountMark, (cpuTrans - transMark) / 1e6, gc - lastGcMs,
                    (cpuAddState - addMark) / 1e6, (cpuLibUpload - libMark) / 1e6,
                    lastParts[0] / 1e6, lastParts[1] / 1e6, lastParts[2] / 1e6, lastF[0] / 1e6, lastF[1] / 1e6, lastF[2] / 1e6, lastF[3] / 1e6, lastF[4] / 1e6, lastParts[3] / 1e6, lastF[5] / 1e6, lastF[6] / 1e6, lastF[7] / 1e6, lastF[8] / 1e6, lastGl / 1e6));
        }
        lastAfter = now;
        lastGcMs = gc;
        takeOverMark = cpuTakeOver;
        takeOverCountMark = takenOver + leftToVanilla;
        transMark = cpuTrans;
        addMark = cpuAddState;
        libMark = cpuLibUpload;
    }

    /** every section's LOD level decided again: to a finer level at once, to a coarser one a chunk later (no flapping on the edge) */
    private static void relod(int ccx, int ccz) {
        coneDoneX = coneLx;
        coneDoneZ = coneLz;
        for (var e : RECS.long2ObjectEntrySet()) {
            Rec r = e.getValue();
            long k = e.getLongKey();
            // off screen: never finer (it waits until the view turns to it); coarser only as the plain distance's level asks
            // (walking away - the face buffer is sized for that), never by the cone
            boolean back = LOD_CONE > 0 && !CONE_BEHIND && behind(k, ccx, ccz);
            if ((!back && r.lod > lodFor(k, ccx, ccz, 0)) || r.lod < lodFor(k, ccx, ccz, 1)) {
                if (DIRTY.add(k)) {
                    lodRemesh++;
                    if (LOD_CONE > 0) coneRemesh++;
                }
            }
        }
    }

    /**
     * -Dlab.voxel.stream.lodCone=K (with .lod): the level from an effective distance - the chunk distance times
     * 1 + K * (1 - cos a) / 2, a the horizontal angle between the look and the section - so sections toward the screen's
     * edges and behind go coarse sooner; the nearest 2 chunks keep full detail. A turn of more than CONE_TURN re-decides
     * the levels (the user, 11-10: a soft reveal of detail beats hitches; the full picture follows in a moment).
     */
    static final float LOD_CONE = Float.parseFloat(System.getProperty("lab.voxel.stream.lodCone", Boolean.getBoolean("lab.voxel.stream.cone") ? "2" : "0"));
    private static final double CONE_TURN = Math.cos(Math.toRadians(15));
    /** -Dlab.voxel.stream.coneBehind: the old rule - sections behind the camera coarse too (an A/B of the reveal) */
    private static final boolean CONE_BEHIND = Boolean.getBoolean("lab.voxel.stream.coneBehind");
    private static double coneLx, coneLz = 1, coneDoneX, coneDoneZ = 1;
    public static long coneRemesh;

    /**
     * the queue's order with the cone: the distance stretched as the level's (sections in view first), and a section
     * already meshed that only goes coarser (behind, after a turn) four times further back - the turn's reveal first
     */
    private static long conePriority(long k, long dist, int ccx, int ccz) {
        int dx = SectionPos.x(k) - ccx, dz = SectionPos.z(k) - ccz;
        if (dx != 0 || dz != 0) {
            double cos = (dx * coneLx + dz * coneLz) / Math.sqrt((double) dx * dx + (double) dz * dz);
            double f = 1 + LOD_CONE * (1 - cos) / 2;
            dist = (long) (dist * f * f);
        }
        Rec r = RECS.get(k);
        if (r != null && r.lod < lodFor(k, ccx, ccz, 0)) dist *= 4;
        return dist;
    }

    /** more than 90 degrees off the look and past the 2 full-detail chunks (the cone leaves it alone) */
    private static boolean behind(long k, int ccx, int ccz) {
        int dx = SectionPos.x(k) - ccx, dz = SectionPos.z(k) - ccz;
        return Math.max(Math.abs(dx), Math.abs(dz)) > 2 && dx * coneLx + dz * coneLz < 0;
    }

    /** a section's level; slack 1 = the coarser bound one chunk nearer (the hysteresis) */
    static int lodFor(long k, int ccx, int ccz, int slack) {
        int dx = SectionPos.x(k) - ccx, dz = SectionPos.z(k) - ccz;
        int dd = Math.max(Math.abs(dx), Math.abs(dz)) - slack;
        if (LOD_CONE <= 0 || dd <= 2) return lodLevel(dd);
        double cos = (dx * coneLx + dz * coneLz) / Math.sqrt((double) dx * dx + (double) dz * dz);
        // more than 90 degrees off the look (never on screen: half the horizontal view is under 70 degrees even at
        // FOV 110): the plain distance's level - nothing re-meshed coarser behind, a turn finds it fine already
        if (cos < 0 && !CONE_BEHIND) return lodLevel(dd);
        return lodLevel((int) Math.floor(dd * (1 + LOD_CONE * (1 - cos) / 2)));
    }

    /** the camera entered another chunk: chunks within dm become resident (uploaded), others leave (cleared, freed) */
    private static void moved(int ccx, int ccz) {
        var level = Minecraft.getInstance().level;
        int oldx = camCx, oldz = camCz;
        camCx = ccx;
        camCz = ccz;
        List<Long> leave = new ArrayList<>();
        for (long k : RESIDENT) {
            int x = net.minecraft.world.level.ChunkPos.getX(k), z = net.minecraft.world.level.ChunkPos.getZ(k);
            if (!within(x, z, dm)) leave.add(k);
        }
        for (long k : leave) {
            RESIDENT.remove(k);
            int x = net.minecraft.world.level.ChunkPos.getX(k), z = net.minecraft.world.level.ChunkPos.getZ(k);
            for (int s = 0; s < secY; s++) {
                long key = SectionPos.asLong(x, level.getMinSection() + s, z);
                Rec r = RECS.remove(key);
                if (r != null) {
                    release(r.alloc, r.size);
                    zeroRanges(r.slot);
                    if (SLOT_REC[r.slot] == r) SLOT_REC[r.slot] = null;
                }
                int[] t = TINTS.remove(key);
                if (t != null) releaseTint(t[0], t[1]);
                DIRTY.remove(key);
                STALE_LIGHT.remove(key);
                STALE.remove(key);   // no clearing upload: the slot's next chunk is uploaded before anything reads it
            }
        }
        if (LOD > 0) relod(ccx, ccz);
        // sections beyond the render distance are not drawn: their faces go back to the buffer (meshed again on return)
        for (var it = RECS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            long key = e.getKey();
            if (!within(SectionPos.x(key), SectionPos.z(key), d)) {
                release(e.getValue().alloc, e.getValue().size);
                zeroRanges(e.getValue().slot);
                if (SLOT_REC[e.getValue().slot] == e.getValue()) SLOT_REC[e.getValue().slot] = null;
                it.remove();
            }
        }
        for (int x = ccx - dm; x <= ccx + dm; x++)
            for (int z = ccz - dm; z <= ccz + dm; z++) {
                long ck = ChunkKey(x, z);
                boolean wasIn = oldx != Integer.MIN_VALUE && Math.abs(x - oldx) <= d && Math.abs(z - oldz) <= d;
                if (!RESIDENT.contains(ck) && level.getChunkSource().getChunk(x, z, false) != null) {
                    RESIDENT.add(ck);
                    for (int s = 0; s < secY; s++) STALE.add(SectionPos.asLong(x, level.getMinSection() + s, z));
                }
                if (RESIDENT.contains(ck) && within(x, z, d) && !wasIn)   // entered the meshed area
                    for (int s = 0; s < secY; s++) {
                        long key = SectionPos.asLong(x, level.getMinSection() + s, z);
                        if (!RECS.containsKey(key)) DIRTY.add(key);
                    }
            }
    }

    /** the nearest dirty sections (empty ones dropped at once): their and their neighbours' stale data uploaded, then counted */
    private static void begin() {
        long tb = System.nanoTime();
        try {
            begin0();
        } finally {
            fBegin += System.nanoTime() - tb;
        }
    }

    private static void begin0() {
        long tp = System.nanoTime();
        var level = Minecraft.getInstance().level;
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        // the BUDGET nearest, in one pass: a bounded max-heap on the distance in two arrays; the chunk / section checks only
        // for keys that would get in (the others are checked when their turn comes)
        if (heapK.length < BUDGET) {
            heapK = new long[BUDGET];
            heapD = new long[BUDGET];
        }
        long[] hk = heapK, hd = heapD;
        int hn = 0;
        long camX = (long) cam.x, camY = (long) cam.y, camZ = (long) cam.z;
        var skyLight = level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        boolean camLit = SKIP_DARK && level.getBrightness(net.minecraft.world.level.LightLayer.SKY, BlockPos.containing(cam)) > 0;
        if (SKIP_DARK && !camLit && !SKIPPED.isEmpty()) {   // under ground: everything skipped meshed again
            darkRestores += SKIPPED.size();
            DIRTY.addAll(SKIPPED);
            SKIPPED.clear();
        }
        int camCxs = (int) Math.floor(cam.x / 16), camCzs = (int) Math.floor(cam.z / 16);
        for (var it = DIRTY.iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            int cx = SectionPos.x(k), cz = SectionPos.z(k);
            if (!within(cx, cz, d)) {
                it.remove();
                continue;
            }
            long dx = cx * 16 + 8 - camX, dy = SectionPos.y(k) * 16 + 8 - camY, dz = cz * 16 + 8 - camZ;
            long dist = dx * dx + dy * dy + dz * dz;
            if (LOD_CONE > 0) dist = conePriority(k, dist, camCxs, camCzs);
            if (hn == BUDGET && dist >= hd[0]) continue;
            long ck = ChunkKey(cx, cz);
            if (!RESIDENT.contains(ck)) {
                if (level.getChunkSource().getChunk(cx, cz, false) == null) {
                    it.remove();
                    continue;
                }
                RESIDENT.add(ck);   // loaded after the camera came: resident now
                for (int s = 0; s < secY; s++) STALE.add(SectionPos.asLong(cx, level.getMinSection() + s, cz));
            }
            LevelChunk c = level.getChunkSource().getChunk(cx, cz, false);
            int si = SectionPos.y(k) - level.getMinSection();
            if (c == null || si < 0 || si >= secY || c.getSections()[si].hasOnlyAir()) {   // nothing to draw
                it.remove();
                dropRec(k);
                continue;
            }
            if (camLit && Math.max(Math.abs(cx - camCxs), Math.abs(cz - camCzs)) > 1 && noSkyAround(skyLight, k)) {   // dark and far: not meshed
                it.remove();
                dropRec(k);
                SKIPPED.add(k);
                darkSkips++;
                continue;
            }
            int at;
            if (hn < BUDGET) {   // push, sift up
                at = hn++;
                while (at > 0 && hd[(at - 1) / 2] < dist) {
                    hk[at] = hk[(at - 1) / 2];
                    hd[at] = hd[(at - 1) / 2];
                    at = (at - 1) / 2;
                }
            } else {   // replace the farthest, sift down
                at = 0;
                while (true) {
                    int l = 2 * at + 1, r = l + 1, m = at;
                    long md = dist;
                    if (l < hn && hd[l] > md) { m = l; md = hd[l]; }
                    if (r < hn && hd[r] > md) m = r;
                    if (m == at) break;
                    hk[at] = hk[m];
                    hd[at] = hd[m];
                    at = m;
                }
            }
            hk[at] = k;
            hd[at] = dist;
        }
        fPick += System.nanoTime() - tp;
        if (hn == 0) return;
        int slot = -1;
        for (int i = 0; i < FLIGHT; i++) if (!SLOT_BUSY[i]) { slot = i; break; }
        if (slot < 0) return;
        int n = hn;
        Batch b = new Batch();
        b.keys = new long[n];
        b.n = n;
        b.slotIdx = slot;
        // nearest first; a section joins only while its stale neighbourhood fits the frame's upload budget
        long[] order = new long[n];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (x, y) -> Long.compare(hd[x], hd[y]));
        for (int i = 0; i < n; i++) order[i] = hk[idx[i]];
        int taken = 0;
        for (int i = 0; i < n; i++) {
            long k = order[i];
            int need = 0;
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        long nk = SectionPos.offset(k, dx, dy, dz);
                        if (STALE.contains(nk) || STALE_LIGHT.contains(nk)) need++;
                    }
            if (taken > 0 && framesUploads + need > UPLOADS) break;
            b.keys[taken++] = k;
            DIRTY.remove(k);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        long nk = SectionPos.offset(k, dx, dy, dz);
                        if (STALE.remove(nk)) {
                            STALE_LIGHT.remove(nk);
                            uploadSection(nk);
                        } else if (STALE_LIGHT.remove(nk)) uploadLight(nk);
                    }
        }
        n = taken;
        b.n = n;
        if (libDirty) uploadLibrary();
        flushUnpack();
        IntBuffer sb = MemoryUtil.memAllocInt(n * 4);
        if (b.lod == null || b.lod.length < n) b.lod = new int[Math.max(n, BUDGET)];
        for (int i = 0; i < n; i++) {
            long k = b.keys[i];
            int lod = lodFor(k, camCx, camCz, 0);
            b.lod[i] = lod;
            if (lod > 0) lodSections++;
            if (lod > 1) lod4Sections++;
            sb.put(SectionPos.x(k) * 16).put(SectionPos.y(k) * 16).put(SectionPos.z(k) * 16).put(slotOf(k) | (lod > 0 ? 1 << 30 : 0) | (lod > 1 ? 1 << 29 : 0));
        }
        sb.flip();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, sectionBufs[slot]);
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, sb);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        MemoryUtil.memFree(sb);
        dispatch(0, n, slot);
        b.fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        b.phase = 1;
        SLOT_BUSY[slot] = true;
        FLIGHTS.add(b);
        batches++;
    }

    private static void dispatch(int pass, int n, int slot) {
        long td = System.nanoTime();
        try {
            dispatch0(pass, n, slot);
        } finally {
            fDispatch += System.nanoTime() - td;
        }
    }

    private static void dispatch0(int pass, int n, int slot) {
        dispatchWith(MESH_PROG[meshVariant], MESH_U[meshVariant], pass, n, sectionBufs[slot], basesBufs[slot], countsBufs[slot], true);
    }

    private static void dispatchWith(int prog, int[] u, int pass, int n, int secBuf, int basesBuf, int countsBuf, boolean ringTimed) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 9);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, gridTex);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 10);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, lightTex);
        int[][] binds = {{1, faceSlotBuf}, {2, famBuf}, {3, fpropBuf}, {4, entryBuf}, {5, offsetBuf}, {6, variantBuf}, {7, basesBuf}, {8, aoBuf}, {9, pairBuf}, {10, secBuf}, {11, libBuf},
                {12, propBuf}, {13, outBuf}, {14, quadBuf}, {15, countsBuf}};
        for (int[] bb : binds) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bb[0], bb[1]);
        if (MERGE_STATS) {
            if (mergeStatsBuf == 0) {
                mergeStatsBuf = dynamicBuffer(32, null);
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, mergeStatsBuf);
                org.lwjgl.opengl.GL43C.glClearBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, (int[]) null);
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            }
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, mergeStatsBuf);
        }
        GlStateManager._glUseProgram(prog);
        GL20C.glUniform3i(u[0], nx, ny, nx);
        GL20C.glUniform1i(u[1], NMAX);
        GL20C.glUniform1i(u[2], minY);
        GL20C.glUniform1i(u[3], pass);
        if (ringTimed) elapsedBegin(1);
        GL43C.glDispatchCompute(n, 1, 1);
        if (ringTimed) elapsedEnd();
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
        for (int[] bb : binds) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bb[0], 0);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, 0);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 9);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._glUseProgram(0);
        net.minecraft.client.renderer.GameRenderer.getPositionShader().clear();
    }

    private static boolean signaled(long fence) {
        int r = GL32C.glClientWaitSync(fence, 0, 0L);
        return r == GL32C.GL_ALREADY_SIGNALED || r == GL32C.GL_CONDITION_SATISFIED;
    }

    /** the batches in flight, oldest first: counted -> sizes read, blocks allocated, written; written -> published */
    private static void progress() {
        for (Batch b : new ArrayList<>(FLIGHTS)) {
            if (!signaled(b.fence)) {
                waitFrames++;
                continue;
            }
            GL32C.glDeleteSync(b.fence);
            if (b.phase == 1) written(b);
            else {
                publish(b);
                FLIGHTS.remove(b);
                SLOT_BUSY[b.slotIdx] = false;
            }
        }
    }

    private static void written(Batch b) {
        b.counts = new int[b.n * BUCKETS];
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, countsBufs[b.slotIdx]);
        timedGet(GL43C.GL_SHADER_STORAGE_BUFFER, 0, b.counts);
        b.allocs = new int[b.n];
        b.bases = new int[b.n * BUCKETS];
        for (int i = 0; i < b.n; i++) {
            int total = 0;
            for (int k = 0; k < BUCKETS; k++) total += b.counts[i * BUCKETS + k];
            int at = allocate(total);
            if (at == -2) {   // the face buffer is full: this section waits
                fullStalls++;
                DIRTY.add(b.keys[i]);
                at = -1;
                for (int k = 0; k < BUCKETS; k++) b.counts[i * BUCKETS + k] = 0;
            }
            b.allocs[i] = at;
            int run = Math.max(at, 0);
            for (int k = 0; k < BUCKETS; k++) {
                b.bases[i * BUCKETS + k] = run;
                run += b.counts[i * BUCKETS + k];
            }
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, basesBufs[b.slotIdx]);
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, b.bases);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        dispatch(2, b.n, b.slotIdx);
        b.fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        b.phase = 2;
    }

    /** the sections' new faces replace the old ones */
    private static void publish(Batch b) {
        meshGen++;
        int[] slotOrigins = new int[4];
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, slotBuf);
        for (int i = 0; i < b.n; i++) {
            long k = b.keys[i];
            if (!RESIDENT.contains(ChunkKey(SectionPos.x(k), SectionPos.z(k)))) {   // it left while in flight
                if (b.allocs[i] >= 0) release(b.allocs[i], sum(b.counts, i));
                continue;
            }
            Rec r = RECS.get(k);
            if (r == null) {
                r = new Rec();
                RECS.put(k, r);
            } else release(r.alloc, r.size);
            r.slot = slotOf(k);
            r.key = k;
            int newLod = b.lod != null ? b.lod[i] : 0;
            if (newLod != r.lod && (newLod == 0 || r.lod == 0) && VANILLA_OWNED.contains(b.keys[i])) ChunkMultiDraw.invalidateLayerCaches();   // vanillaOwns changes
            r.lod = newLod;
            SLOT_REC[r.slot] = r;
            r.alloc = b.allocs[i];
            r.size = sum(b.counts, i);
            r.nonEmpty = 0;
            r.transCount = 0;
            for (int q = 0; q < BUCKETS; q++) {
                r.base[q] = b.bases[i * BUCKETS + q];
                r.count[q] = b.counts[i * BUCKETS + q];
                if (r.count[q] > 0 && q < 18) r.nonEmpty |= 1 << q;
                if (q >= 18) r.transCount += r.count[q];
            }
            slotOrigins[0] = SectionPos.x(k) * 16;
            slotOrigins[1] = SectionPos.y(k) * 16;
            slotOrigins[2] = SectionPos.z(k) * 16;
            slotOrigins[3] = r.slot;
            timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 16L * r.slot, slotOrigins);
            setRanges(r);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, slotBuf);
            meshed++;
            if (BAKE && VERTS && r.alloc >= 0) bakeAdd(r, !TINT_PENDING.contains(k));
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        if (BAKE && VERTS) bakeFlush();
    }


    private static int sum(int[] counts, int i) {
        int t = 0;
        for (int k = 0; k < BUCKETS; k++) t += counts[i * BUCKETS + k];
        return t;
    }

    // ---- timing ----

    private static long cpuMoved, cpuProgress, cpuBegin, cpuDraw, cpuFrames, gpuMesh, gpuDraw, gpuFrames, cpuUploads, cpuTints;
    private static final int TR = 8;
    private static final int[][] TQ = new int[TR][3];
    private static final boolean[] TQ_USED = new boolean[TR];
    private static int tslot;

    private static final int ER = 16;
    private static final int[] EQ = new int[ER];
    private static final int[] EQ_KIND = new int[ER];   // 0 unused, 1 mesh, 2 unpack, 3 draw
    private static int eslot;
    private static long gpuBusyMesh, gpuBusyUnpack, gpuBusyDraw;

    private static void elapsedBegin(int kind) {
        if (EQ[0] == 0) GlTrack.queries(EQ);
        if (EQ_KIND[eslot] != 0) {   // a whole ring ago: long finished, or dropped
            if (GL15C.glGetQueryObjecti(EQ[eslot], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long ns = GL33C.glGetQueryObjecti64(EQ[eslot], GL15C.GL_QUERY_RESULT);
                if (EQ_KIND[eslot] == 1) gpuBusyMesh += ns;
                else if (EQ_KIND[eslot] == 2) gpuBusyUnpack += ns;
                else gpuBusyDraw += ns;
            }
        }
        EQ_KIND[eslot] = kind;
        GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, EQ[eslot]);
    }

    private static void elapsedEnd() {
        GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        eslot = (eslot + 1) % ER;
    }

    /** the opaque layers from the GPU-written commands (glMultiDrawElementsIndirectCount: the counts stay on the GPU) */
    private static void drawLayersGpu(Matrix4f frustumMatrix, Matrix4f projectionMatrix, net.minecraft.world.phys.Vec3 cam, int bx, int by, int bz) {
        var mc = Minecraft.getInstance();
        projectionMatrix.mul(frustumMatrix, VP);
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        int lightmap = RenderSystem.getShaderTexture(2);
        float[] fog = RenderSystem.getShaderFogColor();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 2);
        GlStateManager._bindTexture(lightmap);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._bindTexture(mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getId());
        int[][] binds = {{13, outBuf}, {14, quadBuf}, {5, offsetBuf}, {2, slotBuf}, {3, sectionTintBuf}, {6, tintBuf}, {4, famBuf}};
        for (int[] bb : binds) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bb[0], bb[1]);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_LEQUAL);
        GlStateManager._depthMask(true);
        GlStateManager._disableBlend();
        GlStateManager._enableCull();
        GlStateManager._glBindVertexArray(VERTS ? vaoV : vao);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, gcmdBuf);
        GL15C.glBindBuffer(org.lwjgl.opengl.GL46C.GL_PARAMETER_BUFFER, gcountBuf);
        elapsedBegin(3);
        for (int layer = 0; layer < 3; layer++) {
            int prog = VERTS ? (layer == 0 ? drawSolidV : drawCutoutV) : layer == 0 ? drawSolid : drawCutout;
            int[] u = VERTS ? (layer == 0 ? U_SOLID_V : U_CUTOUT_V) : layer == 0 ? U_SOLID : U_CUTOUT;
            GlStateManager._glUseProgram(prog);
            org.lwjgl.opengl.GL33C.glBindSampler(0, layer == 2 ? samplerFlat : samplerMip);
            GL20C.glUniformMatrix4fv(u[0], false, VP.get(MAT));
            GL20C.glUniform3i(u[1], bx, by, bz);
            GL20C.glUniform3f(u[5], (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
            GL20C.glUniform4f(u[2], fog[0], fog[1], fog[2], fog[3]);
            GL20C.glUniform2f(u[3], fogStart(), fogEnd());
            if (layer > 0) GL20C.glUniform1f(u[4], layer == 1 ? 0.5f : 0.1f);
            org.lwjgl.opengl.GL46C.glMultiDrawElementsIndirectCount(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, (long) layer * MAXC * 20, 4L * layer, MAXC, 20);
        }
        elapsedEnd();
        GL15C.glBindBuffer(org.lwjgl.opengl.GL46C.GL_PARAMETER_BUFFER, 0);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
        org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
        for (int[] bb : binds) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bb[0], 0);
        GlStateManager._glUseProgram(0);
        net.minecraft.client.renderer.GameRenderer.getPositionShader().clear();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }


    // ---- -Dlab.voxel.stream.occlusion (with .vertices): two-phase GPU occlusion culling of the opaque layers (as
    // OcclusionCull for the raster, notes/52): phase 1 draws the sections visible last frame (the CPU-built commands
    // copied with 0 instances for the others: order kept), a max-depth pyramid is built from the depth, every visible
    // section's box (2 blocks of margin) is tested against it, phase 2 draws the newly visible ----
    private static final boolean OCCLUSION = Boolean.getBoolean("lab.voxel.stream.occlusion");
    private static int occMaskProg, occDown0Prog, occDownProg, occTestProg, occA, occB, occVisPrev, occVisNext, occSlotsBuf;
    private static long occCmdCap, occSlotCap;
    private static int hizTex, hizW, hizH, hizLevels, occFrameW, occFrameH;
    private static int[] occSlotList = new int[0];
    private static final int OCC_UNIT = 31;

    private static final String OCC_MASK = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 8) buffer In { uint cin[]; };
            layout(std430, binding = 9) buffer Out { uint cout[]; };
            layout(std430, binding = 10) buffer Prev { uint visPrev[]; };
            layout(std430, binding = 11) buffer Next { uint visNext[]; };
            uniform uint N;
            uniform uint Phase;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= N) return;
                uint b = i * 5u, slot = cin[b + 4u];
                uint inst = Phase == 1u ? visPrev[slot] : (visNext[slot] != 0u && visPrev[slot] == 0u ? 1u : 0u);
                cout[b] = cin[b];
                cout[b + 1u] = inst;
                cout[b + 2u] = cin[b + 2u];
                cout[b + 3u] = cin[b + 3u];
                cout[b + 4u] = slot;
            }
            """;
    private static final String OCC_DOWN0 = """
            #version 430
            layout(local_size_x = 8, local_size_y = 8) in;
            uniform sampler2D Depth;
            layout(r32f, binding = 0) uniform image2D Dst;
            uniform ivec2 SrcSize;
            uniform ivec2 DstSize;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= DstSize.x || p.y >= DstSize.y) return;
                float m = 0.0;
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) m = max(m, texelFetch(Depth, min(p * 2 + ivec2(dx, dy), SrcSize - 1), 0).r);
                imageStore(Dst, p, vec4(m));
            }
            """;
    private static final String OCC_DOWN = """
            #version 430
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(r32f, binding = 1) uniform image2D Src;
            layout(r32f, binding = 0) uniform image2D Dst;
            uniform ivec2 SrcSize;
            uniform ivec2 DstSize;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= DstSize.x || p.y >= DstSize.y) return;
                float m = 0.0;
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) m = max(m, imageLoad(Src, min(p * 2 + ivec2(dx, dy), SrcSize - 1)).r);
                imageStore(Dst, p, vec4(m));
            }
            """;
    private static final String OCC_TEST = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 2) buffer Slots { ivec4 slots[]; };
            layout(std430, binding = 9) buffer Vis { uint vis[]; };
            layout(std430, binding = 11) buffer Next { uint visNext[]; };
            uniform uint N;
            uniform mat4 ViewProj;
            uniform ivec3 CamBlock;
            uniform vec3 CamFrac;
            uniform sampler2D Hiz;
            uniform ivec2 HizSize;
            uniform int Levels;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= N) return;
                uint slot = vis[i];
                vec3 lo = vec3(slots[slot].xyz - CamBlock) - CamFrac - vec3(2.0), hi = lo + vec3(20.0);
                vec2 mn = vec2(1e9), mx = vec2(-1e9);
                float zmin = 1e9;
                bool visible = false;
                for (int k = 0; k < 8; k++) {
                    vec3 c = vec3((k & 1) != 0 ? hi.x : lo.x, (k & 2) != 0 ? hi.y : lo.y, (k & 4) != 0 ? hi.z : lo.z);
                    vec4 h = ViewProj * vec4(c, 1.0);
                    if (h.w <= 0.05) { visible = true; break; }   // near or behind the camera: drawn
                    vec3 nd = h.xyz / h.w;
                    mn = min(mn, nd.xy);
                    mx = max(mx, nd.xy);
                    zmin = min(zmin, nd.z * 0.5 + 0.5);
                }
                if (!visible) {
                    vec2 uv0 = clamp(mn * 0.5 + 0.5, 0.0, 1.0), uv1 = clamp(mx * 0.5 + 0.5, 0.0, 1.0);
                    vec2 span = (uv1 - uv0) * vec2(HizSize) + 1.0;   // a pixel of jitter
                    int lvl = clamp(int(ceil(log2(max(max(span.x, span.y), 1.0)))), 0, Levels - 1);
                    ivec2 ls = max(HizSize >> lvl, ivec2(1));
                    ivec2 a = clamp(ivec2(uv0 * vec2(ls)), ivec2(0), ls - 1), b = clamp(ivec2(uv1 * vec2(ls)), ivec2(0), ls - 1);
                    float m = 0.0;
                    for (int y = a.y; y <= min(b.y, a.y + 2); y++)
                        for (int x = a.x; x <= min(b.x, a.x + 2); x++) m = max(m, texelFetch(Hiz, ivec2(x, y), lvl).r);
                    visible = zmin <= m;
                }
                visNext[slot] = visible ? 1u : 0u;
            }
            """;

    private static void occlusionInit() {
        occMaskProg = VoxelCompute.program(OCC_MASK, null, null);
        occDown0Prog = VoxelCompute.program(OCC_DOWN0, null, null);
        occDownProg = VoxelCompute.program(OCC_DOWN, null, null);
        occTestProg = VoxelCompute.program(OCC_TEST, null, null);
        if (occMaskProg == 0 || occDown0Prog == 0 || occDownProg == 0 || occTestProg == 0) throw new IllegalStateException("occlusion shaders failed (see the log)");
        int slots = chunks * chunks * secY;
        occVisPrev = dynamicBuffer(4L * slots, null);
        occVisNext = dynamicBuffer(4L * slots, null);
        for (int b : new int[] {occVisPrev, occVisNext}) {   // everything visible to start with: the first frame draws all in phase 1
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, b);
            org.lwjgl.opengl.GL43C.glClearBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, new int[] {1});
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void occBuffers(int n) {
        if (20L * n > occCmdCap) {
            if (occA != 0) GlTrack.delBufs(new int[] {occA, occB});
            occCmdCap = 20L * (n + n / 2 + 1024);
            for (int k = 0; k < 2; k++) {
                int b = GlTrack.buf(GL15C.glGenBuffers());
                GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, b);
                GL44C.glBufferStorage(GL40C.GL_DRAW_INDIRECT_BUFFER, occCmdCap, 0);   // GPU-written only: immutable (Intel)
                if (k == 0) occA = b;
                else occB = b;
            }
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        }
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (hizTex == 0 || target.width != occFrameW || target.height != occFrameH) {
            if (hizTex != 0) GlTrack.delTex(hizTex);
            occFrameW = target.width;
            occFrameH = target.height;
            hizW = Math.max(1, (occFrameW + 1) / 2);
            hizH = Math.max(1, (occFrameH + 1) / 2);
            hizLevels = 1 + (int) Math.floor(Math.log(Math.max(hizW, hizH)) / Math.log(2));
            hizTex = GlTrack.tex(GL11C.glGenTextures());
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, hizTex);
            org.lwjgl.opengl.GL42C.glTexStorage2D(GL11C.GL_TEXTURE_2D, hizLevels, GL30C.GL_R32F, hizW, hizH);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        }
    }

    /** phase 1: the commands with last frame's visibility (bound as the indirect buffer); phase 2: pyramid, test, the newly visible */
    private static void occlusionPhase(int phase, int n, int bx, int by, int bz, net.minecraft.world.phys.Vec3 cam) {
        if (occMaskProg == 0) occlusionInit();
        if (phase == 1) occBuffers(n);
        int prog = GlStateManager._getInteger(GL20C.GL_CURRENT_PROGRAM);
        if (phase == 2) {
            var target = Minecraft.getInstance().getMainRenderTarget();
            // the pyramid: level 0 from the depth (max of 2x2), then each level from the one below
            GlStateManager._glUseProgram(occDown0Prog);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + OCC_UNIT);   // a unit GlStateManager does not track
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, target.getDepthTextureId());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(occDown0Prog, "Depth"), OCC_UNIT);
            GL20C.glUniform2i(GL20C.glGetUniformLocation(occDown0Prog, "SrcSize"), occFrameW, occFrameH);
            GL20C.glUniform2i(GL20C.glGetUniformLocation(occDown0Prog, "DstSize"), hizW, hizH);
            org.lwjgl.opengl.GL42C.glBindImageTexture(0, hizTex, 0, false, 0, org.lwjgl.opengl.GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
            GL43C.glDispatchCompute((hizW + 7) / 8, (hizH + 7) / 8, 1);
            GlStateManager._glUseProgram(occDownProg);
            int uS = GL20C.glGetUniformLocation(occDownProg, "SrcSize"), uD = GL20C.glGetUniformLocation(occDownProg, "DstSize");
            for (int l = 1; l < hizLevels; l++) {
                GL43C.glMemoryBarrier(org.lwjgl.opengl.GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                int sw = Math.max(1, hizW >> (l - 1)), sh = Math.max(1, hizH >> (l - 1)), dw = Math.max(1, hizW >> l), dh = Math.max(1, hizH >> l);
                org.lwjgl.opengl.GL42C.glBindImageTexture(1, hizTex, l - 1, false, 0, org.lwjgl.opengl.GL15C.GL_READ_ONLY, GL30C.GL_R32F);
                org.lwjgl.opengl.GL42C.glBindImageTexture(0, hizTex, l, false, 0, org.lwjgl.opengl.GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
                GL20C.glUniform2i(uS, sw, sh);
                GL20C.glUniform2i(uD, dw, dh);
                GL43C.glDispatchCompute((dw + 7) / 8, (dh + 7) / 8, 1);
            }
            GL43C.glMemoryBarrier(org.lwjgl.opengl.GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
            org.lwjgl.opengl.GL42C.glBindImageTexture(0, 0, 0, false, 0, org.lwjgl.opengl.GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
            org.lwjgl.opengl.GL42C.glBindImageTexture(1, 0, 0, false, 0, org.lwjgl.opengl.GL15C.GL_READ_ONLY, GL30C.GL_R32F);
            // the test: every visible section's box against the pyramid
            int vn = 0;
            int sz = visRecsCount;
            if (occSlotList.length < sz) occSlotList = new int[sz + sz / 2 + 256];
            for (int i = 0; i < sz; i++) occSlotList[vn++] = visRecs[i].slot;
            if (4L * Math.max(vn, 1) > occSlotCap) {
                if (occSlotsBuf != 0) GlTrack.delBuf(occSlotsBuf);
                occSlotCap = 4L * (vn + vn / 2 + 256);
                occSlotsBuf = dynamicBuffer(occSlotCap, null);
            }
            if (vn > 0) {
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, occSlotsBuf);
                IntBuffer sb = MemoryUtil.memAllocInt(vn);
                sb.put(occSlotList, 0, vn).flip();
                timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, sb);
                MemoryUtil.memFree(sb);
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            }
            GlStateManager._glUseProgram(occTestProg);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, hizTex);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(occTestProg, "Hiz"), OCC_UNIT);
            GL30C.glUniform1ui(GL20C.glGetUniformLocation(occTestProg, "N"), vn);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(occTestProg, "ViewProj"), false, VP.get(MAT));
            GL20C.glUniform3i(GL20C.glGetUniformLocation(occTestProg, "CamBlock"), bx, by, bz);
            GL20C.glUniform3f(GL20C.glGetUniformLocation(occTestProg, "CamFrac"), (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
            GL20C.glUniform2i(GL20C.glGetUniformLocation(occTestProg, "HizSize"), hizW, hizH);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(occTestProg, "Levels"), hizLevels);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, slotBuf);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 9, occSlotsBuf);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 11, occVisNext);
            if (vn > 0) GL43C.glDispatchCompute((vn + 63) / 64, 1, 1);
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        }
        // the phase's commands: the CPU's with the phase's instance counts
        GlStateManager._glUseProgram(occMaskProg);
        GL30C.glUniform1ui(GL20C.glGetUniformLocation(occMaskProg, "N"), n);
        GL30C.glUniform1ui(GL20C.glGetUniformLocation(occMaskProg, "Phase"), phase);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 8, cmdBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 9, phase == 1 ? occA : occB);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 10, occVisPrev);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 11, occVisNext);
        GL43C.glDispatchCompute((n + 63) / 64, 1, 1);
        GL43C.glMemoryBarrier(org.lwjgl.opengl.GL42C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        for (int b : new int[] {8, 9, 10, 11}) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, b, 0);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, phase == 1 ? occA : occB);
        GlStateManager._glUseProgram(prog);
    }

    /** this frame's visibility becomes next frame's "last frame" */
    private static void occlusionEnd() {
        if (++occFrames % 120 == 0 && visRecsCount > 0) {   // how much the test hides (a synchronous read, every 120 frames)
            int[] vis = new int[chunks * chunks * secY];
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, occVisNext);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, vis);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            for (int i = 0; i < visRecsCount; i++) {
                Rec r = visRecs[i];
                int f = 0;
                for (int k = 0; k < 18; k++) f += r.count[k];
                occTested++;
                occFacesTested += f;
                if (vis[r.slot] == 0) {
                    occHidden++;
                    occFacesHidden += f;
                }
            }
        }
        int t = occVisPrev;
        occVisPrev = occVisNext;
        occVisNext = t;
    }

    private static int visRecsCount;
    private static long occFrames, occTested, occHidden, occFacesTested, occFacesHidden;


    // ---- -Dlab.voxel.stream.compact (with .vertices): one draw per layer instead of ~20 000 small multi-draw commands.
    // The CPU-built commands (visible order kept) with their output offsets go up; a compute pass writes 6 indices per face
    // into one index buffer in that order; each layer is one glDrawElements. The vertex stage finds the section by the
    // face's slot (written by the mesher). N100, the same 411 k faces through the vertex stage: 4.73 ms as commands,
    // 2.24 ms as one draw (draws under ~256 vertices leave the vertex waves part empty, on every GPU) ----
    private static final boolean COMPACT = on("lab.voxel.stream.compact");
    private static int faceSlotBuf, compactProg, compactIdx, vaoC, drawSolidC, drawCutoutC;
    private static long compactCap;
    private static final int[] U_SOLID_C = new int[6], U_CUTOUT_C = new int[6], U_FLAT_C = new int[6];
    private static int drawFlatC;
    private static final int[] CC_BUFS = new int[3];
    private static final long[] CC_CAPS = new long[3];
    private static int[] ccmds = new int[0];

    private static final String COMPACT_CS = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 8) buffer Cmds { uvec4 cc[]; };     // first face, faces, -, output offset (faces)
            layout(std430, binding = 9) buffer Idx { uint idx[]; };
            uniform uint N;
            uniform uint Total;
            void main() {
                uint g = gl_GlobalInvocationID.x;
                if (g >= Total) return;
                uint lo = 0u, hi = N - 1u;   // the command holding output face g: the last with offset <= g
                while (lo < hi) {
                    uint mid = (lo + hi + 1u) >> 1u;
                    if (cc[mid].w <= g) lo = mid; else hi = mid - 1u;
                }
                uint v = (cc[lo].x + (g - cc[lo].w)) * 4u, o = g * 6u;
                idx[o] = v; idx[o + 1u] = v + 1u; idx[o + 2u] = v + 2u;
                idx[o + 3u] = v; idx[o + 4u] = v + 2u; idx[o + 5u] = v + 3u;
            }
            """;

    private static void compactInit(String vsV, String fsHead, String fsTail) {
        compactProg = VoxelCompute.program(COMPACT_CS, null, null);
        String vsC = vsV.replace("layout(location = 3) in ivec4 aSlot;    // per instance: the section's origin and slot",
                        "layout(std430, binding = 2) buffer SlotsC { ivec4 slotsC[]; };\n                layout(std430, binding = 7) buffer FaceSlots { uint faceSlot[]; };")
                .replace("void main() {\n    vec3 local", "void main() {\n    ivec4 aSlot = slotsC[faceSlot[uint(gl_VertexID) >> 2u]];\n    vec3 local");
        if (!vsC.contains("ivec4 aSlot = slotsC[")) throw new IllegalStateException("compact vertex stage: main not found");
        drawSolidC = VoxelCompute.program(null, vsC, fsHead + fsTail);
        drawCutoutC = VoxelCompute.program(null, vsC, fsHead + "        if (c.a < AlphaCut) discard;\n" + fsTail);
        drawFlatC = VoxelCompute.program(null, vsC, fsHead + "        fragColor = vec4(0.5, 0.5, 0.5, 1.0) + 0.0 * c;\n    }\n");
        if (compactProg == 0 || drawSolidC == 0 || drawCutoutC == 0 || drawFlatC == 0) throw new IllegalStateException("compact shaders failed (see the log)");
        if (DRAW_BENCH) {
            String lean = vsC.replace("out vec2 lmuv;", "vec2 lmuv;").replace("out float bright;", "float bright;")
                    .replace("flat out vec3 tintRgb;", "vec3 tintRgb;").replace("flat out float tintA;", "float tintA;");
            String fsLean = fsHead.replace("in vec2 lmuv;", "").replace("in float bright;", "")
                    .replace("flat in vec3 tintRgb;", "").replace("flat in float tintA;", "");
            String[] vss = {lean, lean.replace("if (code != 0u) {", "if (false) {"),
                    lean.replace("textureLod(Lightmap, clamp(lmuv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)), 0.0).rgb", "vec3(lmuv.x / 256.0)")};
            for (int k = 0; k < 3; k++) {
                if (vss[k].equals(lean) && k > 0) throw new IllegalStateException("lean ablation " + k + ": nothing replaced");
                LEAN_C[k] = VoxelCompute.program(null, vss[k], fsLean + fsTail);
                GlStateManager._glUseProgram(LEAN_C[k]);
                GL20C.glUniform1i(GL20C.glGetUniformLocation(LEAN_C[k], "Atlas"), 0);
                GL20C.glUniform1i(GL20C.glGetUniformLocation(LEAN_C[k], "Lightmap"), 2);
                String[] names = {"ViewProj", "CamBlock", "FogColorV", "FogRange", "AlphaCut", "CamFrac"};
                for (int j = 0; j < 6; j++) U_LEAN_C[k][j] = GL20C.glGetUniformLocation(LEAN_C[k], names[j]);
            }
        }
        int[][] us = {U_SOLID_C, U_CUTOUT_C, U_FLAT_C};
        int[] ps = {drawSolidC, drawCutoutC, drawFlatC};
        for (int k = 0; k < 3; k++) {
            GlStateManager._glUseProgram(ps[k]);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ps[k], "Atlas"), 0);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ps[k], "Lightmap"), 2);
            us[k][0] = GL20C.glGetUniformLocation(ps[k], "ViewProj");
            us[k][1] = GL20C.glGetUniformLocation(ps[k], "CamBlock");
            us[k][2] = GL20C.glGetUniformLocation(ps[k], "FogColorV");
            us[k][3] = GL20C.glGetUniformLocation(ps[k], "FogRange");
            us[k][4] = GL20C.glGetUniformLocation(ps[k], "AlphaCut");
            us[k][5] = GL20C.glGetUniformLocation(ps[k], "CamFrac");
        }
        vaoC = GlTrack.vao(GL30C.glGenVertexArrays());
        GlStateManager._glBindVertexArray(vaoC);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, outBuf);
        GL30C.glVertexAttribIPointer(0, 4, GL11C.GL_UNSIGNED_SHORT, 16, 16L);
        GL20C.glVertexAttribPointer(1, 2, GL11C.GL_UNSIGNED_SHORT, true, 16, 24L);
        GL30C.glVertexAttribIPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, 16, 28L);
        for (int k = 0; k < 3; k++) GL20C.glEnableVertexAttribArray(k);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
    }

    /** the frame's commands expanded into one index buffer (order kept), then one draw per layer */
    /** -Dlab.voxel.stream.prepass (with .compact; a measurement): the solid layer drawn depth-only first - what overdraw costs at most */
    private static final boolean PREPASS = Boolean.getBoolean("lab.voxel.stream.prepass");

    private static void compactDraw(int n, int[] start, int[] count, int bx, int by, int bz, net.minecraft.world.phys.Vec3 cam, float[] fog) {
        // the commands as (first face, faces, -, output offset), the layers' output ranges
        if (ccmds.length < n * 4) ccmds = new int[n * 4 + n * 2 + 64];
        int total = 0;
        int[] layerFace = new int[4];
        for (int layer = 0; layer < 3; layer++) {
            layerFace[layer] = total;
            for (int pass = 0; pass < (MIXED > 1 ? 2 : 1); pass++) {   // mixed resolution: the near sections first, the far (LOD) ones after
                if (pass == 1) nearFaces[layer] = total - layerFace[layer];
                for (int i = start[layer]; i < start[layer] + count[layer]; i++) {
                    if (MIXED > 1 && isFar(cmds[i * 5 + 4]) != (pass == 1)) continue;
                    int faces = cmds[i * 5] / 6;
                    ccmds[i * 4] = cmds[i * 5 + 3] / 4;
                    ccmds[i * 4 + 1] = faces;
                    ccmds[i * 4 + 2] = 0;
                    ccmds[i * 4 + 3] = total;
                    total += faces;
                }
            }
            if (MIXED <= 1) nearFaces[layer] = total - layerFace[layer];
        }
        layerFace[3] = total;
        if (total == 0) return;
        // the same commands as the frame before (a still camera, nothing meshed into new ranges): the index buffer holds them already
        boolean same = !DRAW_BENCH && n * 4 == lastCcmdsLen && total == lastCcTotal && compactIdx != 0 && java.util.Arrays.equals(ccmds, 0, n * 4, lastCcmds, 0, n * 4)
                && java.util.Arrays.equals(nearFaces, lastNearFaces);
        if (same) compactReused++;
        else {
            if (lastCcmds.length < ccmds.length) lastCcmds = new int[ccmds.length];
            System.arraycopy(ccmds, 0, lastCcmds, 0, n * 4);
            lastCcmdsLen = n * 4;
            lastCcTotal = total;
            System.arraycopy(nearFaces, 0, lastNearFaces, 0, 3);
            compactIndex(n, total);
        }
        if (benchNow == 11) return;
        compactLayers(layerFace, bx, by, bz, cam, fog);
    }

    private static int[] lastCcmds = new int[0];
    private static int lastCcmdsLen, lastCcTotal;
    private static final int[] nearFaces = new int[3], lastNearFaces = new int[3];

    /**
     * -Dlab.voxel.stream.mixedRes=S (2 or 4, with .lod and .compact): the far (LOD) sections drawn into a target S times smaller
     * on each side, then put into the main target with their depth by a full-screen pass; the near ones at full resolution
     * on top. The far terrain's near-pixel triangles cost fragments without showing detail; near terrain keeps its pixels.
     */
    static final int MIXED = Integer.getInteger("lab.voxel.stream.lod", Boolean.getBoolean("lab.voxel.stream.lodNear") ? 4 : 6) > 0 ? Integer.getInteger("lab.voxel.stream.mixedRes", 0) : 0;   // LOD is declared further down
    private static int lowFbo, lowColor, lowDepth, lowW, lowH, compositeProg, emptyVao;
    public static long mixedFrames, mixedFarFaces, mixedAllFaces;

    /** the terrain's fog: vanilla's, pushed out to the far horizon's radius when it draws (no fogged rim at the chunks' edge) */
    static float fogStart() {
        return RenderSystem.getShaderFogStart();
    }

    static float fogEnd() {
        return RenderSystem.getShaderFogEnd();
    }

    private static boolean isFar(int slot) {
        Rec r = slot >= 0 && slot < SLOT_REC.length ? SLOT_REC[slot] : null;
        return r != null && r.lod > 0;
    }

    private static final String COMPOSITE_VS = """
            #version 430
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String COMPOSITE_FS = """
            #version 430
            uniform sampler2D LowColor;
            uniform sampler2D LowDepth;
            uniform int Scale;
            uniform ivec2 LowSize;
            out vec4 fragColor;
            void main() {
                ivec2 q = min(ivec2(gl_FragCoord.xy) / Scale, LowSize - 1);
                float d = texelFetch(LowDepth, q, 0).r;
                if (d >= 1.0) discard;   // nothing far drawn here
                fragColor = texelFetch(LowColor, q, 0);
                gl_FragDepth = d;
            }
            """;

    /** the far target at 1/S of the main one, made or resized */
    private static void lowTarget(int w, int h) {
        int lw = (w + MIXED - 1) / MIXED, lh = (h + MIXED - 1) / MIXED;
        if (lowFbo != 0 && lw == lowW && lh == lowH) return;
        if (lowFbo != 0) {
            GlTrack.delFbo(lowFbo);
            GlTrack.delTex(lowColor);
            GlTrack.delTex(lowDepth);
        }
        lowW = lw;
        lowH = lh;
        lowColor = GlTrack.tex(GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D));
        GL45C.glTextureStorage2D(lowColor, 1, GL11C.GL_RGBA8, lw, lh);
        lowDepth = GlTrack.tex(GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D));
        GL45C.glTextureStorage2D(lowDepth, 1, GL30C.GL_DEPTH_COMPONENT32F, lw, lh);
        for (int t : new int[] {lowColor, lowDepth}) {
            GL45C.glTextureParameteri(t, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL45C.glTextureParameteri(t, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        }
        lowFbo = GlTrack.fbo(GL45C.glCreateFramebuffers());
        GL45C.glNamedFramebufferTexture(lowFbo, GL30C.GL_COLOR_ATTACHMENT0, lowColor, 0);
        GL45C.glNamedFramebufferTexture(lowFbo, GL30C.GL_DEPTH_ATTACHMENT, lowDepth, 0);
        if (compositeProg == 0) {
            compositeProg = VoxelCompute.program(null, COMPOSITE_VS, COMPOSITE_FS);
            if (compositeProg == 0) throw new IllegalStateException("mixed resolution: the composite shader failed (see the log)");
            emptyVao = GlTrack.vao(GL30C.glGenVertexArrays());
        }
    }

    /** one layer's faces [first, first + faces) of the compacted index buffer, with the layer's program */
    private static void drawRange(int layer, int first, int faces, int bx, int by, int bz, net.minecraft.world.phys.Vec3 cam, float[] fog) {
        if (faces <= 0) return;
        int p = benchNow == 12 ? drawFlatC : benchNow >= 14 ? LEAN_C[benchNow - 14] : layer == 0 ? drawSolidC : drawCutoutC;
        int[] u = benchNow == 12 ? U_FLAT_C : benchNow >= 14 ? U_LEAN_C[benchNow - 14] : layer == 0 ? U_SOLID_C : U_CUTOUT_C;
        GlStateManager._glUseProgram(p);
        org.lwjgl.opengl.GL33C.glBindSampler(0, layer == 2 ? samplerFlat : samplerMip);
        GL20C.glUniformMatrix4fv(u[0], false, VP.get(MAT));
        GL20C.glUniform3i(u[1], bx, by, bz);
        GL20C.glUniform3f(u[5], (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
        GL20C.glUniform4f(u[2], fog[0], fog[1], fog[2], fog[3]);
        GL20C.glUniform2f(u[3], fogStart(), fogEnd());
        if (layer > 0) GL20C.glUniform1f(u[4], layer == 1 ? 0.5f : 0.1f);
        GL11C.glDrawElements(GL11C.GL_TRIANGLES, faces * 6, GL11C.GL_UNSIGNED_INT, 24L * first);
    }
    public static long compactReused;

    /** the commands up, the index buffer written by the compute pass (one index run per face, in command order) */
    private static void compactIndex(int n, int total) {
        int ri = (int) (drawFrames % 3);
        if (16L * n > CC_CAPS[ri]) {
            if (CC_BUFS[ri] != 0) GlTrack.delBuf(CC_BUFS[ri]);
            CC_CAPS[ri] = 16L * (n + n / 2 + 1024);
            CC_BUFS[ri] = dynamicBuffer(CC_CAPS[ri], null);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, CC_BUFS[ri]);
        IntBuffer cb = MemoryUtil.memAllocInt(n * 4);
        cb.put(ccmds, 0, n * 4).flip();
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, cb);
        MemoryUtil.memFree(cb);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        if (24L * total > compactCap) {   // the index buffer: GPU-written only (immutable storage), grown with room
            if (compactIdx != 0) GlTrack.delBuf(compactIdx);
            compactCap = 24L * (total + total / 2 + 4096);
            compactIdx = GlTrack.buf(GL15C.glGenBuffers());
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, compactIdx);
            GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, compactCap, 0);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            GlStateManager._glBindVertexArray(vaoC);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, compactIdx);
            GlStateManager._glBindVertexArray(0);
        }
        int prog = GlStateManager._getInteger(GL20C.GL_CURRENT_PROGRAM);
        GlStateManager._glUseProgram(compactProg);
        GL30C.glUniform1ui(GL20C.glGetUniformLocation(compactProg, "N"), n);
        GL30C.glUniform1ui(GL20C.glGetUniformLocation(compactProg, "Total"), total);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 8, CC_BUFS[ri]);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 9, compactIdx);
        GL43C.glDispatchCompute((total + 63) / 64, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_ELEMENT_ARRAY_BARRIER_BIT);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 8, 0);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 9, 0);
        GlStateManager._glUseProgram(prog);
    }

    private static void compactLayers(int[] layerFace, int bx, int by, int bz, net.minecraft.world.phys.Vec3 cam, float[] fog) {
        GlStateManager._glBindVertexArray(vaoC);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, slotBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 7, faceSlotBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, sectionTintBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tintBuf);
        if (PREPASS && layerFace[1] > 0) {   // the solid layer's depth first (no colour): the colour passes then shade only the nearest
            GlStateManager._glUseProgram(drawFlatC);
            GL20C.glUniformMatrix4fv(U_FLAT_C[0], false, VP.get(MAT));
            GL20C.glUniform3i(U_FLAT_C[1], bx, by, bz);
            GL20C.glUniform3f(U_FLAT_C[5], (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
            GlStateManager._colorMask(false, false, false, false);
            GL11C.glDrawElements(GL11C.GL_TRIANGLES, layerFace[1] * 6, GL11C.GL_UNSIGNED_INT, 0L);
            GlStateManager._colorMask(true, true, true, true);
        }
        int farFaces = 0;
        for (int layer = 0; layer < 3; layer++) farFaces += layerFace[layer + 1] - layerFace[layer] - nearFaces[layer];
        if (MIXED > 1 && farFaces > 0 && benchNow < 0) {
            int mainFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            int[] vp = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, vp);
            lowTarget(vp[2], vp[3]);
            // 1. the far sections into the small target
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, lowFbo);
            GL11C.glViewport(0, 0, lowW, lowH);
            GL45C.glClearNamedFramebufferfv(lowFbo, GL11C.GL_COLOR, 0, new float[] {0f, 0f, 0f, 0f});
            GL45C.glClearNamedFramebufferfv(lowFbo, GL11C.GL_DEPTH, 0, new float[] {1f});
            GlStateManager._glBindVertexArray(vaoC);
            for (int layer = 0; layer < 3; layer++)
                drawRange(layer, layerFace[layer] + nearFaces[layer], layerFace[layer + 1] - layerFace[layer] - nearFaces[layer], bx, by, bz, cam, fog);
            // 2. into the main target with their depth
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, mainFbo);
            GL11C.glViewport(vp[0], vp[1], vp[2], vp[3]);
            GlStateManager._glUseProgram(compositeProg);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 4);
            GlStateManager._bindTexture(lowColor);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 5);
            GlStateManager._bindTexture(lowDepth);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(compositeProg, "LowColor"), 4);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(compositeProg, "LowDepth"), 5);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(compositeProg, "Scale"), MIXED);
            GL20C.glUniform2i(GL20C.glGetUniformLocation(compositeProg, "LowSize"), lowW, lowH);
            GlStateManager._glBindVertexArray(emptyVao);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            GlStateManager._bindTexture(0);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 4);
            GlStateManager._bindTexture(0);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
            // 3. the near sections at full resolution
            GlStateManager._glBindVertexArray(vaoC);
            for (int layer = 0; layer < 3; layer++) drawRange(layer, layerFace[layer], nearFaces[layer], bx, by, bz, cam, fog);
            mixedFrames++;
            mixedFarFaces += farFaces;
            mixedAllFaces += layerFace[3];
            return;
        }
        for (int layer = 0; layer < 3; layer++) drawRange(layer, layerFace[layer], layerFace[layer + 1] - layerFace[layer], bx, by, bz, cam, fog);
    }

    private static void gpuMark(int i) {
        if (TQ[0][0] == 0) for (int[] q : TQ) GlTrack.queries(q);
        GL33C.glQueryCounter(TQ[tslot][i], GL33C.GL_TIMESTAMP);
        if (i == 2) {
            TQ_USED[tslot] = true;
            tslot = (tslot + 1) % TR;
        }
    }

    private static void gpuCollect() {
        int s = tslot;   // the oldest slot: written TR-1 frames ago
        if (!TQ_USED[s] || GL15C.glGetQueryObjecti(TQ[s][2], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) return;
        TQ_USED[s] = false;
        long a = GL33C.glGetQueryObjecti64(TQ[s][0], GL15C.GL_QUERY_RESULT), b = GL33C.glGetQueryObjecti64(TQ[s][1], GL15C.GL_QUERY_RESULT),
                c = GL33C.glGetQueryObjecti64(TQ[s][2], GL15C.GL_QUERY_RESULT);
        gpuMesh += b - a;
        gpuDraw += c - b;
        gpuFrames++;
    }

    // ---- the draw ----

    private static final Matrix4f VP = new Matrix4f();
    private static final float[] MAT = new float[16];
    private static final int MAX_QUADS_PER_DRAW = 32768;
    private static int[] cmds = new int[0];
    private static IntBuffer cmdStage;
    private static long drawnFaces, drawFrames;

    /** -Dlab.voxel.stream.farSkip=N (measurement): the opaque layers draw only sections within N chunks of the camera - the ceiling of a far LOD */
    private static final int FAR_SKIP = Integer.getInteger("lab.voxel.stream.farSkip", 0);

    private static void draw(Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        if (!VoxelLod.cutAll) return;
        var mc = Minecraft.getInstance();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        var visible = dev.lull.optsupport.VoxelStream.visibleChunks(mc);
        int need = (visible.size() + 8) * 18 * 5;
        if (cmds.length < need) cmds = new int[need + need / 2];   // with room: not a new array each time the view grows a little
        int[] start = new int[3], count = new int[3];
        int n = 0, frameFaces = 0;
        long td0 = System.nanoTime();
        if (GPU_CMDS) {
            drawFrames++;
            int gbx = net.minecraft.util.Mth.floor(cam.x), gby = net.minecraft.util.Mth.floor(cam.y), gbz = net.minecraft.util.Mth.floor(cam.z);
            gpuCommands(visible, gbx, gby, gbz, cam);
            long tg = System.nanoTime();
            fDrawBuild += tg - td0;
            drawLayersGpu(frustumMatrix, projectionMatrix, cam, gbx, gby, gbz);
            fDrawSubmit += System.nanoTime() - tg;
            return;
        }
        // the visible sections' records and the directions that can face the camera, once (not per layer)
        int sz = visible.size(), vn = 0;
        if (visRecs.length < sz) {
            visRecs = new Rec[sz + 256];
            visMask = new int[sz + 256];
        }
        int ccx = net.minecraft.util.Mth.floor(cam.x) >> 4, ccz = net.minecraft.util.Mth.floor(cam.z) >> 4;
        for (int i = 0; i < sz; i++) {
            var o = visible.get(i).getOrigin();
            if (FAR_SKIP > 0 && Math.max(Math.abs((o.getX() >> 4) - ccx), Math.abs((o.getZ() >> 4) - ccz)) > FAR_SKIP) continue;
            Rec r = recAt(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4);
            if (r == null || r.alloc < 0) continue;
            int m = 0;
            if (cam.y < o.getY() + 16) m |= 1;
            if (cam.y > o.getY()) m |= 2;
            if (cam.z < o.getZ() + 16) m |= 4;
            if (cam.z > o.getZ()) m |= 8;
            if (cam.x < o.getX() + 16) m |= 16;
            if (cam.x > o.getX()) m |= 32;
            visRecs[vn] = r;
            visMask[vn++] = m;
        }
        visRecsCount = vn;
        for (int i = 0; i < vn; i++) {   // the buckets both facing the camera and not empty, per section once
            int m = visMask[i];
            visMask[i] = (m | m << 6 | m << 12) & visRecs[i].nonEmpty;
        }
        int[] cm = cmds;
        for (int layer = 0; layer < 3; layer++) {
            start[layer] = n;
            int shift = layer * 6;
            for (int i = 0; i < vn; i++) {
                int bits = visMask[i] >>> shift & 63;
                if (bits == 0) continue;
                Rec r = visRecs[i];
                if ((n + 6) * 5 > cm.length) cm = cmds = java.util.Arrays.copyOf(cm, cm.length * 2);
                while (bits != 0) {
                    int dd = Integer.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    int bk = shift + dd, c = r.count[bk], bs = r.base[bk];
                    while (c > 0) {
                        int take = Math.min(c, MAX_QUADS_PER_DRAW);
                        if ((n + 1) * 5 > cm.length) cm = cmds = java.util.Arrays.copyOf(cm, cm.length * 2);
                        int o5 = n * 5;
                        cm[o5] = take * 6;
                        cm[o5 + 1] = 1;
                        cm[o5 + 2] = 0;
                        cm[o5 + 3] = bs * 4;
                        cm[o5 + 4] = VERTS ? r.slot : 0;
                        drawnFaces += take;
                        frameFaces += take;
                        n++;
                        bs += take;
                        c -= take;
                    }
                }
            }
            count[layer] = n - start[layer];
        }
        drawFrames++;
        lastDrawnFaces = frameFaces;
        long td1 = System.nanoTime();
        fDrawBuild += td1 - td0;
        if (n == 0) return;
        // a ring of command buffers (-Dlab.voxel.stream.cmdRing): the frame's commands never overwrite ones the GPU may still read
        int ci = CMD_RING ? (int) (drawFrames % 3) : 0;
        cmdBuf = CMD_BUFS[ci];
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
        if ((long) n * 20 > CMD_CAPS[ci]) {
            GlTrack.delBuf(cmdBuf);
            cmdBuf = CMD_BUFS[ci] = GlTrack.buf(GL15C.glGenBuffers());
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
            CMD_CAPS[ci] = (long) n * 40;
            GL44C.glBufferStorage(GL40C.GL_DRAW_INDIRECT_BUFFER, CMD_CAPS[ci], GL44C.GL_DYNAMIC_STORAGE_BIT);
        }
        if (cmdStage == null || cmdStage.capacity() < n * 5) {
            if (cmdStage != null) MemoryUtil.memFree(cmdStage);
            cmdStage = MemoryUtil.memAllocInt(n * 10);
        }
        cmdStage.clear();
        cmdStage.put(cmds, 0, n * 5).flip();
        timedSub(GL40C.GL_DRAW_INDIRECT_BUFFER, 0, cmdStage);
        long td2 = System.nanoTime();
        fDrawUp += td2 - td1;
        projectionMatrix.mul(frustumMatrix, VP);
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        int lightmap = RenderSystem.getShaderTexture(2);
        float[] fog = RenderSystem.getShaderFogColor();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 2);
        GlStateManager._bindTexture(lightmap);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._bindTexture(mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getId());
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 13, outBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 14, quadBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, offsetBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, slotBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, sectionTintBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tintBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, famBuf);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_LEQUAL);
        GlStateManager._depthMask(true);
        GlStateManager._disableBlend();
        GlStateManager._enableCull();
        GlStateManager._glBindVertexArray(vao);
        int bm = drawBenchMode();
        if (bm >= 0) drawBenchBegin(bm);   // (its own query: time queries do not nest)
        else elapsedBegin(3);
        int bx = net.minecraft.util.Mth.floor(cam.x), by = net.minecraft.util.Mth.floor(cam.y), bz = net.minecraft.util.Mth.floor(cam.z);
        if (VERTS) GlStateManager._glBindVertexArray(vaoV);
        long td3 = System.nanoTime();
        fDrawState += td3 - td2;
        boolean occ = OCCLUSION && VERTS && bm < 0;
        benchNow = bm;
        if (COMPACT && VERTS && (bm < 0 || bm >= 9 && bm <= 12 || bm >= 14) && !occ) {
            compactDraw(n, start, count, bx, by, bz, cam, fog);
            if (bm >= 0) drawBenchEnd(bm);
            else elapsedEnd();
            fDrawSubmit += System.nanoTime() - td3;
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
            GlStateManager._glBindVertexArray(0);
            com.mojang.blaze3d.vertex.BufferUploader.invalidate();
            org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
            for (int bnd : new int[] {13, 14, 5, 2, 3, 6, 4, 7}) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bnd, 0);
            GlStateManager._glUseProgram(0);
            net.minecraft.client.renderer.GameRenderer.getPositionShader().clear();
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            return;
        }
        if (occ) occlusionPhase(1, n, -1, 0, 0, null);   // phase 1: last frame's visible sections
        for (int phase = occ ? 1 : 0; phase <= (occ ? 2 : 0); phase++) {
        if (phase == 2) {   // the pyramid from phase 1's depth, every visible section's box tested, the newly visible drawn
            occlusionPhase(2, n, bx, by, bz, cam);
            GlStateManager._glBindVertexArray(vaoV);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, sectionTintBuf);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tintBuf);
        }
        if (bm == 4 || bm >= 6 && bm <= 8) floorDraw(VP, bm == 4 ? 0 : bm - 5);
        else if (bm == 5) {   // as many faces as the commands draw, as one draw over a contiguous range of the face buffer
            GlStateManager._glUseProgram(VERTS ? drawFlatV : drawFlat);
            int[] u = VERTS ? U_FLAT_V : U_FLAT;
            GL20C.glUniformMatrix4fv(u[0], false, VP.get(MAT));
            GL20C.glUniform3i(u[1], bx, by, bz);
            GL20C.glUniform3f(u[5], (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
            for (int f = 0; f < frameFaces; f += MAX_QUADS_PER_DRAW)
                GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, Math.min(MAX_QUADS_PER_DRAW, frameFaces - f) * 6, GL11C.GL_UNSIGNED_INT, 0L, f * 4);
        }
        else for (int layer = 0; layer < 3; layer++) {
            if (count[layer] == 0) continue;
            boolean flat = bm > 0 && bm != 13;
            int prog = VERTS ? (flat ? drawFlatV : layer == 0 ? drawSolidV : drawCutoutV) : flat ? drawFlat : layer == 0 ? drawSolid : drawCutout;
            int[] u = VERTS ? (flat ? U_FLAT_V : layer == 0 ? U_SOLID_V : U_CUTOUT_V) : flat ? U_FLAT : layer == 0 ? U_SOLID : U_CUTOUT;
            GlStateManager._glUseProgram(prog);
            org.lwjgl.opengl.GL33C.glBindSampler(0, layer == 2 ? samplerFlat : samplerMip);
            GL20C.glUniformMatrix4fv(u[0], false, VP.get(MAT));
            GL20C.glUniform3i(u[1], bx, by, bz);
            GL20C.glUniform3f(u[5], (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
            GL20C.glUniform4f(u[2], fog[0], fog[1], fog[2], fog[3]);
            GL20C.glUniform2f(u[3], fogStart(), fogEnd());
            if (layer > 0 && !flat) GL20C.glUniform1f(u[4], layer == 1 ? 0.5f : 0.1f);
            GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, (long) start[layer] * 20, count[layer], 20);
        }
        }
        if (occ) occlusionEnd();
        if (bm >= 0) drawBenchEnd(bm);
        else elapsedEnd();
        fDrawSubmit += System.nanoTime() - td3;
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
        org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
        for (int bnd : new int[] {13, 14, 5, 2, 3, 6, 4}) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bnd, 0);
        GlStateManager._glUseProgram(0);
        net.minecraft.client.renderer.GameRenderer.getPositionShader().clear();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }

    /**
     * renderLevel's translucent layer (profiler "translucent", before vanilla's own): the sections' translucent faces,
     * sections far to near as vanilla's loop goes, and inside each section back to front by the distance to the quad's
     * centre (vertices 0 and 2, MeshData's centroid) - a bitonic sort per section on the GPU, every frame. Sections
     * vanilla still meshes (fluids) keep their translucent layer in vanilla's draw.
     */
    public static void translucent(Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        if (!active || !ready || !VoxelLod.cutAll) return;
        long t0 = System.nanoTime();
        try {
            translucent0(frustumMatrix, projectionMatrix);
        } finally {
            cpuTrans += System.nanoTime() - t0;
        }
    }

    private static void translucent0(Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        var mc = Minecraft.getInstance();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        var visible = dev.lull.optsupport.VoxelStream.visibleChunks(mc);
        int entries = 0, total = 0;
        if (transList.length < visible.size() * 4) transList = new int[visible.size() * 4 + 64];
        if (TRANS_BY_DISTANCE) {
            // far to near by the section centre's distance (vanilla's reversed visible list is breadth-first order)
            if (transOrder.length < visible.size()) transOrder = new long[visible.size() + 64];
            for (int i = 0; i < visible.size(); i++) {
                var o = visible.get(i).getOrigin();
                double dx = o.getX() + 8 - cam.x, dy = o.getY() + 8 - cam.y, dz = o.getZ() + 8 - cam.z;
                transOrder[i] = (long) (dx * dx + dy * dy + dz * dz) << 20 | i;
            }
            java.util.Arrays.sort(transOrder, 0, visible.size());
        }
        for (int ii = visible.size() - 1; ii >= 0; ii--) {
            int i = TRANS_BY_DISTANCE ? (int) (transOrder[ii] & 0xFFFFF) : ii;
            var rs = visible.get(i);
            var o = rs.getOrigin();
            Rec r = recAt(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4);
            if (r == null || r.alloc < 0) continue;
            int n = 0;
            n = r.transCount;
            if (n == 0) continue;
            if (!rs.compiled.get().isEmpty(RenderType.translucent())) {
                transVanilla++;
                continue;
            }
            transList[entries * 4] = r.base[18];
            transList[entries * 4 + 1] = n;
            transList[entries * 4 + 2] = total;
            transList[entries * 4 + 3] = 0;
            if (n > 4096) transUnsorted++;
            entries++;
            total += n;
        }
        transFrames++;
        if (entries == 0) return;
        transSections += entries;
        transFaces += total;
        if (16L * entries > sortListCapacity) {
            if (sortListBuf != 0) GlTrack.delBuf(sortListBuf);
            sortListCapacity = 32L * entries;
            sortListBuf = dynamicBuffer(sortListCapacity, null);
        }
        if (4L * total > sortedCapacity) {
            if (sortedBuf != 0) GlTrack.delBuf(sortedBuf);
            sortedCapacity = 8L * total;
            sortedBuf = dynamicBuffer(sortedCapacity, null);
            sortedGen = -1;
        }
        int bx = net.minecraft.util.Mth.floor(cam.x), by = net.minecraft.util.Mth.floor(cam.y), bz = net.minecraft.util.Mth.floor(cam.z);
        // as vanilla, a resort only when the camera moved (to another block) or the sections' faces changed
        boolean resort = sortedGen != meshGen || bx != sortedCam[0] || by != sortedCam[1] || bz != sortedCam[2]
                || !java.util.Arrays.equals(transList, 0, entries * 4, sortedList, 0, Math.min(sortedList.length, entries * 4)) || sortedList.length < entries * 4
                || sortedEntries != entries;
        if (resort) {
            transResorts++;
            sortedGen = meshGen;
            sortedCam[0] = bx;
            sortedCam[1] = by;
            sortedCam[2] = bz;
            sortedEntries = entries;
            if (sortedList.length < entries * 4) sortedList = new int[transList.length];
            System.arraycopy(transList, 0, sortedList, 0, entries * 4);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, sortListBuf);
        IntBuffer lb = MemoryUtil.memAllocInt(entries * 4);
        lb.put(transList, 0, entries * 4).flip();
        timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, lb);
        MemoryUtil.memFree(lb);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        int[][] binds = {{13, outBuf}, {14, quadBuf}, {5, offsetBuf}, {2, slotBuf}, {8, sortListBuf}, {9, sortedBuf}};
        for (int[] bb : binds) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bb[0], bb[1]);
        GlStateManager._glUseProgram(sortProg);
        GL20C.glUniform3i(uSortCamBlock, bx, by, bz);
        GL20C.glUniform3f(uSortCamFrac, (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
        elapsedBegin(3);
        if (resort) {
            GL43C.glDispatchCompute(entries, 1, 1);
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        }
        // vanilla's translucent state (blend, depth, the translucent target under Fabulous), then our program
        RenderType.translucent().setupRenderState();
        projectionMatrix.mul(frustumMatrix, VP);
        int lightmap = RenderSystem.getShaderTexture(2);
        float[] fog = RenderSystem.getShaderFogColor();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 2);
        GlStateManager._bindTexture(lightmap);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._bindTexture(mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getId());
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, sectionTintBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tintBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, famBuf);
        GlStateManager._glUseProgram(drawTrans);
        org.lwjgl.opengl.GL33C.glBindSampler(0, samplerMip);
        GL20C.glUniformMatrix4fv(U_TRANS[0], false, VP.get(MAT));
        GL20C.glUniform3i(U_TRANS[1], bx, by, bz);
        GL20C.glUniform3f(U_TRANS[5], (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
        GL20C.glUniform4f(U_TRANS[2], fog[0], fog[1], fog[2], fog[3]);
        GL20C.glUniform2f(U_TRANS[3], fogStart(), fogEnd());
        GlStateManager._glBindVertexArray(vao);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, total * 6);
        elapsedEnd();
        GlStateManager._glBindVertexArray(0);
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
        org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
        for (int bnd : new int[] {13, 14, 5, 2, 8, 9, 3, 6, 4}) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, bnd, 0);
        GlStateManager._glUseProgram(0);
        net.minecraft.client.renderer.GameRenderer.getPositionShader().clear();
        RenderType.translucent().clearRenderState();
    }

    private static int[] transList = new int[0], sortedList = new int[0];
    private static Rec[] visRecs = new Rec[0];
    private static long[] heapK = new long[0], heapD = new long[0];
    private static int[] visMask = new int[0];
    private static final int[] sortedCam = new int[3];
    private static long meshGen, sortedGen = -1, transResorts;
    private static int sortedEntries;
    private static long[] transOrder = new long[0];
    /** -Dlab.voxel.stream.transByDistance: translucent sections far to near by distance instead of the reversed visible list */
    private static final boolean TRANS_BY_DISTANCE = Boolean.getBoolean("lab.voxel.stream.transByDistance");

    /**
     * Every section in view marked dirty for vanilla: on start the sections vanilla compiled before go through takeOver
     * (their old meshes, water among them, would still be drawn); on stop vanilla meshes the taken-over ones again.
     */
    private static void dirtyAllVanilla() {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        int r = mc.options.getEffectiveRenderDistance() + 1, ccx = net.minecraft.util.Mth.floor(cam.x) >> 4, ccz = net.minecraft.util.Mth.floor(cam.z) >> 4;
        for (int x = ccx - r; x <= ccx + r; x++)
            for (int z = ccz - r; z <= ccz + r; z++)
                for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) mc.levelRenderer.setSectionDirty(x, sy, z);
    }

    public static String report() {
        active = false;
        VoxelLod.cutAll = VoxelLod.cutting = false;
        dirtyAllVanilla();
        double secs = (System.nanoTime() - windowStart) / 1e9;
        int used = 0;
        for (Rec r : RECS.values()) used += r.size;
        // sections with no sky light at all (caves and rock, not joined to the sky): what their faces take of the buffer
        long darkSecs = 0, darkFaces = 0;
        var sky = Minecraft.getInstance().level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        for (var e : RECS.long2ObjectEntrySet()) {
            if (e.getValue().size == 0) continue;
            var layer = sky.getDataLayerData(SectionPos.of(e.getLongKey()));
            boolean dark = layer != null;
            if (layer != null && !layer.isEmpty()) for (byte v : layer.getData()) if (v != 0) { dark = false; break; }
            if (dark) {
                darkSecs++;
                darkFaces += e.getValue().size;
            }
        }
        String merge = "";
        if (MERGE_STATS && mergeStatsBuf != 0) {
            int[] m = new int[8];
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, mergeStatsBuf);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, m);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            long all = Integer.toUnsignedLong(m[0]);
            merge = String.format(Locale.ROOT, "%n  merge stats (full-detail faces meshed, all passes): %d; full faces with one light: untinted %d (%.1f %%), tinted %d (%.1f %%); of them up faces: untinted %d, tinted %d",
                    all, m[1] & 0xFFFFFFFFL, 100.0 * (m[1] & 0xFFFFFFFFL) / Math.max(1, all), m[2] & 0xFFFFFFFFL, 100.0 * (m[2] & 0xFFFFFFFFL) / Math.max(1, all), m[3] & 0xFFFFFFFFL, m[4] & 0xFFFFFFFFL);
        }
        String caves = merge + String.format(Locale.ROOT, "%n  without sky light (caves, rock): %d sections, %d faces (%.1f %% of the meshed faces)", darkSecs, darkFaces, 100.0 * darkFaces / Math.max(1, used))
                + (SKIP_DARK ? String.format(Locale.ROOT, "; skipDark: %d skips, %d restored, %d skipped now", darkSkips, darkRestores, SKIPPED.size()) : "")
                + (COMPACT ? String.format(Locale.ROOT, "; compact index reused %d frames of %d", compactReused, drawFrames) : "")
                + (LOD_CONE > 0 ? String.format(Locale.ROOT, "; lod cone K=%.1f: %d sections meshed again for a turn or move", LOD_CONE, coneRemesh) : "")
                + (MIXED > 1 ? String.format(Locale.ROOT, "; mixed resolution 1/%d: %d frames, far faces %.1f %% of the drawn", MIXED, mixedFrames, 100.0 * mixedFarFaces / Math.max(1, mixedAllFaces)) : "");
        return String.format(Locale.ROOT,
                "voxel stream: %d states, %d quads in the library; %d sections meshed in %d batches (%.0f per s), %d section uploads (unpacked on the GPU), %d waits for a fence (frames), %d deferred for space; first view complete %s; %d sections with faces, %d faces (%.0f %% of the buffer); drawn %.0f faces per frame; still dirty %d",
                STATES.size(), QUADS.size(), meshed, batches, meshed / secs, uploads, waitFrames, fullStalls,
                firstViewAt == 0 ? "not yet" : String.format(Locale.ROOT, "%.2f s after the window's start", (firstViewAt - windowStart) / 1e9),
                RECS.size(), used, 100.0 * used / CAPACITY, drawFrames == 0 ? 0 : (double) drawnFaces / drawFrames, DIRTY.size())
                + String.format(Locale.ROOT, "%n  vanilla compiles taken over %d, left to vanilla (fluids, translucent, unknown) %d; take-over CPU %.3f ms per frame (%.1f us each)",
                        takenOver, leftToVanilla, cpuTakeOver / 1e6 / Math.max(1, cpuFrames), cpuTakeOver / 1e3 / Math.max(1, takenOver + leftToVanilla))
                + String.format(Locale.ROOT, "; translucent pass CPU %.3f ms per frame", cpuTrans / 1e6 / Math.max(1, cpuFrames))
                + String.format(Locale.ROOT, "%n  left to vanilla for mods: model data of block entities %d, mods' own section geometry %d, models that read the world %d (%d states); sections vanilla draws now %d",
                        leftForModelData, leftForGeometry, leftForMods, LIB.stream().filter(e -> (e[3] & VANILLA_MODEL) != 0).count(), VANILLA_OWNED.size())
                + (LOD > 0 ? String.format(Locale.ROOT, "%n  far LOD beyond %d chunks (cells of 4 beyond %d): %d sections meshed as LOD (%d of them cells of 4), %d meshed again for a level change, %d states LOD-solid",
                        LOD, LOD2, lodSections, lod4Sections, lodRemesh, PROPS.stream().filter(pr -> (pr[0] & 4096) != 0).count()) : "")
                + String.format(Locale.ROOT, "%n  buffer writes / reads: %d calls, %.3f ms per frame (%.1f us each); tint arena %.0f %% used, %d allocations failed; tints kept for unchanged contents %d",
                        glCalls, cpuGl / 1e6 / Math.max(1, cpuFrames), cpuGl / 1e3 / Math.max(1, glCalls), 100.0 * tintUsed() / TINT_CAPACITY, tintAllocFails, tintsKept)
                + String.format(Locale.ROOT, "%n  library: %d new states, %.3f ms each (%.3f ms per frame); %d uploads, %.3f ms each (%.3f ms per frame)",
                        addedStates, cpuAddState / 1e6 / Math.max(1, addedStates), cpuAddState / 1e6 / Math.max(1, cpuFrames),
                        libUploads, cpuLibUpload / 1e6 / Math.max(1, libUploads), cpuLibUpload / 1e6 / Math.max(1, cpuFrames))
                + (OCCLUSION ? String.format(Locale.ROOT, "%n  occlusion (sampled every 120 frames): %d of %d sections hidden, %.1f %% of their opaque faces", occHidden, occTested, 100.0 * occFacesHidden / Math.max(1, occFacesTested)) : "")
                + (VERIFY_VIS ? String.format(Locale.ROOT, "; visibility checked against VisGraph: %d, mismatches %d", visChecks.get(), visMismatches.get()) : "")
                + (VERIFY_PAIRS ? String.format(Locale.ROOT, "; pairs checked against Block.shouldRenderFace: %d, mismatches %d", pairChecks, pairMismatches) : "")
                + (PAIR_CLASSES ? String.format(Locale.ROOT, "; pair classes: %d classes (%d with own face logic) for %d states, class pairs reused %d, computed %d, asked directly %d", CLASS_KEYS.size(), CUSTOM_CLASS.cardinality(), STATES.size(), classPairHits, classPairMisses, classPairDirect) : "")
                + String.format(Locale.ROOT, "; a new state: pairs %.3f, model entry %.3f, fluid properties %.3f ms",
                        addPairsNs / 1e6 / Math.max(1, addedStates), addEntryNs / 1e6 / Math.max(1, addedStates), addFluidNs / 1e6 / Math.max(1, addedStates))
                + String.format(Locale.ROOT, "%n  translucent: %.0f sections, %.0f faces sorted per frame (sections over 4096 faces left unsorted: %d; left to vanilla's compiled translucent %.1f per frame); resorted in %d of %d frames",
                        transSections / (double) Math.max(1, transFrames), transFaces / (double) Math.max(1, transFrames), transUnsorted, transVanilla / (double) Math.max(1, transFrames), transResorts, transFrames)
                + String.format(Locale.ROOT, "%n  point updates: %d cells written, %d to whole-section uploads (tinted or unknown); light-only uploads %d", pointWrites, pointFallbacks, lightUploads)
                + String.format(Locale.ROOT, "%n  per frame: CPU moved %.3f, batches %.3f, picking + uploads %.3f (of it uploads %.3f, of those tints %.3f), draw %.3f ms; GPU busy: mesh %.3f, unpack %.3f, draw %.3f ms (timestamps around the parts, GPU waits included: %.3f / %.3f)",
                cpuMoved / 1e6 / Math.max(1, cpuFrames), cpuProgress / 1e6 / Math.max(1, cpuFrames), cpuBegin / 1e6 / Math.max(1, cpuFrames),
                cpuUploads / 1e6 / Math.max(1, cpuFrames), cpuTints / 1e6 / Math.max(1, cpuFrames), cpuDraw / 1e6 / Math.max(1, cpuFrames),
                gpuBusyMesh / 1e6 / Math.max(1, cpuFrames), gpuBusyUnpack / 1e6 / Math.max(1, cpuFrames), gpuBusyDraw / 1e6 / Math.max(1, cpuFrames),
                gpuMesh / 1e6 / Math.max(1, gpuFrames), gpuDraw / 1e6 / Math.max(1, gpuFrames)) + caves;
    }

    // ---- setup ----

    private static void setup() throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        d = mc.options.getEffectiveRenderDistance();
        dm = d + 1;
        chunks = 2 * dm + 1;
        nx = chunks * 16;
        ny = level.getHeight();
        minY = level.getMinBuildHeight();
        secY = level.getSectionsCount();
        pcData = net.minecraft.world.level.chunk.PalettedContainer.class.getDeclaredField(dev.lull.Names.field("net/minecraft/world/level/chunk/PalettedContainer", "data"));
        pcData.setAccessible(true);
        dlData = DataLayer.class.getDeclaredField(dev.lull.Names.field("net/minecraft/world/level/chunk/DataLayer", "data"));
        dlData.setAccessible(true);
        dlDefault = DataLayer.class.getDeclaredField(dev.lull.Names.field("net/minecraft/world/level/chunk/DataLayer", "defaultValue"));
        dlDefault.setAccessible(true);
        wlist = net.minecraft.client.resources.model.WeightedBakedModel.class.getDeclaredField(dev.lull.Names.field("net/minecraft/client/resources/model/WeightedBakedModel", "list"));
        wlist.setAccessible(true);
        mselectors = net.minecraft.client.resources.model.MultiPartBakedModel.class.getDeclaredField(dev.lull.Names.field("net/minecraft/client/resources/model/MultiPartBakedModel", "selectors"));
        mselectors.setAccessible(true);
        STATES.clear();
        STATE_CLASS.clear();
        CLASS_KEYS.clear();
        CUSTOM_CLASS.clear();
        CLASS_PAIRS.clear();
        LOCAL.clear();
        LIB.clear();
        ENTRIES.clear();
        VARIANTS.clear();
        QUADS.clear();
        OFFSETS.clear();
        PROPS.clear();
        pairs = new int[(NMAX * NMAX * 6 + 31) / 32];
        java.util.Arrays.fill(LIB_SENT, 0);
        PAIR_DIRTY.clear();
        PAIR_DIRTY.set(0, pairs.length);
        STATES.add(Blocks.AIR.defaultBlockState());
        LOCAL.put(Blocks.AIR.defaultBlockState(), 0);
        PROPS.add(new int[] {0, Float.floatToRawIntBits(1.0f)});
        LIB.add(new int[] {0, 0, 0, 0});
        fluidFamilies(level);
        FPROPS.clear();
        FPROPS.add(fluidProps(Blocks.AIR.defaultBlockState(), level));
        pairBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, pairBuf);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, 4L * pairs.length, GL44C.GL_DYNAMIC_STORAGE_BIT);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        IntBuffer ao = VoxelCompute.aoTables();
        aoBuf = VoxelCompute.storage(ao);
        MemoryUtil.memFree(ao);
        gridTex = tex3d(GL30C.GL_R16UI);
        lightTex = tex3d(GL30C.GL_R8UI);
        FREE.clear();
        FREE.put(0, CAPACITY);
        RECS.clear();
        SLOT_REC = new Rec[chunks * chunks * secY];
        minSec = level.getMinSection();
        RESIDENT.clear();
        DIRTY.clear();
        STALE.clear();
        STALE_LIGHT.clear();
        cause = CAUSE_NONE;
        camCx = Integer.MIN_VALUE;
        cpuMoved = cpuProgress = cpuBegin = cpuDraw = cpuFrames = gpuMesh = gpuDraw = gpuFrames = cpuUploads = cpuTints = 0;
        gpuBusyMesh = gpuBusyUnpack = gpuBusyDraw = 0;
        java.util.Arrays.fill(EQ_KIND, 0);
        FLIGHTS.clear();
        java.util.Arrays.fill(SLOT_BUSY, false);
        outBuf = dynamicBuffer(16L + 4L * FACE_UINTS * CAPACITY, new int[] {0, CAPACITY, 0, 0});
        faceSlotBuf = dynamicBuffer(4L * CAPACITY, null);
        countsBufs = new int[FLIGHT];
        basesBufs = new int[FLIGHT];
        sectionBufs = new int[FLIGHT];
        for (int i = 0; i < FLIGHT; i++) {
            countsBufs[i] = dynamicBuffer(4L * BUCKETS * BUDGET, null);
            basesBufs[i] = dynamicBuffer(4L * BUCKETS * BUDGET, null);
            sectionBufs[i] = dynamicBuffer(16L * BUDGET, null);
        }
        slotBuf = dynamicBuffer(16L * chunks * chunks * secY, null);
        if (GPU_CMDS) gpuCommandsInit();
        sectionTintBuf = dynamicBuffer(16L * chunks * chunks * secY, null);
        tintBuf = dynamicBuffer(4L * TINT_CAPACITY, null);
        TINT_FREE.clear();
        TINT_FREE.put(0, TINT_CAPACITY);
        TINTS.clear();
        TINT_GEN.clear();
        TINT_HASH.clear();
        TINT_DONE.clear();
        TINT_PENDING.clear();
        BAKE_RANGES.clear();
        NEEDS_VANILLA.clear();
        cpuGl = glCalls = tintAllocFails = tintsKept = 0;
        TAKE_GEN.clear();
        TAKE_DONE.clear();
        takenOver = leftToVanilla = cpuTakeOver = cpuTrans = cpuAddState = addedStates = cpuLibUpload = libUploads = addMark = libMark = 0;
        lastAfter = 0;
        spikesLogged = 0;
        pointWrites = pointFallbacks = lightUploads = 0;
        transFaces = transSections = transFrames = transUnsorted = transVanilla = transResorts = 0;
        sortedGen = -1;
        // the atlas sampled as vanilla's render types do: solid and cutout_mipped with mipmaps, cutout without
        samplerMip = GlTrack.sampler(org.lwjgl.opengl.GL33C.glGenSamplers());
        org.lwjgl.opengl.GL33C.glSamplerParameteri(samplerMip, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_LINEAR);
        org.lwjgl.opengl.GL33C.glSamplerParameteri(samplerMip, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        samplerFlat = GlTrack.sampler(org.lwjgl.opengl.GL33C.glGenSamplers());
        org.lwjgl.opengl.GL33C.glSamplerParameteri(samplerFlat, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        org.lwjgl.opengl.GL33C.glSamplerParameteri(samplerFlat, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        for (int i = 0; i < 3; i++) {
            CMD_BUFS[i] = GlTrack.buf(GL15C.glGenBuffers());
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, CMD_BUFS[i]);
            CMD_CAPS[i] = 20L * 4096;
            GL44C.glBufferStorage(GL40C.GL_DRAW_INDIRECT_BUFFER, CMD_CAPS[i], GL44C.GL_DYNAMIC_STORAGE_BIT);
        }
        cmdBuf = CMD_BUFS[0];
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        compile();
        packetBuf = dynamicBuffer(4L * PK_CAPACITY, null);
        packetIndexBuf = dynamicBuffer(4L * 256, null);
        unpackProg = VoxelCompute.program(UNPACK, null, null);
        if (unpackProg == 0) throw new IllegalStateException("unpack shader failed (see the log)");
        uploadLibrary();
        VoxelLod.cutAll = VoxelLod.cutting = true;
        VANILLA_OWNED.clear();
        ChunkMultiDraw.invalidateLayerCaches();
        LOG.info("[sleep/voxel] stream: grid {}x{}x{} ({} chunks a side), face buffer {} faces", nx, ny, nx, chunks, CAPACITY);
        dirtyAllVanilla();
    }

    private static int dynamicBuffer(long bytes, int[] head) {
        int b = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, b);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, bytes, GL44C.GL_DYNAMIC_STORAGE_BIT);
        if (head != null) timedSub(GL43C.GL_SHADER_STORAGE_BUFFER, 0, head);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        return b;
    }

    /**
     * Per section (one work group): its translucent faces back to front. Key: the squared distance from the camera to
     * the quad's centre (float bits, positive: ordered as uints) with its low 12 bits replaced by the inverted index,
     * so equal distances keep the faces' order as vanilla's stable sort does; a bitonic sort, descending, in shared memory.
     */
    private static final String SORT = """
                #version 430
                layout(local_size_x = 256) in;
                layout(std430, binding = 13) buffer Out { uint counter; uint capacity; uint pad0; uint pad1; uint faces[]; };
                layout(std430, binding = 14) buffer Quads { float quads[]; };
                layout(std430, binding = 5) buffer Offsets { float offs[]; };
                layout(std430, binding = 2) buffer Slots { ivec4 slots[]; };
                layout(std430, binding = 8) buffer List { uvec4 list[]; };
                layout(std430, binding = 9) buffer Sorted { uint sorted[]; };
                uniform ivec3 CamBlock;
                uniform vec3 CamFrac;
                shared uint keys[4096];
                %FLUID_CORNER%
                void main() {
                    uvec4 e = list[gl_WorkGroupID.x];
                    uint n = e.y, inv = gl_LocalInvocationID.x;
                    if (n > 4096u) {
                        for (uint i = inv; i < n; i += 256u) sorted[e.z + i] = e.x + i;
                        return;
                    }
                    uint m = 1u;
                    while (m < n) m <<= 1u;
                    for (uint i = inv; i < m; i += 256u) {
                        uint key = 0u;
                        if (i < n) {
                            uint b = (e.x + i) * 16u;
                            uint pk = faces[b];
                            ivec3 origin = slots[pk >> 12u].xyz;
                            vec3 local = vec3(float(pk & 15u), float((pk >> 4u) & 15u), float((pk >> 8u) & 15u));
                            uint w1 = faces[b + 1u];
                            uint q = (w1 & 0xFFFFu) * 40u;
                            vec3 off = vec3(0.0), c;
                            if ((w1 & 0xFFFFu) == 0xFFFFu) c = (fluidCorner(b, w1, 0) + fluidCorner(b, w1, 2)) * 0.5;
                            else {
                                int ot = int(quads[q + 37u]);
                                if (ot > 0) {
                                    uint nib = w1 >> 16u, tb = uint(ot - 1) * 48u;
                                    off = vec3(offs[tb + (nib & 15u)], offs[tb + 16u + ((nib >> 4u) & 15u)], offs[tb + 32u + ((nib >> 8u) & 15u)]);
                                }
                                c = (vec3(quads[q], quads[q + 1u], quads[q + 2u]) + vec3(quads[q + 6u], quads[q + 7u], quads[q + 8u])) * 0.5;
                            }
                            vec3 rel = vec3(origin - CamBlock) + local + off + c - CamFrac;
                            key = (floatBitsToUint(dot(rel, rel)) & 0xFFFFF000u) | (4095u - i);
                        }
                        keys[i] = key;
                    }
                    barrier();
                    for (uint k = 2u; k <= m; k <<= 1u)
                        for (uint j = k >> 1u; j > 0u; j >>= 1u) {
                            for (uint i = inv; i < m; i += 256u) {
                                uint l = i ^ j;
                                if (l > i) {
                                    uint a = keys[i], bb = keys[l];
                                    if (((i & k) == 0u) ? (a < bb) : (a > bb)) { keys[i] = bb; keys[l] = a; }
                                }
                            }
                            barrier();
                        }
                    for (uint i = inv; i < n; i += 256u) sorted[e.z + i] = e.x + (4095u - (keys[i] & 4095u));
                }
                """;

    private static final String FLUID_CORNER = """
                // a fluid face's corner in its cell (LiquidBlockRenderer's vertex order); w1: 0xFFFF | kind << 16 | dir << 20 | family << 24 | flags << 28
                vec3 fluidCorner(uint b, uint w1, int c) {
                    uint kind = (w1 >> 16u) & 15u, dir = (w1 >> 20u) & 15u;
                    float h0 = uintBitsToFloat(faces[b + 2u]), h1 = uintBitsToFloat(faces[b + 3u]), h2 = uintBitsToFloat(faces[b + 4u]), h3 = uintBitsToFloat(faces[b + 5u]);
                    if (kind == 0u) return c == 0 ? vec3(0.0, h0, 0.0) : c == 1 ? vec3(0.0, h1, 1.0) : c == 2 ? vec3(1.0, h2, 1.0) : vec3(1.0, h3, 0.0);
                    if (kind == 1u) return c == 0 ? vec3(0.0, h0, 0.0) : c == 1 ? vec3(1.0, h3, 0.0) : c == 2 ? vec3(1.0, h2, 1.0) : vec3(0.0, h1, 1.0);
                    if (kind == 2u) return c == 0 ? vec3(0.0, h0, 1.0) : c == 1 ? vec3(0.0, h0, 0.0) : c == 2 ? vec3(1.0, h0, 0.0) : vec3(1.0, h0, 1.0);
                    // sides: h0 = f44, h1 = f45, h2 = f16
                    float x47, x51, z49, z52;
                    if (dir == 2u) { x47 = 0.0; x51 = 1.0; z49 = 0.001; z52 = 0.001; }
                    else if (dir == 3u) { x47 = 1.0; x51 = 0.0; z49 = 1.0 - 0.001; z52 = 1.0 - 0.001; }
                    else if (dir == 4u) { x47 = 0.001; x51 = 0.001; z49 = 1.0; z52 = 0.0; }
                    else { x47 = 1.0 - 0.001; x51 = 1.0 - 0.001; z49 = 0.0; z52 = 1.0; }
                    if (kind == 3u) return c == 0 ? vec3(x47, h0, z49) : c == 1 ? vec3(x51, h1, z52) : c == 2 ? vec3(x51, h2, z52) : vec3(x47, h2, z49);
                    return c == 0 ? vec3(x47, h2, z49) : c == 1 ? vec3(x51, h2, z52) : c == 2 ? vec3(x51, h1, z52) : vec3(x47, h0, z49);
                }
                """;

    // ---- the mesh kernel's variants and a benchmark of them ----

    /** -Dlab.voxel.stream.meshKernel: 0 the counts-array kernel, 1 (default) the atomic one (N100: 138 vs 229 us per section), 2 atomic + shared neighbourhood (no gain) */
    private static final int meshVariant = Integer.getInteger("lab.voxel.stream.meshKernel", VERTS ? 2 : 1);   // with vertices the shared neighbourhood wins (N100: mesh GPU 1.39 -> 1.08 ms/frame)
    private static final int[] MESH_PROG = new int[5];
    private static final int[][] MESH_U = new int[5][];
    /** -Dlab.voxel.stream.meshBench: once the view is complete, both kernels timed over built sections in batches of 12..256 */
    private static final boolean MESH_BENCH = Boolean.getBoolean("lab.voxel.stream.meshBench");
    private static boolean benchDone;

    /**
     * The kernel with 24 shared atomic counters instead of a 24 x 256 counts array (24 KiB of shared memory, fewer
     * work groups resident); the write pass takes its place in a bucket by an atomic add instead of counting the
     * section again. Faces inside a bucket come in any order (the opaque buckets draw the same; translucent is sorted).
     */
    /** light() writing its corners through the runtime remap indices (br[r0] = ...), as before the select form */
    static String scatterVariant(String src) {
        String a = "for (int c = 0; c < 4; c++) {\n                            br[c] = c == r0 ? b0 : c == r1 ? b1 : c == r2 ? b2 : b3;\n                            lm[c] = c == r0 ? m0 : c == r1 ? m1 : c == r2 ? m2 : m3;\n                        }";
        String b = "for (int c = 0; c < 4; c++) {\n                            br[c] = c == r0 ? f29 : c == r1 ? f31 : c == r2 ? f32 : f33;\n                            lm[c] = c == r0 ? m0 : c == r1 ? m1 : c == r2 ? m2 : m3;\n                        }";
        if (!src.contains(a) || !src.contains(b)) throw new IllegalStateException("scatter variant: the select form not found");
        return src.replace(a, "br[r0] = b0; br[r1] = b1; br[r2] = b2; br[r3] = b3; lm[r0] = m0; lm[r1] = m1; lm[r2] = m2; lm[r3] = m3;")
                .replace(b, "br[r0] = f29; br[r1] = f31; br[r2] = f32; br[r3] = f33; lm[r0] = m0; lm[r1] = m1; lm[r2] = m2; lm[r3] = m3;");
    }

    /** -Dlab.voxel.stream.lod=N: sections farther than N chunks (Chebyshev, from the camera's chunk) meshed as 2 x 2 x 2 cells - the topmost
     * full cube of a cell drawn twice as large, faces only toward open cells, flat light; fluids at full resolution. Pictures differ at distance. */
    static final int LOD = Integer.getInteger("lab.voxel.stream.lod", Boolean.getBoolean("lab.voxel.stream.lodNear") ? 4 : 6);
    /** -Dlab.voxel.stream.lod2=N (with .lod, N > lod): sections farther than N chunks meshed as 4 x 4 x 4 cells (a second, coarser level) */
    static final int LOD2 = LOD > 0 ? Integer.getInteger("lab.voxel.stream.lod2", Boolean.getBoolean("lab.voxel.stream.lodNear") ? 8 : 10) : 0;

    /** the LOD level of a section dd chunks (Chebyshev) from the camera: 0 full detail, 1 cells of 2, 2 cells of 4 */
    static int lodLevel(int dd) {
        return LOD2 > LOD && dd > LOD2 ? 2 : LOD > 0 && dd > LOD ? 1 : 0;
    }
    private static final String LOD_GLSL = """
                // ---- the far LOD (-Dlab.voxel.stream.lod, .lod2): cells of S x S x S (S = 2, 4), one representative block each ----
                bool lodSolid(uint s) { return (props[s].x & 4096u) != 0u; }
                // the section's cells with a LOD-solid block, one bit each (up to 512 cells), filled by the whole group first
                shared uint lodBits[16];
                bool lodBit(uint i) { return (lodBits[i >> 5u] & (1u << (i & 31u))) != 0u; }
                bool lodCell(ivec3 c, int S) {
                    for (int k = 0; k < S * S * S; k++) if (lodSolid(stateAt(c + ivec3(k % S, (k / S) % S, k / (S * S))))) return true;
                    return false;
                }
                // across a section's border (the next section may be at full detail): open unless the 4 blocks right behind the face are solid
                bool lodLayerOpen(ivec3 c, int cull, int S) {
                    ivec3 n = OFF[cull];
                    ivec3 b = c + max(n, ivec3(0)) * S + min(n, ivec3(0));
                    ivec3 t1 = n.x != 0 ? ivec3(0, 1, 0) : ivec3(1, 0, 0), t2 = n.z != 0 ? ivec3(0, 1, 0) : ivec3(0, 0, 1);
                    for (int k = 0; k < S * S; k++) if (!lodSolid(stateAt(b + t1 * (k % S) + t2 * (k / S)))) return true;
                    return false;
                }
                // the topmost LOD-solid block of the cell (the surface's block: grass over dirt)
                uint lodRep(ivec3 c, out ivec3 rp, int S) {
                    rp = c;
                    for (int y = S - 1; y >= 0; y--)
                        for (int k = 0; k < S * S; k++) {
                            ivec3 p = c + ivec3(k % S, y, k / S);
                            uint s = stateAt(p);
                            if (lodSolid(s)) { rp = p; return s; }
                        }
                    return 0u;
                }
                // the light in front of a face: the brightest of the 4 blocks it covers (sky and block light apart)
                int lodLight(ivec3 c, int cull, int S) {
                    ivec3 n = OFF[cull];
                    ivec3 b = c + max(n, ivec3(0)) * S + min(n, ivec3(0));
                    ivec3 t1 = n.x != 0 ? ivec3(0, 1, 0) : ivec3(1, 0, 0), t2 = n.z != 0 ? ivec3(0, 1, 0) : ivec3(0, 0, 1);
                    int sky = 0, blk = 0;
                    for (int k = 0; k < S * S; k++) {
                        ivec3 p = b + t1 * (k % S) + t2 * (k / S);
                        int l = lightColor(stateAt(p), p);
                        sky = max(sky, (l >> 20) & 15);
                        blk = max(blk, (l >> 4) & 15);
                    }
                    return sky << 20 | blk << 4;
                }
                void lodVerts(uint at, ivec3 c, ivec3 rp, uint q, uint lmv, int S) {
                    uint qb = q * 40u;
                    int ti = int(quads[qb + 20u]);
                    uint code = (ti >= 0 && ti < 4) ? uint(ti + 1) : 0u;
                    uint ct = cellOf(rp) | code << 12u;
                    vec3 base = vec3(c & 15);
                    float br = shadeFor(int(quads[qb + 21u]), (uint(quads[qb + 22u]) & 1u) != 0u);
                    for (uint k = 0u; k < 4u; k++)
                        putVert(at, k, base + float(S) * vec3(quads[qb + k * 3u], quads[qb + k * 3u + 1u], quads[qb + k * 3u + 2u]),
                                vec2(quads[qb + 12u + k * 2u], quads[qb + 13u + k * 2u]), br, lmv & 255u, (lmv >> 16u) & 255u, ct);
                }
                void lodMain(ivec3 o, uint inv, uint sec, int S) {
                    uint mine[24];
                    for (uint l = 0u; l < 24u; l++) mine[l] = 0u;
                    uint n = uint(16 / S);
                    if (inv < 16u) lodBits[inv] = 0u;
                    barrier();
                    for (uint b = inv; b < 4096u; b += 256u) {   // every block once, by the whole group: its cell's bit
                        ivec3 l = ivec3(int(b & 15u), int(b >> 8u), int((b >> 4u) & 15u));
                        if (lodSolid(stateAt(o + l))) {
                            uint ci = uint(l.x / S) + n * uint(l.z / S) + n * n * uint(l.y / S);
                            atomicOr(lodBits[ci >> 5u], 1u << (ci & 31u));
                        }
                    }
                    barrier();
                    for (uint i = inv; i < n * n * n; i += 256u) {
                        if (!lodBit(i)) continue;   // nothing LOD-solid in the cell
                        ivec3 c = o + S * ivec3(int(i % n), int(i / (n * n)), int((i / n) % n));
                        ivec3 rp;
                        uint rs = lodRep(c, rp, S);
                        if (rs == 0u) continue;
                        uint nib;
                        Seeds sd = seeds(rp, rs, nib);
                        uvec4 le = lib[rs];
                        uint per = 0u;   // quads per side, 2 bits each: a side drawn by one quad alone may go opaque (leaves), not one with an overlay (grass)
                        for (uint ei = le.x; ei < le.x + le.y; ei++) {
                            uvec2 e = pickEntry(ei, sd);
                            for (uint k = 0u; k < e.y; k++) {
                                int cl = int(quads[(e.x + k) * 40u + 24u]);
                                if (cl <= 5 && ((per >> uint(cl * 2)) & 3u) < 3u) per += 1u << uint(cl * 2);
                            }
                        }
                        for (uint ei = le.x; ei < le.x + le.y; ei++) {
                            uvec2 e = pickEntry(ei, sd);
                            for (uint k = 0u; k < e.y; k++) {
                                uint q = e.x + k;
                                int cull = int(quads[q * 40u + 24u]);
                                if (cull > 5) continue;   // the cube's faces only
                                ivec3 nc = c + OFF[cull] * S;
                                bool inSec = all(greaterThanEqual(nc, o)) && all(lessThan(nc, o + 16));
                                ivec3 nl = (nc - o) / S;
                                if (inSec ? lodBit(uint(nl.x) + n * uint(nl.z) + n * n * uint(nl.y)) : !lodLayerOpen(c, cull, S)) continue;   // where the next cell is open
                                uint bk = bucketOf(q);
                                if (bk >= 18u) continue;
                                if ((props[rs].x & 8192u) != 0u && ((per >> uint(cull * 2)) & 3u) == 1u) bk = bk % 6u;   // only-cutout states (leaves) drawn opaque at distance: no sky through the holes (vanilla's fast leaves)
                                if (Pass == 0) { mine[bk]++; continue; }
                                uint at = bases[sec * 24u + bk] + atomicAdd(acnt[bk], 1u);
                                if (at < capacity && at < bases[sec * 24u + bk] + counts4[sec * 24u + bk]) lodVerts(at, c, rp, q, uint(lodLight(c, cull, S)), S);
                            }
                        }
                    }
                    #ifdef FLUIDS
                    for (uint i = inv; i < 4096u; i += 256u) {   // the fluids at full resolution
                        ivec3 p = o + ivec3(i & 15u, i >> 8u, (i >> 4u) & 15u);
                        uint s = stateAt(p);
                        if (s != 0u && fam(s) != 0u) fluidFaces(p, s, sec, mine, Pass != 0);
                    }
                    #endif
                    if (Pass == 0) {
                        for (uint l = 0u; l < 24u; l++) if (mine[l] != 0u) atomicAdd(acnt[l], mine[l]);
                        barrier();
                        if (inv < 24u) counts4[sec * 24u + inv] = acnt[inv];
                    }
                }

""";
    public static long lodSections, lod4Sections, lodRemesh;
    /** -Dlab.voxel.stream.mergeStats (a measurement): counters in the mesh kernel - faces, and those a merge could take */
    static final boolean MERGE_STATS = Boolean.getBoolean("lab.voxel.stream.mergeStats");
    private static int mergeStatsBuf;

    static String atomicVariant(String src) {
        String[][] r = {
                {"shared uint counts[6144];", "shared uint acnt[24];"},
                {"uint inv = gl_LocalInvocationIndex, sec = gl_WorkGroupID.x;", "uint inv = gl_LocalInvocationIndex, sec = gl_WorkGroupID.x;\n    if (inv < 24u) acnt[inv] = 0u;\n    barrier();"},
                {"uint at = cnt[bk]++;", "uint at = write ? bases[sec * 24u + bk] + atomicAdd(acnt[bk], 1u) : cnt[bk]++;"},
                {"uint bk = bucketOf(q), at = w[bk]++;", "uint bk = bucketOf(q), at = bases[sec * 24u + bk] + atomicAdd(acnt[bk], 1u);"},
        };
        for (String[] x : r) {
            if (!src.contains(x[0])) throw new IllegalStateException("atomic variant: not found: " + x[0]);
            src = src.replace(x[0], x[1]);
        }
        String loop = "for (uint i = inv; i < 4096u; i += 256u) {";   // the first one is the count pass's: not run by the write pass
        int f = src.indexOf(loop);
        if (f < 0) throw new IllegalStateException("atomic variant: the count loop not found");
        src = src.substring(0, f) + "if (Pass == 0) " + src.substring(f);
        if (LOD > 0 && VERTS) {   // the far LOD (vertices only): its functions before main, a branch at the start of main (after the shared counters are cleared)
            int m = src.indexOf("void main() {");
            if (m < 0 || !src.contains("uint mine[24];")) throw new IllegalStateException("atomic variant: main for the LOD not found");
            src = src.substring(0, m) + LOD_GLSL + src.substring(m);
            int mm = src.indexOf("uint mine[24];", src.indexOf("void main() {", m + LOD_GLSL.length()));   // main's, not lodMain's
            src = src.substring(0, mm) + "if ((uint(sections[gl_WorkGroupID.x].w) & 0x40000000u) != 0u) { lodMain(o, inv, sec, (uint(sections[gl_WorkGroupID.x].w) & 0x20000000u) != 0u ? 4 : 2); return; }\n                    " + src.substring(mm);
        }
        int a = src.indexOf("for (uint l = 0u; l < 24u; l++) counts[l * 256u + inv] = mine[l];");
        int b = src.indexOf("for (uint l = 0u; l < 24u; l++) w[l] = bases[sec * 24u + l] + counts[l * 256u + inv];");
        if (a < 0 || b < 0) throw new IllegalStateException("atomic variant: the counts block not found");
        b = src.indexOf('\n', b);
        return src.substring(0, a) + """
                if (Pass == 0) {
                        for (uint l = 0u; l < 24u; l++) if (mine[l] != 0u) atomicAdd(acnt[l], mine[l]);
                        barrier();
                        if (inv < 24u) counts4[sec * 24u + inv] = acnt[inv];
                        return;
                    }
                    uint w[24];""" + src.substring(b);
    }

    /**
     * On top of the atomic kernel: the section's 18 x 18 x 18 neighbourhood (state | light << 16) loaded into shared
     * memory once per pass; every neighbour the faces, the AO and the fluids read is within one cell of the section.
     */
    static String cachedVariant(String src) {
        String[][] r = {
                {"uint stateAt(ivec3 p) {", "uint stateAtTex(ivec3 p) {"},
                {"uint lightAt(ivec3 p) {", "uint lightAtTex(ivec3 p) {"},
        };
        for (String[] x : r) {
            if (!src.contains(x[0])) throw new IllegalStateException("cached variant: not found: " + x[0]);
            src = src.replace(x[0], x[1]);
        }
        int at = src.indexOf("uint lightAtTex(ivec3 p) {");
        at = src.indexOf('\n', at) + 1;
        src = src.substring(0, at) + """
                shared uint nb[5832];
                ivec3 nbO;
                uint stateAt(ivec3 p) { ivec3 r = p - nbO; if (uint(r.x) < 18u && uint(r.y) < 18u && uint(r.z) < 18u) return nb[(r.y * 18 + r.z) * 18 + r.x] & 0xFFFFu; return stateAtTex(p); }
                uint lightAt(ivec3 p) { ivec3 r = p - nbO; if (uint(r.x) < 18u && uint(r.y) < 18u && uint(r.z) < 18u) return nb[(r.y * 18 + r.z) * 18 + r.x] >> 16u; return lightAtTex(p); }
                """ + src.substring(at);
        String head = "uint inv = gl_LocalInvocationIndex, sec = gl_WorkGroupID.x;";
        if (!src.contains(head)) throw new IllegalStateException("cached variant: main not found");
        src = src.replace(head, head + """

                    nbO = o - ivec3(1);
                    for (uint c = inv; c < 5832u; c += 256u) {
                        ivec3 q = nbO + ivec3(int(c % 18u), int(c / 324u), int((c / 18u) % 18u));
                        nb[c] = stateAtTex(q) | lightAtTex(q) << 16u;
                    }
                    barrier();""");
        return src;
    }

    // ---- -Dlab.voxel.stream.vertexStudy (with .vertices): how many vertices merging faces or hiding covered sections would save ----
    private static final boolean VERTEX_STUDY = Boolean.getBoolean("lab.voxel.stream.vertexStudy");
    private static boolean studyDone;

    private static void vertexStudy(Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        studyDone = true;
        var mc = Minecraft.getInstance();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        var visible = dev.lull.optsupport.VoxelStream.visibleChunks(mc);
        List<Rec> recs = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            var o = visible.get(i).getOrigin();
            Rec r = recAt(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4);
            if (r != null && r.alloc >= 0) recs.add(r);
        }
        // 1. covered sections: each visible section's box against this frame's terrain depth (any sample passing)
        long facesAll = 0, facesHidden = 0;
        int hiddenSections = 0;
        int boxProg = VoxelCompute.program(null, """
                #version 430
                uniform mat4 ViewProj;
                uniform vec3 Lo;
                const vec3 C[8] = vec3[](vec3(0,0,0), vec3(1,0,0), vec3(1,1,0), vec3(0,1,0), vec3(0,0,1), vec3(1,0,1), vec3(1,1,1), vec3(0,1,1));
                const int I[36] = int[](0,1,2, 0,2,3, 4,6,5, 4,7,6, 0,4,5, 0,5,1, 3,2,6, 3,6,7, 0,3,7, 0,7,4, 1,5,6, 1,6,2);
                void main() { gl_Position = ViewProj * vec4(Lo + C[I[gl_VertexID]] * 16.0, 1.0); }
                """, """
                #version 430
                out vec4 f;
                void main() { f = vec4(1.0); }
                """);
        if (boxProg != 0) {
            projectionMatrix.mul(frustumMatrix, VP);
            int[] qs = new int[recs.size()];
            GlTrack.queries(qs);
            GlStateManager._glUseProgram(boxProg);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(boxProg, "ViewProj"), false, VP.get(MAT));
            int uLo = GL20C.glGetUniformLocation(boxProg, "Lo");
            GlStateManager._glBindVertexArray(vao);
            GL11C.glColorMask(false, false, false, false);
            GlStateManager._depthMask(false);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11C.GL_LEQUAL);
            GlStateManager._disableCull();
            for (int i = 0; i < recs.size(); i++) {
                Rec r = recs.get(i);
                GL20C.glUniform3f(uLo, (float) (SectionPos.x(r.key) * 16 - cam.x), (float) (SectionPos.y(r.key) * 16 - cam.y), (float) (SectionPos.z(r.key) * 16 - cam.z));
                GL15C.glBeginQuery(GL33C.GL_ANY_SAMPLES_PASSED, qs[i]);
                GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 36);
                GL15C.glEndQuery(GL33C.GL_ANY_SAMPLES_PASSED);
            }
            GL11C.glColorMask(true, true, true, true);
            GlStateManager._depthMask(true);
            GlStateManager._enableCull();
            GlStateManager._glBindVertexArray(0);
            GlStateManager._glUseProgram(0);
            for (int i = 0; i < recs.size(); i++) {
                Rec r = recs.get(i);
                int opaque = 0;
                for (int k = 0; k < 18; k++) opaque += r.count[k];
                facesAll += opaque;
                if (GL15C.glGetQueryObjecti(qs[i], GL15C.GL_QUERY_RESULT) == 0) {
                    facesHidden += opaque;
                    hiddenSections++;
                }
            }
            GlTrack.delQueries(qs);
        }
        // 2. merging: full-cell square faces with the same plane, sprite (and its turn), light and AO at all four corners
        long faces = 0, squares = 0, rectsStrict = 0, rectsLoose = 0;
        int sampled = 0;
        for (int i = 0; i < recs.size() && sampled < 300; i += Math.max(1, recs.size() / 300)) {
            Rec r = recs.get(i);
            sampled++;
            for (int bk = 0; bk < 18; bk++) {
                int n = r.count[bk];
                if (n == 0) continue;
                int[] v = new int[n * 16];
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, outBuf);
                GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 16L + 64L * r.base[bk], v);
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
                faces += n;
                Map<String, long[]> strict = new HashMap<>(), loose = new HashMap<>();   // group -> 16x16 bit grid (4 longs)
                for (int f = 0; f < n; f++) {
                    int b = f * 16;
                    int[] px = new int[4], py = new int[4], pz = new int[4], pu = new int[4], pv = new int[4];
                    boolean flat = true;
                    int light0 = v[b + 3] & 0xFFFFFF;
                    for (int c = 0; c < 4; c++) {
                        px[c] = v[b + c * 4] & 0xFFFF;
                        py[c] = v[b + c * 4] >>> 16;
                        pz[c] = v[b + c * 4 + 1] & 0xFFFF;
                        pu[c] = v[b + c * 4 + 2] & 0xFFFF;
                        pv[c] = v[b + c * 4 + 2] >>> 16;
                        if ((v[b + c * 4 + 3] & 0xFFFFFF) != light0) flat = false;
                    }
                    int code = (v[b + 1] >>> 16) >>> 12;
                    int[][] P = {px, py, pz};
                    int axis = -1;
                    for (int a = 0; a < 3; a++) if (P[a][0] == P[a][1] && P[a][1] == P[a][2] && P[a][2] == P[a][3]) axis = a;
                    if (axis < 0 || !flat) continue;
                    int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
                    int min1 = Math.min(Math.min(P[a1][0], P[a1][1]), Math.min(P[a1][2], P[a1][3])), max1 = Math.max(Math.max(P[a1][0], P[a1][1]), Math.max(P[a1][2], P[a1][3]));
                    int min2 = Math.min(Math.min(P[a2][0], P[a2][1]), Math.min(P[a2][2], P[a2][3])), max2 = Math.max(Math.max(P[a2][0], P[a2][1]), Math.max(P[a2][2], P[a2][3]));
                    if (max1 - min1 != 2048 || max2 - min2 != 2048 || min1 % 2048 != 0 || min2 % 2048 != 0) continue;
                    squares++;
                    int c1 = min1 / 2048 - 4, c2 = min2 / 2048 - 4;   // (local + 8) * 2048: cell = value / 2048 - 8 ... offset by the +8 bias
                    c1 = min1 / 2048 - 8;
                    c2 = min2 / 2048 - 8;
                    if (c1 < 0 || c1 > 15 || c2 < 0 || c2 > 15) continue;
                    int umin = Math.min(Math.min(pu[0], pu[1]), Math.min(pu[2], pu[3])), vmin = Math.min(Math.min(pv[0], pv[1]), Math.min(pv[2], pv[3]));
                    // the sprite and its turn: corner 0's uv relative to the rect, with the corner's position in the cell
                    String turn = (pu[0] - umin) + "," + (pv[0] - vmin) + "," + (P[a1][0] - min1) + "," + (P[a2][0] - min2);
                    String g = axis + ":" + P[axis][0] + ":" + umin + ":" + vmin + ":" + turn + ":" + light0 + ":" + bk;
                    long[] grid = loose.computeIfAbsent(g, k -> new long[4]);
                    grid[c2 >> 2] |= 1L << ((c2 & 3) * 16 + c1);
                    if (code == 0) strict.computeIfAbsent(g, k -> new long[4])[c2 >> 2] |= 1L << ((c2 & 3) * 16 + c1);
                    else rectsStrict++;   // a tinted face kept alone (its neighbours' colours unknown here)
                }
                for (long[] grid : loose.values()) rectsLoose += greedyRects(grid);
                for (long[] grid : strict.values()) rectsStrict += greedyRects(grid);
            }
        }
        long nonSquare = faces - squares;
        LOG.info(String.format(Locale.ROOT,
                "[sleep/voxel] vertex study: %d visible sections with faces, %d opaque faces; covered by the terrain in front: %d sections, %d faces (%.1f %%)%n"
                        + "  merging, %d sections sampled: %d faces, %d full-cell squares with flat light; after greedy merging %d (tinted alone) / %d (tinted merged too) faces -> %.1f %% / %.1f %% of the faces",
                recs.size(), facesAll, hiddenSections, facesHidden, 100.0 * facesHidden / Math.max(1, facesAll),
                sampled, faces, squares, nonSquare + rectsStrict, nonSquare + rectsLoose,
                100.0 * (nonSquare + rectsStrict) / Math.max(1, faces), 100.0 * (nonSquare + rectsLoose) / Math.max(1, faces)));
    }

    /** greedy rectangles over a 16x16 grid (4 longs of 4 rows each): rows extended right, then down while the same span */
    private static int greedyRects(long[] grid) {
        boolean[][] g = new boolean[16][16];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) g[y][x] = (grid[y >> 2] >>> ((y & 3) * 16 + x) & 1L) != 0;
        int rects = 0;
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                if (!g[y][x]) continue;
                int w = 1;
                while (x + w < 16 && g[y][x + w]) w++;
                int h = 1;
                outer:
                while (y + h < 16) {
                    for (int k = 0; k < w; k++) if (!g[y + h][x + k]) break outer;
                    h++;
                }
                for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) g[yy][xx] = false;
                rects++;
            }
        return rects;
    }


    private static int benchSec, benchBases, benchCounts, benchQuery;

    private static void meshBench() {
        benchDone = true;
        List<Long> keys = new ArrayList<>();
        for (var e : RECS.long2ObjectEntrySet()) if (e.getValue().alloc >= 0 && e.getValue().size > 0 && keys.size() < 256) keys.add(e.getLongKey());
        if (keys.size() < 12) return;
        if (benchSec == 0) {
            benchSec = dynamicBuffer(16L * 256, null);
            benchBases = dynamicBuffer(4L * BUCKETS * 256, null);
            benchCounts = dynamicBuffer(4L * BUCKETS * 256, null);
            int[] bq = new int[1];
            GlTrack.queries(bq);
            benchQuery = bq[0];
        }
        StringBuilder sb = new StringBuilder("[sleep/voxel] mesh bench (GPU us per section, median of 5; faces per section):");
        int[] sizes = {32, 256};
        for (int v = 0; v < 5; v++)
            for (int B : sizes) {
                int n = Math.min(B, keys.size());
                long[] t0 = new long[5], t2 = new long[5];
                long faces = 0;
                for (int rep = 0; rep < 5; rep++) {
                    IntBuffer secs = MemoryUtil.memAllocInt(n * 4);
                    for (int i = 0; i < n; i++) {
                        long k = keys.get((i + rep * 7) % keys.size());
                        secs.put(SectionPos.x(k) * 16).put(SectionPos.y(k) * 16).put(SectionPos.z(k) * 16).put(slotOf(k));
                    }
                    secs.flip();
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, benchSec);
                    GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, secs);
                    MemoryUtil.memFree(secs);
                    GL11C.glFinish();
                    GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, benchQuery);
                    dispatchWith(MESH_PROG[v], MESH_U[v], 0, n, benchSec, benchBases, benchCounts, false);
                    GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                    t0[rep] = GL33C.glGetQueryObjecti64(benchQuery, GL15C.GL_QUERY_RESULT);
                    int[] counts = new int[n * BUCKETS];
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, benchCounts);
                    GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, counts);
                    long total = 0;
                    for (int c : counts) total += c;
                    faces = total;
                    int at = allocate((int) total);   // a scratch block in the free space: the built sections stay as they are
                    if (at < 0) {
                        sb.append(" (no scratch space)");
                        return;
                    }
                    int[] bases = new int[n * BUCKETS];
                    for (int i = 0, run = at; i < n * BUCKETS; i++) {
                        bases[i] = run;
                        run += counts[i];
                    }
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, benchBases);
                    GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, bases);
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
                    GL11C.glFinish();
                    GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, benchQuery);
                    dispatchWith(MESH_PROG[v], MESH_U[v], 2, n, benchSec, benchBases, benchCounts, false);
                    GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                    t2[rep] = GL33C.glGetQueryObjecti64(benchQuery, GL15C.GL_QUERY_RESULT);
                    GL11C.glFinish();
                    release(at, (int) total);
                }
                java.util.Arrays.sort(t0);
                java.util.Arrays.sort(t2);
                sb.append(String.format(Locale.ROOT, "%n  %-8s batch %3d: count pass %7.1f, write pass %7.1f, both %7.1f us per section (%.0f faces per section)",
                        new String[] {"counts", "atomic", "cached", "no-light", "atomic-scatter"}[v], n, t0[2] / 1e3 / n, t2[2] / 1e3 / n, (t0[2] + t2[2]) / 1e3 / n, faces / (double) n));
            }
        LOG.info(sb.toString());
    }

    /** cells are world coordinates; the grid is toroidal: world modulo its size (bias keeps % on non-negative ints) */
    private static final String PRELUDE_STREAM = """
                uniform int MinY;
                bool inside(ivec3 p) { return p.y >= MinY && p.y < MinY + Dims.y; }
                ivec3 gridOf(ivec3 p) { return ivec3((p.x + Dims.x * 262144) % Dims.x, p.y - MinY, (p.z + Dims.z * 262144) % Dims.z); }
                uint stateAt(ivec3 p) { return inside(p) ? texelFetch(Grid, gridOf(p), 0).r : 0u; }
                uint lightAt(ivec3 p) { return inside(p) ? texelFetch(Light, gridOf(p), 0).r : (p.y >= MinY + Dims.y ? 0xF0u : 0u); }
                ivec3 worldOf(ivec3 p) { return p; }
                #define FLUIDS 1
                #define FACE_STRIDE 16u
                uint encodeCell(ivec3 p) { return uint(p.x & 15) | uint(p.y & 15) << 4u | uint(p.z & 15) << 8u | (uint(sections[gl_WorkGroupID.x].w) & 0x1FFFFFFFu) << 12u; }
            """;

    private static void compile() {
        String meshSrc = VoxelCompute.meshSource(VERTS ? PRELUDE_STREAM.replace("#define FLUIDS 1", "#define FLUIDS 1\n#define VERTS 1" + (MERGE_STATS ? "\n#define MERGE_STATS 1" : "")) : PRELUDE_STREAM);
        meshProg = VoxelCompute.program(meshSrc, null, null);
        int meshProgA = VoxelCompute.program(atomicVariant(meshSrc), null, null);
        int meshProgC = VoxelCompute.program(cachedVariant(atomicVariant(meshSrc)), null, null);
        // ablations for the benchmark only (wrong pictures): no light / AO, no position seeds
        int meshProgL = VoxelCompute.program(atomicVariant(meshSrc).replace("light(p, s, q, br, lm);", "for (int v = 0; v < 4; v++) { br[v] = 1.0; lm[v] = 0; }"), null, null);
        int meshProgS = VoxelCompute.program(scatterVariant(atomicVariant(meshSrc)), null, null);   // (was: no seeds) light()'s writes through runtime indices, as before 11-09
        if (meshProgA == 0 || meshProgC == 0 || meshProgL == 0 || meshProgS == 0) throw new IllegalStateException("mesh shader variants failed (see the log)");
        MESH_PROG[3] = meshProgL;
        MESH_PROG[4] = meshProgS;
        MESH_PROG[0] = meshProg;
        MESH_PROG[1] = meshProgA;
        MESH_PROG[2] = meshProgC;
        for (int k = 0; k < 5; k++) {
            GlStateManager._glUseProgram(MESH_PROG[k]);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(MESH_PROG[k], "Grid"), 9);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(MESH_PROG[k], "Light"), 10);
            MESH_U[k] = new int[] {GL20C.glGetUniformLocation(MESH_PROG[k], "Dims"), GL20C.glGetUniformLocation(MESH_PROG[k], "States"),
                    GL20C.glGetUniformLocation(MESH_PROG[k], "MinY"), GL20C.glGetUniformLocation(MESH_PROG[k], "Pass")};
        }
        String vs = """
                #version 430
                layout(std430, binding = 13) buffer Out { uint counter; uint capacity; uint pad0; uint pad1; uint faces[]; };
                layout(std430, binding = 14) buffer Quads { float quads[]; };
                layout(std430, binding = 5) buffer Offsets { float offs[]; };
                layout(std430, binding = 2) buffer Slots { ivec4 slots[]; };
                layout(std430, binding = 3) buffer SectionTints { uvec4 sectionTints[]; };
                layout(std430, binding = 6) buffer Tints { uint tints[]; };
                layout(std430, binding = 4) buffer Families { float fams[]; };
                uniform mat4 ViewProj;
                uniform ivec3 CamBlock;
                uniform vec3 CamFrac;
                out vec2 uv;
                out vec2 lmuv;
                out float bright;
                out float dist;
                flat out vec3 tintRgb;
                flat out float tintA;
                out vec3 vcolor;
                uniform sampler2D Lightmap;
                vec3 vertexColor(float br, vec3 tint, vec2 lm) {   // vanilla: Color * minecraft_sample_lightmap(Sampler2, UV2), in the vertex shader
                    return br * tint * textureLod(Lightmap, clamp(lm / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)), 0.0).rgb;
                }
                %FLUID_CORNER%
                // a cell's colour for tint block k: the section's mask of tinted cells, the rank of the cell in it
                uint tintAt(uvec4 st, uint k, uint pk) {
                    uint c = (pk & 15u) + 16u * ((pk >> 4u) & 15u) + 256u * ((pk >> 8u) & 15u), w = c >> 5u, bit = c & 31u;
                    uint m = tints[st.x + w];
                    if (((m >> bit) & 1u) == 0u) return 0xFFFFFFFFu;
                    return tints[st.x + 256u + k * st.w + tints[st.x + 128u + w] + bitCount(m & ((1u << bit) - 1u))];
                }
                float sprU(uint fb, float f) { return fams[fb] + (fams[fb + 1u] - fams[fb]) * f; }
                float sprV(uint fb, float f) { return fams[fb + 2u] + (fams[fb + 3u] - fams[fb + 2u]) * f; }
                void main() {
                    uint f = uint(gl_VertexID) >> 2u;
                    int c = gl_VertexID & 3;
                    uint b = f * 16u;
                    uint pk = faces[b];
                    ivec3 origin = slots[pk >> 12u].xyz;
                    vec3 local = vec3(float(pk & 15u), float((pk >> 4u) & 15u), float((pk >> 8u) & 15u));
                    uint w1 = faces[b + 1u];
                    tintA = 1.0;
                    if ((w1 & 0xFFFFu) == 0xFFFFu) {   // a fluid face (LiquidBlockRenderer.tesselate)
                        uint kind = (w1 >> 16u) & 15u, dir = (w1 >> 20u) & 15u, fam = (w1 >> 24u) & 15u, fl = w1 >> 28u;
                        uint fb = fam * 16u;
                        vec3 fp = fluidCorner(b, w1, c);
                        if (kind <= 1u) {
                            float f17, f18, f19, f20, f21, f22, f23, f24;
                            if ((fl & 1u) != 0u) {   // still
                                f17 = sprU(fb, 0.0); f21 = sprV(fb, 0.0); f18 = f17; f22 = sprV(fb, 1.0); f19 = sprU(fb, 1.0); f23 = f22; f20 = f19; f24 = f21;
                            } else {
                                float a = uintBitsToFloat(faces[b + 7u]);
                                float f26 = sin(a) * 0.25, f27 = cos(a) * 0.25;
                                uint ff = fb + 4u;
                                f17 = sprU(ff, 0.5 + (-f27 - f26)); f21 = sprV(ff, 0.5 + -f27 + f26);
                                f18 = sprU(ff, 0.5 + -f27 + f26); f22 = sprV(ff, 0.5 + f27 + f26);
                                f19 = sprU(ff, 0.5 + f27 + f26); f23 = sprV(ff, 0.5 + (f27 - f26));
                                f20 = sprU(ff, 0.5 + (f27 - f26)); f24 = sprV(ff, 0.5 + (-f27 - f26));
                            }
                            float f53 = (f17 + f18 + f19 + f20) / 4.0, f54 = (f21 + f22 + f23 + f24) / 4.0, sh = fams[fb + 12u];
                            f17 = f17 + sh * (f53 - f17); f18 = f18 + sh * (f53 - f18); f19 = f19 + sh * (f53 - f19); f20 = f20 + sh * (f53 - f20);
                            f21 = f21 + sh * (f54 - f21); f22 = f22 + sh * (f54 - f22); f23 = f23 + sh * (f54 - f23); f24 = f24 + sh * (f54 - f24);
                            if (kind == 0u) uv = c == 0 ? vec2(f17, f21) : c == 1 ? vec2(f18, f22) : c == 2 ? vec2(f19, f23) : vec2(f20, f24);
                            else uv = c == 0 ? vec2(f17, f21) : c == 1 ? vec2(f20, f24) : c == 2 ? vec2(f19, f23) : vec2(f18, f22);
                            bright = 1.0;
                        } else if (kind == 2u) {
                            uv = c == 0 ? vec2(fams[fb], fams[fb + 3u]) : c == 1 ? vec2(fams[fb], fams[fb + 2u]) : c == 2 ? vec2(fams[fb + 1u], fams[fb + 2u]) : vec2(fams[fb + 1u], fams[fb + 3u]);
                            bright = 0.5;
                        } else {
                            uint sp = (fl & 2u) != 0u ? fb + 8u : fb + 4u;   // the overlay next to glass-like blocks, else the flowing sprite
                            float h44 = uintBitsToFloat(faces[b + 2u]), h45 = uintBitsToFloat(faces[b + 3u]);
                            float f56 = sprU(sp, 0.0), f58 = sprU(sp, 0.5), f59 = sprV(sp, (1.0 - h44) * 0.5), f60 = sprV(sp, (1.0 - h45) * 0.5), f31 = sprV(sp, 0.5);
                            if (kind == 3u) uv = c == 0 ? vec2(f56, f59) : c == 1 ? vec2(f58, f60) : c == 2 ? vec2(f58, f31) : vec2(f56, f31);
                            else uv = c == 0 ? vec2(f56, f31) : c == 1 ? vec2(f58, f31) : c == 2 ? vec2(f58, f60) : vec2(f56, f59);
                            bright = dir < 4u ? 0.8 : 0.6;
                        }
                        uint lmw = faces[b + 6u];
                        lmuv = vec2(float(lmw & 255u), float((lmw >> 8u) & 255u));
                        tintRgb = vec3(1.0);
                        uvec4 st = sectionTints[pk >> 12u];
                        if ((st.z & 0xFFu) != 0xFFu) {
                            uint col = tintAt(st, st.z & 0xFFu, pk);
                            tintRgb = vec3(float((col >> 16u) & 255u), float((col >> 8u) & 255u), float(col & 255u)) / 255.0;
                            tintA = float(col >> 24u) / 255.0;
                        }
                        vec3 frel = vec3(origin - CamBlock) + local + fp - CamFrac;
                        dist = max(length(frel.xz), abs(frel.y));
                        vcolor = vertexColor(bright, tintRgb, lmuv);
                        gl_Position = ViewProj * vec4(frel, 1.0);
                        return;
                    }
                    uint q = (w1 & 0xFFFFu) * 40u;
                    vec3 off = vec3(0.0);
                    int ot = int(quads[q + 37u]);
                    if (ot > 0) {
                        uint nib = w1 >> 16u, tb = uint(ot - 1) * 48u;
                        off = vec3(offs[tb + (nib & 15u)], offs[tb + 16u + ((nib >> 4u) & 15u)], offs[tb + 32u + ((nib >> 8u) & 15u)]);
                    }
                    vec3 p = vec3(quads[q + uint(c * 3)], quads[q + uint(c * 3 + 1)], quads[q + uint(c * 3 + 2)]);
                    uv = vec2(quads[q + 12u + uint(c * 2)], quads[q + 13u + uint(c * 2)]);
                    bright = uintBitsToFloat(faces[b + 2u + uint(c)]);
                    uint lmw = faces[b + 6u + uint(c >> 1)] >> uint((c & 1) * 16);
                    lmuv = vec2(float(lmw & 255u), float((lmw >> 8u) & 255u));
                    int ti = int(quads[q + 20u]);
                    tintRgb = vec3(1.0);
                    if (ti >= 0 && ti < 4) {   // the cell's colour from vanilla's BlockColors, for this tint index
                        uvec4 st = sectionTints[pk >> 12u];
                        uint k = (st.y >> uint(ti * 8)) & 0xFFu;
                        if (k != 0xFFu) {
                            uint col = tintAt(st, k, pk);
                            tintRgb = vec3(float((col >> 16u) & 255u), float((col >> 8u) & 255u), float(col & 255u)) / 255.0;
                        }
                    }
                    vec3 rel = vec3(origin - CamBlock) + local + off + p - CamFrac;   // integers first: exact far from the origin
                    dist = max(length(rel.xz), abs(rel.y));
                    vcolor = vertexColor(bright, tintRgb, lmuv);
                    gl_Position = ViewProj * vec4(rel, 1.0);
                }
                """;
        String fsHead = """
                #version 430
                uniform sampler2D Atlas;
                uniform sampler2D Lightmap;
                uniform vec4 FogColorV;
                uniform vec2 FogRange;
                uniform float AlphaCut;
                in vec2 uv;
                in vec2 lmuv;
                in float bright;
                in float dist;
                flat in vec3 tintRgb;
                flat in float tintA;
                in vec3 vcolor;
                out vec4 fragColor;
                void main() {
                    vec4 c = texture(Atlas, uv);
                """;
        String fsTail = """
                    vec3 col = c.rgb * vcolor;
                    float fv = dist <= FogRange.x ? 0.0 : dist < FogRange.y ? smoothstep(FogRange.x, FogRange.y, dist) : 1.0;
                    fragColor = vec4(mix(col, FogColorV.rgb, fv * FogColorV.a), 1.0);
                }
                """;
        vs = vs.replace("%FLUID_CORNER%", FLUID_CORNER);
        String vsTrans = vs.replace("layout(std430, binding = 6) buffer Tints { uint tints[]; };",
                        "layout(std430, binding = 6) buffer Tints { uint tints[]; };\n                layout(std430, binding = 9) buffer Sorted { uint sorted[]; };")
                .replace("uint f = uint(gl_VertexID) >> 2u;", "uint f = sorted[uint(gl_VertexID) / 6u];")
                .replace("int c = gl_VertexID & 3;", "int c = int[6](0, 1, 2, 0, 2, 3)[uint(gl_VertexID) % 6u];");
        String fsTransTail = """
                    vec3 col = c.rgb * vcolor;
                    float fv = dist <= FogRange.x ? 0.0 : dist < FogRange.y ? smoothstep(FogRange.x, FogRange.y, dist) : 1.0;
                    fragColor = vec4(mix(col, FogColorV.rgb, fv * FogColorV.a), c.a * tintA);
                }
                """;
        drawTrans = VoxelCompute.program(null, vsTrans, fsHead + fsTransTail);
        sortProg = VoxelCompute.program(SORT.replace("%FLUID_CORNER%", FLUID_CORNER), null, null);
        if (drawTrans == 0 || sortProg == 0 || !vsTrans.contains("sorted[uint(gl_VertexID)") || !vsTrans.contains("int[6](")) throw new IllegalStateException("translucent shaders failed (see the log)");
        GlStateManager._glUseProgram(sortProg);
        uSortCamBlock = GL20C.glGetUniformLocation(sortProg, "CamBlock");
        uSortCamFrac = GL20C.glGetUniformLocation(sortProg, "CamFrac");
        GlStateManager._glUseProgram(drawTrans);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(drawTrans, "Atlas"), 0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(drawTrans, "Lightmap"), 2);
        U_TRANS[0] = GL20C.glGetUniformLocation(drawTrans, "ViewProj");
        U_TRANS[1] = GL20C.glGetUniformLocation(drawTrans, "CamBlock");
        U_TRANS[2] = GL20C.glGetUniformLocation(drawTrans, "FogColorV");
        U_TRANS[3] = GL20C.glGetUniformLocation(drawTrans, "FogRange");
        U_TRANS[5] = GL20C.glGetUniformLocation(drawTrans, "CamFrac");
        String vsV = """
                #version 430
                layout(location = 0) in uvec4 aPos;     // x y z (u16), cell | tint code << 12
                layout(location = 1) in vec2 aUv;       // unorm16
                layout(location = 2) in uvec4 aLight;   // brightness, block light, sky light (bytes)
                layout(location = 3) in ivec4 aSlot;    // per instance: the section's origin and slot
                layout(std430, binding = 3) buffer SectionTints { uvec4 sectionTints[]; };
                layout(std430, binding = 6) buffer Tints { uint tints[]; };
                uniform mat4 ViewProj;
                uniform ivec3 CamBlock;
                uniform vec3 CamFrac;
                uniform sampler2D Lightmap;
                out vec2 uv;
                out vec2 lmuv;
                out float bright;
                out float dist;
                flat out vec3 tintRgb;
                flat out float tintA;
                out vec3 vcolor;
                uint tintAt(uvec4 st, uint k, uint c) {
                    uint w = c >> 5u, bit = c & 31u;
                    uint m = tints[st.x + w];
                    if (((m >> bit) & 1u) == 0u) return 0xFFFFFFFFu;
                    return tints[st.x + 256u + k * st.w + tints[st.x + 128u + w] + bitCount(m & ((1u << bit) - 1u))];
                }
                void main() {
                    vec3 local = vec3(aPos.xyz) / 2048.0 - 8.0;
                    uv = aUv;
                    bright = float(aLight.x) / 255.0;
                    lmuv = vec2(float(aLight.y), float(aLight.z));
                    tintRgb = vec3(1.0);
                    tintA = 1.0;
                    uint code = aPos.w >> 12u;
                    if (code != 0u) {   // vanilla's BlockColors (or the fluid's colour) for this cell
                        uvec4 st = sectionTints[uint(aSlot.w)];
                        uint k = code == 5u ? (st.z & 0xFFu) : (st.y >> ((code - 1u) * 8u)) & 0xFFu;
                        if (k != 0xFFu) {
                            uint col = tintAt(st, k, aPos.w & 4095u);
                            tintRgb = vec3(float((col >> 16u) & 255u), float((col >> 8u) & 255u), float(col & 255u)) / 255.0;
                        }
                    }
                    vcolor = bright * tintRgb * textureLod(Lightmap, clamp(lmuv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)), 0.0).rgb;
                    vec3 rel = vec3(aSlot.xyz - CamBlock) + local - CamFrac;
                    dist = max(length(rel.xz), abs(rel.y));
                    gl_Position = ViewProj * vec4(rel, 1.0);
                }
                """;
        if (VERTS && BAKE) {
            String a = "if (code != 0u) {   // vanilla's BlockColors";
            if (!vsV.contains(a)) throw new IllegalStateException("bakeTints: the vertex stage's tint branch not found");
            vsV = vsV.replace(a, "if (aLight.w != 0u) { tintRgb = vec3(float(aPos.w & 255u), float(aPos.w >> 8u), float(aLight.x)) / 255.0; bright = 1.0; }\n"
                    + "                    else " + a);
        }
        if (VERTS) {
            drawSolidV = VoxelCompute.program(null, vsV, fsHead + fsTail);
            drawCutoutV = VoxelCompute.program(null, vsV, fsHead + "        if (c.a < AlphaCut) discard;\n" + fsTail);
            drawFlatV = VoxelCompute.program(null, vsV, fsHead + "        fragColor = vec4(0.5, 0.5, 0.5, 1.0) + 0.0 * c;\n    }\n");
            if (drawSolidV == 0 || drawCutoutV == 0 || drawFlatV == 0) throw new IllegalStateException("vertex-mode draw shaders failed (see the log)");
            if (COMPACT) compactInit(vsV, fsHead, fsTail);
            int[][] uvs = {U_SOLID_V, U_CUTOUT_V, U_FLAT_V};
            int[] pvs = {drawSolidV, drawCutoutV, drawFlatV};
            for (int k = 0; k < 3; k++) {
                GlStateManager._glUseProgram(pvs[k]);
                GL20C.glUniform1i(GL20C.glGetUniformLocation(pvs[k], "Atlas"), 0);
                GL20C.glUniform1i(GL20C.glGetUniformLocation(pvs[k], "Lightmap"), 2);
                uvs[k][0] = GL20C.glGetUniformLocation(pvs[k], "ViewProj");
                uvs[k][1] = GL20C.glGetUniformLocation(pvs[k], "CamBlock");
                uvs[k][2] = GL20C.glGetUniformLocation(pvs[k], "FogColorV");
                uvs[k][3] = GL20C.glGetUniformLocation(pvs[k], "FogRange");
                uvs[k][4] = GL20C.glGetUniformLocation(pvs[k], "AlphaCut");
                uvs[k][5] = GL20C.glGetUniformLocation(pvs[k], "CamFrac");
            }
        }
        drawFlat = VoxelCompute.program(null, vs, fsHead + "        fragColor = vec4(0.5, 0.5, 0.5, 1.0) + 0.0 * c;\n    }\n");
        if (drawFlat == 0) throw new IllegalStateException("flat draw shader failed (see the log)");
        GlStateManager._glUseProgram(drawFlat);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(drawFlat, "Atlas"), 0);
        U_FLAT[0] = GL20C.glGetUniformLocation(drawFlat, "ViewProj");
        U_FLAT[1] = GL20C.glGetUniformLocation(drawFlat, "CamBlock");
        U_FLAT[2] = GL20C.glGetUniformLocation(drawFlat, "FogColorV");
        U_FLAT[3] = GL20C.glGetUniformLocation(drawFlat, "FogRange");
        U_FLAT[5] = GL20C.glGetUniformLocation(drawFlat, "CamFrac");
        drawSolid = VoxelCompute.program(null, vs, fsHead + fsTail);
        drawCutout = VoxelCompute.program(null, vs, fsHead + "        if (c.a < AlphaCut) discard;\n" + fsTail);
        if (meshProg == 0 || drawSolid == 0 || drawCutout == 0) throw new IllegalStateException("stream shaders failed (see the log)");
        GlStateManager._glUseProgram(meshProg);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "Grid"), 9);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "Light"), 10);
        uDims = GL20C.glGetUniformLocation(meshProg, "Dims");
        uStates = GL20C.glGetUniformLocation(meshProg, "States");
        uMinY = GL20C.glGetUniformLocation(meshProg, "MinY");
        uPass = GL20C.glGetUniformLocation(meshProg, "Pass");
        int[][] us = {U_SOLID, U_CUTOUT};
        int[] ps = {drawSolid, drawCutout};
        for (int k = 0; k < 2; k++) {
            GlStateManager._glUseProgram(ps[k]);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ps[k], "Atlas"), 0);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ps[k], "Lightmap"), 2);
            us[k][0] = GL20C.glGetUniformLocation(ps[k], "ViewProj");
            us[k][1] = GL20C.glGetUniformLocation(ps[k], "CamBlock");
            us[k][2] = GL20C.glGetUniformLocation(ps[k], "FogColorV");
            us[k][3] = GL20C.glGetUniformLocation(ps[k], "FogRange");
            us[k][4] = GL20C.glGetUniformLocation(ps[k], "AlphaCut");
            us[k][5] = GL20C.glGetUniformLocation(ps[k], "CamFrac");
        }
        GlStateManager._glUseProgram(0);
        vao = GlTrack.vao(GL30C.glGenVertexArrays());
        GlStateManager._glBindVertexArray(vao);
        quadIndexBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, quadIndexBuf);
        IntBuffer ib = MemoryUtil.memAllocInt(MAX_QUADS_PER_DRAW * 6);
        for (int qd = 0; qd < MAX_QUADS_PER_DRAW; qd++) {
            int v = qd * 4;
            ib.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
        }
        ib.flip();
        GL44C.glBufferStorage(GL15C.GL_ELEMENT_ARRAY_BUFFER, ib, 0);
        MemoryUtil.memFree(ib);
        if (VERTS) {
            vaoV = GlTrack.vao(GL30C.glGenVertexArrays());
            GlStateManager._glBindVertexArray(vaoV);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, outBuf);
            GL30C.glVertexAttribIPointer(0, 4, GL11C.GL_UNSIGNED_SHORT, 16, 16L);
            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_UNSIGNED_SHORT, true, 16, 24L);
            GL30C.glVertexAttribIPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, 16, 28L);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, slotBuf);
            GL30C.glVertexAttribIPointer(3, 4, GL11C.GL_INT, 16, 0L);
            GL33C.glVertexAttribDivisor(3, 1);
            for (int k = 0; k < 4; k++) GL20C.glEnableVertexAttribArray(k);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, quadIndexBuf);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        }
        GlStateManager._glBindVertexArray(0);
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
    }
}
