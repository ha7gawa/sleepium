package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lull.opt.LightBuilderAccessor;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

/**
 * exp/shader-light (client), step 2: the recipes travel with the mesh. A section whose light has changed ("dynamic")
 * is compiled with recipes: every vertex of its BLOCK-format layers carries, in the normal + padding bytes (offset 28,
 * unread by the chunk shaders), either its light recipe (LightRecipe) or the sentinel (baked: the shader keeps UV2).
 * UV2 itself stays baked as vanilla's, so a section without a light volume renders exactly as before.
 *
 * <p>A recipe is written only when it reproduces the UV2 actually written for that vertex (so a mod's own light,
 * Forge's per-quad light data, full-bright quads... stay baked). Vertices that did not come through an AO or flat
 * quad of ModelBlockRenderer get the sentinel in a pass over each layer's buffer before it is built. Meshes with
 * recipes are marked (not packed into the 16-byte format, which has no room for them).
 *
 * <p>1.20.1: a section is compiled by ChunkRenderDispatcher.RenderChunk.RebuildTask.compile (1.21.1: SectionCompiler),
 * a layer ends as a BufferBuilder.RenderedBuffer (1.21.1: MeshData), and the builder's vertices are read through
 * LightBuilderAccessor (its buffer, write offset, vertex count and format).
 */
public final class LightVolumes {
    private LightVolumes() {}

    /** -Dlab.shaderlight.all=true: every section compiled with recipes (tests) */
    public static final boolean ALL = Boolean.getBoolean("lab.shaderlight.all");
    public static final int SENTINEL = 0xFFFFFFFF;   // sample code 31: never a recipe
    private static final int STRIDE = 32, UV2 = 24, NORMAL = 28;

    /** sections (SectionPos longs) whose light changed since they were first shown: compiled with recipes */
    public static final Set<Long> DYNAMIC = ConcurrentHashMap.newKeySet();
    public static final AtomicLong recipeSections = new AtomicLong(), recipeVertices = new AtomicLong(), bakedVertices = new AtomicLong(),
            rejected = new AtomicLong();

    /** One compile's verdict, shared by its meshes: complete = every vertex has a recipe and every layer is ours. */
    public static final class CompileInfo {
        public volatile boolean complete;
        int sentinels;
        boolean foreignLayer;
    }

    private static final class Compile {
        boolean on;
        CompileInfo info;
        /** LiquidBlockRenderer: the recipe of the light its last getLightColor call computed */
        int fluidRec = SENTINEL;
        final IdentityHashMap<BufferBuilder, BitSet> written = new IdentityHashMap<>();
        final int[] rec = new int[4];
    }

    private static final ThreadLocal<Compile> STATE = ThreadLocal.withInitial(Compile::new);

    public static void beginCompile(SectionPos section) {
        Compile c = STATE.get();
        c.on = ALL || DYNAMIC.contains(section.asLong());
        c.written.clear();
        c.info = c.on ? new CompileInfo() : null;
        if (c.on) recipeSections.incrementAndGet();
    }

    public static void endCompile() {
        Compile c = STATE.get();
        if (c.info != null) c.info.complete = c.info.sentinels == 0 && !c.info.foreignLayer;
        c.info = null;
        c.on = false;
        c.written.clear();
    }

    private static boolean blockBuilder(VertexConsumer vc) {
        return vc instanceof BufferBuilder bb && ((LightBuilderAccessor) bb).sleep$lvFormat() == DefaultVertexFormat.BLOCK;
    }

    /** the address of the builder's last written vertex (1.21.1: BufferBuilder's vertexPointer) */
    private static long lastVertex(LightBuilderAccessor a) {
        return MemoryUtil.memAddress0(a.sleep$lvBuffer()) + a.sleep$lvNext() - STRIDE;
    }

    /** After ModelBlockRenderer.putQuadData: the quad's four vertices are the builder's last four. */
    public static void quadWritten(BlockAndTintGetter level, BlockState state, BlockPos pos, BakedQuad quad, java.util.BitSet shapeFlags, VertexConsumer consumer, boolean ao) {
        quadWritten(level, state, pos, quad, shapeFlags, consumer, ao, false);
    }

    /** culledFlat: tesselateWithoutAO's culled faces, lit at pos + the face direction (no shape flags computed for them) */
    public static void quadWritten(BlockAndTintGetter level, BlockState state, BlockPos pos, BakedQuad quad, java.util.BitSet shapeFlags, VertexConsumer consumer, boolean ao,
                                   boolean culledFlat) {
        Compile c = STATE.get();
        if (!c.on || !blockBuilder(consumer)) return;
        BufferBuilder bb = (BufferBuilder) consumer;
        LightBuilderAccessor a = (LightBuilderAccessor) bb;
        int n = a.sleep$lvVertices();
        if (n < 4) return;
        long last = lastVertex(a);
        boolean have;
        if (ao) {
            have = LightRecipe.integerPath(quad.getDirection(), shapeFlags);
            if (have) LightRecipe.recipes(level, pos, quad.getDirection(), shapeFlags, c.rec);
        } else {
            // Forge's calculateFaceWithoutAO (quads without AO) and flat lighting: one sample, with the quad's own
            // block's emission (LevelRenderer.getLightColor(level, state, sample))
            BlockPos lp = culledFlat || shapeFlags.get(0) ? pos.relative(quad.getDirection()) : pos;
            int one = LightRecipe.flatSelf(level, state, pos, lp);
            c.rec[0] = c.rec[1] = c.rec[2] = c.rec[3] = one;
            have = true;
        }
        BitSet w = c.written.computeIfAbsent(bb, k -> new BitSet());
        for (int i = 0; i < 4; i++) {
            long v = last - (long) (3 - i) * STRIDE;
            int value = SENTINEL;
            if (have) {
                int uv2 = MemoryUtil.memGetInt(v + UV2);
                int em = LightRecipe.emulate(level, pos, c.rec[i]);
                if (em == uv2) value = c.rec[i];
                else {
                    rejected.incrementAndGet();
                    if (DIAG) diag(state, quad, ao, culledFlat, shapeFlags, em, uv2);
                }
            }
            MemoryUtil.memPutInt(v + NORMAL, value);
            if (value == SENTINEL) c.info.sentinels++;
            if (value == SENTINEL) bakedVertices.incrementAndGet();
            else recipeVertices.incrementAndGet();
            w.set(n - 4 + i);
        }
    }

    /** -Dlab.shaderlight.diag=true: UV2-check rejects grouped by block and path, with an example each */
    private static final boolean DIAG = Boolean.getBoolean("lab.shaderlight.diag");
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> REJECTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> EXAMPLES = new java.util.concurrent.ConcurrentHashMap<>();

    private static void diag(BlockState state, BakedQuad quad, boolean ao, boolean culledFlat, java.util.BitSet flags, int emulated, int written) {
        String k = state.getBlock().getDescriptionId().replace("block.minecraft.", "") + " " + (ao ? "ao" : culledFlat ? "flat-culled" : "flat") + " " + quad.getDirection()
                + (ao ? " flags" + flags : "") + " quadAO=" + quad.hasAmbientOcclusion() + " shade=" + quad.isShade();
        REJECTS.computeIfAbsent(k, x -> new long[1])[0]++;
        EXAMPLES.putIfAbsent(k, "recipe " + Integer.toHexString(emulated) + " written " + Integer.toHexString(written));
    }

    public static String diagReport() {
        StringBuilder b = new StringBuilder("shader light rejects:");
        REJECTS.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(25)
                .forEach(e -> b.append(System.lineSeparator()).append("  ").append(e.getValue()[0]).append("  ").append(e.getKey()).append("  (").append(EXAMPLES.get(e.getKey())).append(")"));
        return b.toString();
    }

    /** LiquidBlockRenderer.getLightColor(level, p) while tesselating the fluid at fluidPos */
    public static void fluidLight(BlockPos fluidPos, BlockPos p) {
        Compile c = STATE.get();
        if (c.on) c.fluidRec = LightRecipe.fluid(fluidPos, p);
    }

    /** after one LiquidBlockRenderer.vertex: the builder's last vertex gets the current fluid recipe (UV2-checked) */
    public static void fluidVertex(BlockAndTintGetter level, BlockPos fluidPos, VertexConsumer consumer) {
        Compile c = STATE.get();
        if (!c.on || !blockBuilder(consumer)) return;
        LightBuilderAccessor a = (LightBuilderAccessor) consumer;
        int n = a.sleep$lvVertices();
        if (n < 1) return;
        long v = lastVertex(a);
        int value = SENTINEL;
        if (c.fluidRec != SENTINEL && LightRecipe.emulate(level, fluidPos, c.fluidRec) == MemoryUtil.memGetInt(v + UV2)) value = c.fluidRec;
        else rejected.incrementAndGet();
        MemoryUtil.memPutInt(v + NORMAL, value);
        if (value == SENTINEL) {
            c.info.sentinels++;
            bakedVertices.incrementAndGet();
        } else recipeVertices.incrementAndGet();
        c.written.computeIfAbsent((BufferBuilder) consumer, k -> new BitSet()).set(n - 1);
    }

    // ---- the translucent layer (water, glass, ice…) stays on vanilla's per-section draws: its shader takes the
    // section's volume from a uniform set before each draw; its VAO exposes the recipe as attribute 7
    public static volatile boolean translucentPatched;

    /**
     * A drawing program dropped the recipe path (its ProbeRecipe attribute is inactive: another mod's shader processing
     * replaced the line that used it - Sable rewrites every "x = y * minecraft_sample_lightmap(...)"): light-only
     * updates no longer skip the re-mesh, so a section shows new light the vanilla way.
     */
    public static volatile boolean recipesIgnored;

    public static void recipesIgnored(String where) {
        if (recipesIgnored) return;
        recipesIgnored = true;
        System.out.println("[sleep/opt] shader light: " + where + " does not read the light recipes (another mod rewrote its light line): light changes re-mesh as vanilla");
    }
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap VOLUME_UNIFORM = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

    static void forgetProgram(int id) {
        VOLUME_UNIFORM.remove(id);
    }
    private static long boundFrame = -1;

    public static String patchTranslucent(String src) {
        String light = "vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);";
        if (!ON || Boolean.getBoolean("lab.shaderlight.noTranslucent") || !ChunkMultiDraw.ssbo() || !src.startsWith("#version 150") || !src.contains(light) || !src.contains("void main() {")) return src;
        translucentPatched = true;
        return src.replaceFirst("#version 150", "#version 150\n#extension GL_ARB_explicit_attrib_location : require\n#extension GL_ARB_shader_storage_buffer_object : require")
                .replace("void main() {", "uniform int ProbeVolume;\n" + glsl() + "\nvoid main() {")
                .replace(light, "vertexColor = Color * minecraft_sample_lightmap(Sampler2, probeUV2(float(ProbeVolume)));");
    }

    /** the vanilla section VAO of a BLOCK-format layer: the normal + padding bytes as integer attribute 7 */
    public static void vanillaBufferUploaded() {
        org.lwjgl.opengl.GL20C.glEnableVertexAttribArray(7);
        org.lwjgl.opengl.GL30C.glVertexAttribIPointer(7, 1, org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT, STRIDE, NORMAL);
    }

    /** LevelRenderer.renderChunkLayer, before a translucent section's draw: its volume, or -1 (baked light) */
    public static void translucentDraw(net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk section) {
        if (!translucentPatched) return;
        net.minecraft.client.renderer.ShaderInstance sh = com.mojang.blaze3d.systems.RenderSystem.getShader();
        if (sh == null) return;
        int program = sh.getId();
        int loc = VOLUME_UNIFORM.getOrDefault(program, Integer.MIN_VALUE);
        if (loc == Integer.MIN_VALUE) {
            loc = org.lwjgl.opengl.GL20C.glGetUniformLocation(program, "ProbeVolume");
            int block = org.lwjgl.opengl.GL43C.glGetProgramResourceIndex(program, org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BLOCK, "ProbeLightVolumes");
            if (block != org.lwjgl.opengl.GL31C.GL_INVALID_INDEX) org.lwjgl.opengl.GL43C.glShaderStorageBlockBinding(program, block, BINDING);
            if (loc >= 0 && org.lwjgl.opengl.GL20C.glGetAttribLocation(program, "ProbeRecipe") < 0) recipesIgnored("the translucent program");
            VOLUME_UNIFORM.put(program, loc);
        }
        if (loc < 0) return;
        if (boundFrame != frame) {
            boundFrame = frame;
            org.lwjgl.opengl.GL30C.glBindBufferBase(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, BINDING, buffer());
        }
        Sec sec = SECS.get(key(section));
        org.lwjgl.opengl.GL20C.glUniform1i(loc, sec != null && sec.vol >= 0 && sec.ready && sec.translucentRecipes ? sec.vol : -1);
    }

    /** ChunkMultiDraw's translucent multi-draw: the volume translucentDraw would set for this section's draw */
    public static float translucentVolume(net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk section) {
        Sec sec = SECS.get(key(section));
        return sec != null && sec.vol >= 0 && sec.ready && sec.translucentRecipes ? sec.vol : -1;
    }

    /** after the draw: the uniform back to -1 (the shader serves other translucent draws too) */
    public static void translucentDrawn() {
        if (!translucentPatched) return;
        net.minecraft.client.renderer.ShaderInstance sh = com.mojang.blaze3d.systems.RenderSystem.getShader();
        if (sh == null) return;
        int loc = VOLUME_UNIFORM.getOrDefault(sh.getId(), -1);
        if (loc >= 0) org.lwjgl.opengl.GL20C.glUniform1i(loc, -1);
    }

    /** RebuildTask.compile, before a layer's builder is ended: the vertices no quad hook wrote get the sentinel. */
    public static boolean beforeBuild(BufferBuilder bb) {
        Compile c = STATE.get();
        if (!c.on || !blockBuilder(bb)) return false;
        LightBuilderAccessor a = (LightBuilderAccessor) bb;
        int n = a.sleep$lvVertices();
        if (n == 0) return false;
        long first = lastVertex(a) - (long) (n - 1) * STRIDE;
        BitSet w = c.written.get(bb);
        for (int i = 0; i < n; i++) {
            if (w != null && w.get(i)) continue;
            MemoryUtil.memPutInt(first + (long) i * STRIDE + NORMAL, SENTINEL);
            c.info.sentinels++;
            bakedVertices.incrementAndGet();
        }
        return true;
    }

    /** RebuildTask.compile, a layer's builder taken: a layer that is not drawn from our arenas keeps its baked light (incomplete). */
    public static void layerUsed(net.minecraft.client.renderer.RenderType type) {
        Compile c = STATE.get();
        if (c.on && !ChunkMultiDraw.layerSupported(type) && !(type == net.minecraft.client.renderer.RenderType.translucent() && translucentPatched))
            c.info.foreignLayer = true;
    }

    /** the compile's info, for the mesh being built */
    public static CompileInfo currentInfo() {
        return STATE.get().info;
    }

    /** Implemented on BufferBuilder.RenderedBuffer: built with recipes (its compile's info), or not (null). */
    public interface RecipeMesh {
        CompileInfo sleep$recipeInfo();

        void sleep$setRecipeInfo(CompileInfo info);
    }

    public static boolean hasRecipes(BufferBuilder.RenderedBuffer mesh) {
        return mesh instanceof RecipeMesh r && r.sleep$recipeInfo() != null;
    }

    // ------------------------------------------------------------------ step 3: light volumes (render thread)

    public static final boolean ON = Boolean.getBoolean("lab.opt.ShaderLightMixin");
    /** 18^3 light bytes per section (a one-block border): block light | sky light << 4, as getLightColor gives them */
    public static final int VOLUME_BYTES = 18 * 18 * 18, BINDING = 6;
    private static final int MAX_VOLUMES = Integer.getInteger("lab.shaderlight.maxVolumes", 8192);
    public static long fills, lightOnlyUpdates, rebuildsAsked;
    /** light changes of a section before it is compiled with recipes */
    private static final int DYNAMIC_AFTER = Integer.getInteger("lab.shaderlight.dynamicAfter", 3);
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap LIGHT_COUNT = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    /** when each section's first mesh arrived (nanoTime), and the settle time before its light changes count */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap FIRST_SEEN = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private static final long SETTLE_NANOS = Long.getLong("lab.shaderlight.settleSeconds", 10) * 1_000_000_000L;
    /** -Dlab.shaderlight.debug=true: the shader path returns full block light (proves it is active: the frame hash must change) */
    private static final boolean DEBUG = Boolean.getBoolean("lab.shaderlight.debug");

    static final class Sec {
        int vol = -1;
        CompileInfo info;
        /** its translucent layer (vanilla draws) was built with recipes */
        boolean translucentRecipes;
        boolean dirty;
        /** the latest refill submitted: an older one finishing later is dropped */
        int seq;
        /** the volume holds light at least as new as the section's meshes: draws may read it (else baked UV2) */
        boolean ready;
    }

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Sec> SECS = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk> SECTIONS =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final java.util.ArrayList<Long> DIRTY = new java.util.ArrayList<>();
    private static int buffer, capacity, used;
    private static final it.unimi.dsi.fastutil.ints.IntArrayList FREE = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private static final java.nio.ByteBuffer FILL = MemoryUtil.memAlloc(VOLUME_BYTES);

    public static int buffer() {
        if (buffer == 0) grow(256);
        return buffer;
    }

    private static void grow(int slots) {
        int nb = org.lwjgl.opengl.GL15C.glGenBuffers();
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, nb);
        org.lwjgl.opengl.GL15C.glBufferData(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, (long) slots * VOLUME_BYTES, org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW);
        if (buffer != 0) {
            org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL31C.GL_COPY_READ_BUFFER, buffer);
            org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL31C.GL_COPY_WRITE_BUFFER, nb);
            org.lwjgl.opengl.GL31C.glCopyBufferSubData(org.lwjgl.opengl.GL31C.GL_COPY_READ_BUFFER, org.lwjgl.opengl.GL31C.GL_COPY_WRITE_BUFFER, 0, 0, (long) capacity * VOLUME_BYTES);
            org.lwjgl.opengl.GL15C.glDeleteBuffers(buffer);
        }
        buffer = nb;
        capacity = slots;
    }

    private static int allocSlot() {
        if (!FREE.isEmpty()) return FREE.removeInt(FREE.size() - 1);
        if (used >= MAX_VOLUMES) return -1;
        if (buffer == 0) grow(256);
        if (used >= capacity) grow(Math.min(MAX_VOLUMES, capacity * 2));
        return used++;
    }

    private static long key(SectionPos p) {
        return p.asLong();
    }

    private static long key(net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk s) {
        var o = s.getOrigin();
        return SectionPos.asLong(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4);
    }

    /** ChunkUploadMixin, after ChunkMultiDraw.store: a layer mesh of the section reached the GPU (ours: in an arena). */
    public static void uploaded(net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk section, net.minecraft.client.renderer.RenderType layer,
                                BufferBuilder.RenderedBuffer mesh, boolean ours) {
        CompileInfo info = mesh instanceof RecipeMesh r ? r.sleep$recipeInfo() : null;
        long k = key(section);
        if (!FIRST_SEEN.containsKey(k)) FIRST_SEEN.put(k, System.nanoTime());
        Sec sec = SECS.get(k);
        boolean translucent = layer == net.minecraft.client.renderer.RenderType.translucent();
        boolean volumeDrawn = ours || translucent && translucentPatched;   // a layer whose draws read the volume
        if (info == null) {
            if (sec != null && !volumeDrawn) sec.info = null;   // a baked layer drawn the vanilla way: light-only updates would miss it
            if (sec != null && translucent) sec.translucentRecipes = false;
            return;
        }
        if (sec == null) SECS.put(k, sec = new Sec());
        SECTIONS.put(k, section);
        sec.info = volumeDrawn ? info : null;
        if (translucent) sec.translucentRecipes = true;
        if (sec.vol < 0) {
            sec.vol = allocSlot();
            if (sec.vol < 0) return;   // out of volumes: stays on baked light
        }
        // a new mesh carries baked light as new as its compile: until a refill of the volume (on the frame pool) lands,
        // its draws use that baked light — what vanilla shows — then the volume again (also covers the first fill)
        sec.ready = false;
        assign(section, -1);
        if (!sec.dirty) {
            sec.dirty = true;
            DIRTY.add(k);
        }
    }

    /** the section's arena entries with recipes point at its volume */
    private static void assign(net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk section, int vol) {
        Object[] md = ((ChunkMultiDraw.ChunkEntries) section).sleep$md();
        for (Object o : md) if (o instanceof ChunkMultiDraw.Entry e) e.vol = e.recipes ? vol : -1;
        SectionLists.changed(section);
    }

    /** RenderChunk reset (origin change, release): its volume is free again. */
    public static void released(net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk section) {
        FIRST_SEEN.remove(key(section));
        LIGHT_COUNT.remove(key(section));
        SECTIONS.remove(key(section));
        Sec sec = SECS.remove(key(section));
        if (sec != null && sec.vol >= 0) FREE.add(sec.vol);
    }

    /**
     * A light change in section (x, y, z) — vanilla re-meshes it (ClientChunkCache.onLightUpdate). A complete section
     * with a volume gets its volume refreshed instead; any other one is re-meshed, from now on with recipes.
     */
    public static void lightChanged(int x, int y, int z) {
        // (1.21.1 also refreshed part batching's lit boxes here, PartBatch.LIT_ON: they come with PartBatch's port)
        long k = SectionPos.asLong(x, y, z);
        Sec sec = SECS.get(k);
        if (!recipesIgnored && sec != null && sec.vol >= 0 && sec.ready && sec.info != null && sec.info.complete) {
            if (!sec.dirty) {
                sec.dirty = true;
                DIRTY.add(k);
            }
            lightOnlyUpdates++;
            return;
        }
        // dynamic after a few changes (a lamp, redstone): the first light a section gets while the world loads is not one
        // counted only once the section has been on screen a while (the light a loading world brings is not dynamic)
        long seen = FIRST_SEEN.get(k);
        if (seen != 0 && System.nanoTime() - seen > SETTLE_NANOS && LIGHT_COUNT.addTo(k, 1) + 1 >= DYNAMIC_AFTER && DYNAMIC.size() < MAX_VOLUMES)
            DYNAMIC.add(k);
        rebuildsAsked++;
        net.minecraft.client.Minecraft.getInstance().levelRenderer.setSectionDirty(x, y, z);
    }

    /** -Dlab.shaderlight.syncFill=true: refills on the render thread (as first built) */
    private static final boolean SYNC_FILL = Boolean.getBoolean("lab.shaderlight.syncFill");
    private static final java.util.concurrent.ConcurrentLinkedQueue<java.nio.ByteBuffer> BUFFERS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public static long asyncFills, droppedFills;

    /**
     * Start of renderLevel: finished refills are uploaded (FrameReactor's completion ring), then the sections whose
     * light changed are refilled on the frame's worker pool — reading the level as vanilla's section compile workers
     * do. Until a refill lands the volume keeps the previous light (vanilla keeps its old mesh until the re-mesh).
     */
    /** renderLevel count (the translucent pass binds the volume buffer once per frame) */
    static long frame;

    public static void frame() {
        frame++;
        FrameReactor.drain();
        if (DIRTY.isEmpty()) return;
        for (int i = 0; i < DIRTY.size(); i++) {
            long k = DIRTY.get(i);
            Sec sec = SECS.get(k);
            if (sec == null || sec.vol < 0) continue;
            sec.dirty = false;
            if (SYNC_FILL) {
                fill(sec, SectionPos.of(k).origin());
                var section = SECTIONS.get(k);
                if (!sec.ready && section != null) {
                    sec.ready = true;
                    assign(section, sec.vol);
                }
            } else submit(k, sec);
        }
        DIRTY.clear();
    }

    private static void submit(long k, Sec sec) {
        int seq = ++sec.seq, vol = sec.vol;
        var section = SECTIONS.get(k);
        BlockPos origin = SectionPos.of(k).origin();
        java.nio.ByteBuffer b = BUFFERS.poll();
        java.nio.ByteBuffer buf = b != null ? b : MemoryUtil.memAlloc(VOLUME_BYTES);
        net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;
        FrameReactor.pool().execute(() -> {
            compute(level, origin, buf);
            FrameReactor.complete(() -> {
                if (SECS.get(k) == sec && sec.vol == vol && sec.seq == seq) {
                    upload(vol, buf);
                    if (!sec.ready && section != null) {
                        sec.ready = true;
                        assign(section, vol);
                    }
                } else droppedFills++;
                BUFFERS.add(buf);
            });
        });
        asyncFills++;
    }

    private static void compute(net.minecraft.world.level.BlockAndTintGetter level, BlockPos origin, java.nio.ByteBuffer into) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        long base = MemoryUtil.memAddress(into);
        int i = 0;
        for (int y = -1; y <= 16; y++)
            for (int z = -1; z <= 16; z++)
                for (int x = -1; x <= 16; x++) {
                    m.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    int lc = LevelRenderer.getLightColor(level, level.getBlockState(m), m);
                    MemoryUtil.memPutByte(base + i++, (byte) ((lc >> 4 & 15) | (lc >> 20 & 15) << 4));
                }
    }

    private static void upload(int vol, java.nio.ByteBuffer data) {
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, buffer());
        data.limit(VOLUME_BYTES).position(0);
        org.lwjgl.opengl.GL15C.glBufferSubData(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, (long) vol * VOLUME_BYTES, data);
        fills++;
    }

    /** the first fill, on the render thread: the volume is right before its first draw */
    private static void fill(Sec sec, BlockPos origin) {
        net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;
        sec.seq++;   // any refill still running is older than this
        compute(level, origin, FILL);
        upload(sec.vol, FILL);
    }

    /** GLSL for ChunkMultiDraw's patched chunk vertex shaders: the vertex's light from its recipe and its section's volume. */
    public static final String GLSL = """
            layout(location = 7) in uint ProbeRecipe;
            layout(std430) buffer ProbeLightVolumes { uint ProbeLV[]; };
            uint probeSample(int vol, ivec3 p) {
                int idx = vol * 5832 + ((p.y + 1) * 18 + (p.z + 1)) * 18 + (p.x + 1);
                uint b = (ProbeLV[idx >> 2] >> uint((idx & 3) * 8)) & 255u;
                return ((b >> 4u) << 20u) | ((b & 15u) << 4u);
            }
            ivec2 probeUV2(float w) {
                if (w < 0.0 || ProbeRecipe == 0xFFFFFFFFu) return UV2;
                int vol = int(w);
                ivec3 bp = ivec3(int((ProbeRecipe >> 28u) & 15u), int((ProbeRecipe >> 24u) & 15u), int((ProbeRecipe >> 20u) & 15u));
                uint marker = (ProbeRecipe >> 5u) & 31u;
                if (marker == 29u) return ivec2(240, 240);
                if (marker == 28u) {
                    int ca = int(ProbeRecipe & 31u), cb = int((ProbeRecipe >> 10u) & 31u);
                    uint la = probeSample(vol, bp + ivec3(ca / 9 - 1, (ca / 3) % 3 - 1, ca % 3 - 1));
                    uint lb = probeSample(vol, bp + ivec3(cb / 9 - 1, (cb / 3) % 3 - 1, cb % 3 - 1));
                    uint lm = max(la & 255u, lb & 255u) | (max((la >> 16u) & 255u, (lb >> 16u) & 255u) << 16u);
                    return ivec2(int(lm & 0xFFFFu), int(lm >> 16u));
                }
                if (marker == 30u) {
                    int c0 = int(ProbeRecipe & 31u);
                    uint lc = probeSample(vol, bp + ivec3(c0 / 9 - 1, (c0 / 3) % 3 - 1, c0 % 3 - 1));
                    uint blk = max((lc >> 4u) & 15u, (ProbeRecipe >> 10u) & 31u);
                    lc = (lc & 0xF00000u) | (blk << 4u);
                    return ivec2(int(lc & 0xFFFFu), int(lc >> 16u));
                }
                uint s[4];
                for (int k = 0; k < 4; k++) {
                    int c = int((ProbeRecipe >> uint(5 * k)) & 31u);
                    s[k] = probeSample(vol, bp + ivec3(c / 9 - 1, (c / 3) % 3 - 1, c % 3 - 1));
                }
                if (s[0] == 0u) s[0] = s[3];
                if (s[1] == 0u) s[1] = s[3];
                if (s[2] == 0u) s[2] = s[3];
                uint lm = ((s[0] + s[1] + s[2] + s[3]) >> 2u) & 0xFF00FFu;
                return ivec2(int(lm & 0xFFFFu), int(lm >> 16u));
            }
            """;

    public static String glsl() {
        return DEBUG ? GLSL.replace("return ivec2(int(lm & 0xFFFFu), int(lm >> 16u));", "return ivec2(240, int(lm >> 16u));") : GLSL;
    }

    public static String report() {
        long t = Math.max(1, recipeVertices.get() + bakedVertices.get());
        return String.format(java.util.Locale.ROOT, "shader light: %d sections compiled with recipes, vertices: recipe %.2f %%, baked %.2f %% (recipe rejected by the UV2 check %d)",
                recipeSections.get(), 100.0 * recipeVertices.get() / t, 100.0 * bakedVertices.get() / t, rejected.get())
                + String.format(java.util.Locale.ROOT, "; volumes %d in use, %d fills (%d on the pool, %d dropped as superseded), light-only updates %d, re-meshes asked %d, dynamic sections %d; shaders with recipe light %s",
                        SECS.size(), fills, asyncFills, droppedFills, lightOnlyUpdates, rebuildsAsked, DYNAMIC.size(), ChunkMultiDraw.LIGHT_PATCHED);
    }

    /** the light a flat sample uses (getLightColor of the state there), for completeness of the recipe check */
    static int lightAt(BlockAndTintGetter level, BlockPos p) {
        return LevelRenderer.getLightColor(level, level.getBlockState(p), p);
    }
}
