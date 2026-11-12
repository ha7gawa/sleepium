package dev.lull.shaders;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

/**
 * A loaded shaderpack running the level render: -Dsleep.shaders=<pack> (from shaderpacks/). Vanilla draws, with the
 * pack's gbuffers programs put in place of vanilla's shaders (by vanilla shader and render phase), into the pack's
 * framebuffer; deferred passes run before the translucents, composite + final after the hand, final into the window.
 * The hooks (sleepium-shaders.mixins.json) do nothing while no pack is loaded.
 */
public final class ShaderPipeline {
    private static ShaderPipeline current;
    /** a pack is loaded (the hooks check this first) */
    public static volatile boolean active;
    /** colortex0 alone (one shared array: framebuffers are cached by the array) */
    public static final int[] BUFFER0 = {0};

    public static ShaderPipeline current() {
        return current;
    }

    /** the depth texture the gbuffers programs draw into (0: no pack loaded) and its size: OcclusionCull */
    public static int gbufferDepth() {
        ShaderPipeline p = current;
        return active && p != null ? p.targets.depthTex0 : 0;
    }

    public static int gbufferWidth() {
        return current.targets.width;
    }

    public static int gbufferHeight() {
        return current.targets.height;
    }

    final ShaderPackSource src;
    final ProgramSet set;
    final PackProperties props;
    /** the pack's block / entity / item ids (ShaderIds.maps while this pipeline is current) */
    final IdMaps ids;
    final RenderTargets targets = new RenderTargets();
    final PackUniforms uniforms = new PackUniforms();
    private final Map<String, CompositeProgram> passes = new HashMap<>();
    private final List<CompositeProgram> deferred = new ArrayList<>(), composite = new ArrayList<>(), begin = new ArrayList<>(), prepare = new ArrayList<>();
    private CompositeProgram finalPass;
    private final Map<ShaderInstance, Map<String, PackShader>> shaders = new java.util.IdentityHashMap<>();
    private final Map<String, PackShader.Prepared> prepared = new java.util.concurrent.ConcurrentHashMap<>();

    private String uniqueName(ProgramSet.Program p, String key) {
        return "sleep_pack" + generation + "_" + p.name + "_" + Integer.toHexString(key.hashCode());
    }

    /** compiled instances by what makes their source (see shaderFor) */
    private final Map<String, PackShader> shared = new HashMap<>();
    private final Map<String, Integer> stageTextures = new HashMap<>();   // "<stage>.<sampler>" -> texture id
    private int noiseTex, normalsTex, specularTex, shadowDepthTex, shadowColorTex;
    private static final Map<String, Integer> UNITS = new HashMap<>(RenderTargets.UNITS);
    /** this pipeline's number: its programs' names (vanilla keeps compiled shaders by name; two pipelines live at once while one is prepared beside the other) */
    private int generation;
    private static int generations;
    public boolean inLevel;
    /** the program applied last reads its quad's vertices: each drawn vertex buffer bound for it (binding 9) */
    public boolean fetch;
    /** the shadow pass runs (shadow programs, the shadow framebuffer); its block-entity part */
    boolean shadowPhase, shadowBlockEntitiesPhase;
    ShadowPass shadow;
    private static final boolean STATS = Boolean.getBoolean("sleep.shaders.stats");
    private long statFrames;
    private long beginUniformNanos, beginClearNanos;
    private boolean shadowDone;
    private Camera camera;
    private float partialTick;
    private boolean deferredDone, handPhase;
    private String phase = "";
    private final List<String> problems = new ArrayList<>();

    public static Map<String, Integer> samplerUnits() {
        return UNITS;
    }

    // ------------------------------------------------------------------ loading

    /** render thread, once: the pack named by -Dsleep.shaders, if any */
    private static volatile String requested;
    private static volatile boolean requestPending;

    /** the settings screen: this pack (null or blank: none) from the next frame on, the chunks rebuilt for it */
    public static void request(String name) {
        requested = name;
        requestPending = true;
    }

    /** the loaded pack's name (null: none) */
    public static String currentName() {
        ShaderPipeline p = current;
        return p == null ? null : p.src.name;
    }

    public static void loadConfigured() {
        if (requestPending) {
            requestPending = false;
            String name = requested;
            if (name == null || name.isBlank()) System.clearProperty("sleep.shaders");
            else System.setProperty("sleep.shaders", name);
            if (pending != null) {   // a pack still being prepared is replaced
                pending.close();
                pending = null;
            }
            ShaderPipeline old = current;
            if (old == null || name == null || name.isBlank()) {
                // nothing on, or the pack switched off: at once (a new pack is prepared while vanilla draws)
                if (old != null) {
                    active = false;
                    ShaderIds.on = false;
                    current = null;
                    old.close();
                }
                load();
                if (current == null && Minecraft.getInstance().levelRenderer != null) Minecraft.getInstance().levelRenderer.allChanged();
                System.out.println("[sleep/shaders] switched to " + (current == null ? "no pack" : current.src.name + " (preparing)"));
                return;
            }
            // a pack on: the new one (another pack, or the same with new options) prepared beside it, the old one drawn
            // meanwhile, then swapped in one frame (no vanilla frames drawn on a pack's meshes, no empty world)
            try {
                pending = new ShaderPipeline(Minecraft.getInstance().gameDirectory.toPath(), name);
                System.out.println("[sleep/shaders] preparing " + pending.src.name + " beside " + old.src.name);
            } catch (Exception e) {
                System.out.println("[sleep/shaders] could not load " + name + ": " + e);
                e.printStackTrace(System.out);
            }
            return;
        }
        if (current == null) load();
        var mc = Minecraft.getInstance();
        long budget = mc.level == null || mc.screen != null ? 60_000_000L : 25_000_000L;
        // a loaded pack not yet on: its programs built over frames, vanilla drawn meanwhile (more per frame on a screen)
        ShaderPipeline p = current;
        if (p != null && !active) {
            if (p.stepPrewarm(budget)) {
                ShaderIds.maps = p.ids;
                activate(p, true);
            }
            return;
        }
        ShaderPipeline n = pending;
        if (n != null && n.stepPrewarm(budget)) {
            ShaderPipeline old = current;
            // the meshes carry the pack's block ids: built again only when the new pack maps blocks otherwise
            boolean sameIds = old != null && old.ids.sameAs(n.ids);
            pending = null;
            current = n;
            ShaderIds.maps = n.ids;
            if (old != null) old.close();
            activate(n, !sameIds);
        }
    }

    /** a pack prepared beside the one on (an applied change of options, or another pack) */
    private static ShaderPipeline pending;

    private static void activate(ShaderPipeline p, boolean remesh) {
        active = true;
        dev.lull.optsupport.LightVolumes.recipesIgnored("a shaderpack's terrain program");
        var mc = Minecraft.getInstance();
        if (mc.options.graphicsMode().get() == net.minecraft.client.GraphicsStatus.FABULOUS) mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
        // the meshes carry the pack's ids and its translucent shadow copies: built again, as Iris does
        if (remesh && mc.levelRenderer != null && mc.level != null) mc.levelRenderer.allChanged();
        System.out.println("[sleep/shaders] " + p.src.name + " on" + (remesh ? "" : " (the meshes kept: same block ids)"));
    }

    /** "name built/total" while a pack is being prepared (the screen shows it), else null */
    public static String preparing() {
        ShaderPipeline p = pending != null ? pending : current;
        if (p == null || p == current && active) return null;
        return p.src.name + " " + p.prewarmBuilt() + "/" + Math.max(p.prewarmTotal(), p.prewarmBuilt());
    }

    /** frees everything the pack made (render thread, between frames: the hooks see active = false first) */
    private void close() {
        for (CompositeProgram cp : passes.values()) cp.delete();
        for (PackShader s : shared.values()) {
            if (s == null) continue;
            dev.lull.optsupport.ProgramCaches.forget(s.getId());
            s.close();
        }
        shared.clear();
        shaders.clear();
        targets.delete();
        if (shadow != null) shadow.targets.delete();
        java.util.Set<Integer> tex = new java.util.HashSet<>(stageTextures.values());
        for (int t : new int[] {noiseTex, normalsTex, specularTex, shadowDepthTex, shadowColorTex}) tex.add(t);
        for (int t : tex) {
            if (t == 0) continue;
            PackUniforms.forgetTexture(t);
            GL11.glDeleteTextures(t);
        }
        GL20.glUseProgram(0);
        ShaderPipelineHooks.resetProgramCache();
    }

    private static boolean otherModWarned;

    private static void load() {
        String name = System.getProperty("sleep.shaders");
        if (name == null || name.isBlank() || current != null) return;
        if (OtherShaderMods.present()) {
            // the hooks were not applied (sleepium-shaders.mixins.json gives way to Oculus / Iris / OptiFine): a pack
            // switched on without them would only make the core's paths stand down
            if (!otherModWarned) System.out.println("[sleep/shaders] " + name + " not loaded: another shader mod (Oculus / Iris / OptiFine) runs packs");
            otherModWarned = true;
            return;
        }
        try {
            current = new ShaderPipeline(Minecraft.getInstance().gameDirectory.toPath(), name);   // on once prepared (loadConfigured)
            ShaderIds.maps = current.ids;
        } catch (Exception e) {
            System.out.println("[sleep/shaders] could not load " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    private ShaderPipeline(Path gameDir, String name) throws Exception {
        long t0 = System.nanoTime();
        src = ShaderPackSource.open(gameDir.resolve("shaderpacks"), name);
        PackOptions options = PackOptions.scan(src);
        Path userFile = gameDir.resolve("shaderpacks").resolve(src.name + ".txt");
        if (Files.exists(userFile)) options.load(Files.readString(userFile));
        List<String> macros = StandardMacros.build(List.of());
        List<String> withOptions = new ArrayList<>(macros);
        withOptions.addAll(options.asMacros());
        props = PackProperties.load(src, withOptions);
        set = ProgramSet.load(src, options, macros, props, "world0");
        uniforms.configure(set.consts);
        uniforms.custom = CustomUniforms.parse(props.customUniforms);
        ids = IdMaps.load(src, withOptions);
        System.out.println("[sleep/shaders] ids: oak_leaves " + ids.block(net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState())
                + ", grass_block " + ids.block(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState())
                + ", short_grass " + ids.block(net.minecraft.world.level.block.Blocks.GRASS.defaultBlockState())
                + ", water " + ids.block(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState())
                + ", stone " + ids.block(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())
                + "; " + ids.blocks.size() + " states mapped");
        if (!ids.unknown.isEmpty()) problems.add("block names this version lacks (ignored): " + ids.unknown.size());
        problems.addAll(uniforms.custom.errors);
        step("textures");
        loadTextures();
        step("targets");
        boolean[] used = new boolean[RenderTargets.COLOR_TEX];
        for (ProgramSet.Program p : set.programs.values()) {
            for (int b : p.drawBuffers) if (b < used.length) used[b] = true;
            for (String s : p.sources) {
                if (s == null) continue;
                for (int i = 0; i < used.length; i++) if (s.contains("colortex" + i) || i < 8 && s.contains(new String[] {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"}[i])) used[i] = true;
            }
        }
        targets.configure(set.consts, used);
        for (ProgramSet.Program p : set.programs.values()) {
            boolean gb = ProgramSet.FALLBACK.containsKey(p.name);
            if (gb) continue;
            step("pass " + p.name);
            CompositeProgram cp = new CompositeProgram(p);
            if (!cp.log.isEmpty()) problems.add(cp.log);
            passes.put(p.name, cp);
        }
        for (String pass : new String[] {"begin", "prepare", "deferred", "composite"}) {
            List<CompositeProgram> list = switch (pass) {
                case "begin" -> begin;
                case "prepare" -> prepare;
                case "deferred" -> deferred;
                default -> composite;
            };
            for (int i = 0; i < 100; i++) {
                CompositeProgram cp = passes.get(i == 0 ? pass : pass + i);
                if (cp != null && (cp.id != 0 || !cp.computes.isEmpty())) list.add(cp);
            }
        }
        finalPass = passes.get("final");
        if (!Boolean.getBoolean("sleep.shaders.noGraph")) {
            FrameGraph g = new FrameGraph(set, props, begin, prepare, deferred, composite, finalPass, targets.used, targets.clear);
            targets.clearRole = g.clearRole;
            System.out.println("[sleep/shaders] " + g.report);
        }
        ProgramSet.Program sp = set.resolve("shadow");
        if (sp != null && sp.sources[0] != null && sp.sources[2] != null && props.bool("shadow.enabled", true)) shadow = new ShadowPass(this, new ShadowTargets(set.consts));
        generation = ++generations;
        System.out.printf("[sleep/shaders] loaded %s: %d programs, %d deferred, %d composite, final %s, %d custom uniforms, %.0f ms%s%n", src.name,
                set.programs.size(), deferred.size(), composite.size(), finalPass != null && finalPass.id != 0, uniforms.custom.entries.size(),
                (System.nanoTime() - t0) / 1e6, problems.isEmpty() ? "" : "; problems:\n" + String.join("\n", problems));
    }

    private static final boolean STEPS = Boolean.getBoolean("sleep.shaders.steps");

    private static void step(String s) {
        if (STEPS) {
            System.out.println("[sleep/shaders] step " + s);
            System.out.flush();
        }
    }

    private void loadTextures() {
        int res = (int) Float.parseFloat(set.consts.getOrDefault("noiseTextureResolution", "256"));
        String noisePath = props.get("texture.noise");
        noiseTex = noisePath != null ? packTexture(noisePath) : 0;
        if (noiseTex == 0) {
            java.util.Random r = new java.util.Random(0);
            byte[] px = new byte[res * res * 3];
            r.nextBytes(px);
            noiseTex = rawTexture(res, res, GL11.GL_RGB8, GL11.GL_RGB, px, true);
        }
        step("defaults");
        normalsTex = rawTexture(1, 1, GL11.GL_RGBA8, GL11.GL_RGBA, new byte[] {127, 127, (byte) 255, (byte) 255}, false);
        specularTex = rawTexture(1, 1, GL11.GL_RGBA8, GL11.GL_RGBA, new byte[] {0, 0, 0, 0}, false);
        // no shadow pass yet: a far depth (everything lit) and a white colour
        step("shadow");
        shadowDepthTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowDepthTex);
        GL43.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_DEPTH_COMPONENT32F, 1, 1);
        org.lwjgl.opengl.GL44.glClearTexImage(shadowDepthTex, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, new float[] {1});
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        step("shadow colour");
        shadowColorTex = rawTexture(1, 1, GL11.GL_RGBA8, GL11.GL_RGBA, new byte[] {-1, -1, -1, -1}, false);
        int nextUnit = 38;
        for (var e : props.all.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("customTexture.")) {
                String sampler = k.substring("customTexture.".length());
                int tex = resolveTexture(e.getValue());
                if (tex == 0) continue;
                UNITS.put(sampler, nextUnit++);
                for (String stage : new String[] {"gbuffers", "deferred", "composite", "prepare", "begin"}) stageTextures.put(stage + "." + sampler, tex);
            } else if (k.startsWith("texture.") && !k.equals("texture.noise")) {
                String[] parts = k.split("\\.", 3);
                if (parts.length < 3) continue;
                int tex = resolveTexture(e.getValue());
                if (tex != 0) stageTextures.put(parts[1] + "." + parts[2], tex);
            }
        }
    }

    /** a pack file (png), a resource location (minecraft:textures/...), or the block atlas */
    private int resolveTexture(String value) {
        step("texture " + value);
        String v = value.trim().split("\\s+")[0];
        if (v.contains(":")) {
            ResourceLocation rl = ResourceLocation.parse(v);
            return Minecraft.getInstance().getTextureManager().getTexture(rl).getId();
        }
        return packTexture(v);
    }

    private int packTexture(String path) {
        byte[] bytes = src.bytes(ShaderPackSource.normalize(path));
        if (bytes == null) {
            problems.add("texture not in the pack: " + path);
            return 0;
        }
        try (NativeImage img = NativeImage.read(new java.io.ByteArrayInputStream(bytes))) {
            int id = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            int w = img.getWidth(), h = img.getHeight();
            byte[] px = new byte[w * h * 4];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int abgr = img.getPixelRGBA(x, y);
                    int o = (y * w + x) * 4;
                    px[o] = (byte) abgr;
                    px[o + 1] = (byte) (abgr >> 8);
                    px[o + 2] = (byte) (abgr >> 16);
                    px[o + 3] = (byte) (abgr >> 24);
                }
            String meta = src.text(ShaderPackSource.normalize(path) + ".mcmeta");
            boolean blur = meta != null && meta.replace(" ", "").contains("\"blur\":true"), clamp = meta != null && meta.replace(" ", "").contains("\"clamp\":true");
            return upload(id, w, h, GL11.GL_RGBA8, GL11.GL_RGBA, px, blur, !clamp);
        } catch (Exception e) {
            problems.add("texture " + path + ": " + e);
            return 0;
        }
    }

    private static int rawTexture(int w, int h, int internal, int format, byte[] px, boolean repeat) {
        return upload(GL11.glGenTextures(), w, h, internal, format, px, true, repeat);
    }

    private static int upload(int id, int w, int h, int internal, int format, byte[] px, boolean linear, boolean repeat) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(px.length);
        buf.put(px).flip();
        // vanilla's texture uploads leave the unpack state set (row length = an atlas's width): rows would be read past
        // this buffer
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        org.lwjgl.system.MemoryUtil.memFree(buf);
        int filter = linear ? GL11.GL_LINEAR : GL11.GL_NEAREST, wrap = repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
        return id;
    }

    // ------------------------------------------------------------------ the frame

    /** LevelRenderer.renderLevel, after vanilla's clear */
    public void beginLevel(Camera camera, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick) {
        if (!prewarmed) prewarm();
        long t0 = STATS ? System.nanoTime() : 0;
        var main = Minecraft.getInstance().getMainRenderTarget();
        targets.resize(main.width, main.height);
        uniforms.beginFrame(camera, frustumMatrix, projectionMatrix, partialTick, main.width, main.height);
        long t1 = STATS ? System.nanoTime() : 0;
        float[] fog = RenderSystem.getShaderFogColor();
        targets.clearAll(fog);
        if (STATS) {
            beginUniformNanos += t1 - t0;
            beginClearNanos += System.nanoTime() - t1;
        }
        inLevel = true;
        ShaderIds.on = true;
        shadowDone = false;
        this.camera = camera;
        this.partialTick = partialTick;
        deferredDone = false;
        handPhase = false;
        phase = "";
        for (CompositeProgram cp : begin) runPass(cp, "begin", false);
        for (CompositeProgram cp : prepare) runPass(cp, "prepare", false);
        bindTextures("gbuffers");
        bindGbuffers(BUFFER0);
    }

    /** a profiler section of renderLevel starts */
    public void phase(String name) {
        if (!inLevel) return;
        if (name.equals("sky") && !shadowDone && shadow != null) {
            shadowDone = true;
            shadow.run(camera, partialTick);
            if (STATS && ++statFrames % 300 == 0) {
                System.out.printf(java.util.Locale.ROOT, "[sleep/shaders] shadow pass: %.2f ms per frame, sections %.0f, draws %.0f (multi-draw layers %.0f), entities %.0f, block entities %.0f%n",
                        shadow.nanos / 1e6 / 300, shadow.sections / 300.0, shadow.draws / 300.0, shadow.multiLayers / 300.0, shadow.entities / 300.0, shadow.blockEntities / 300.0);
                System.out.printf(java.util.Locale.ROOT, "[sleep/shaders] shadow split: collect %.3f, opaque layers %.3f, entities + block entities %.3f, translucent %.3f ms%n",
                        shadow.collectNanos / 1e6 / 300, shadow.opaqueNanos / 1e6 / 300, shadow.entityNanos / 1e6 / 300, shadow.translucentNanos / 1e6 / 300);
                System.out.printf(java.util.Locale.ROOT, "[sleep/shaders] shadow layer setups %.3f ms, per-section draw calls %.3f ms%n", shadow.setupNanos / 1e6 / 300, shadow.drawCallNanos / 1e6 / 300);
                shadow.setupNanos = shadow.drawCallNanos = 0;
                System.out.printf(java.util.Locale.ROOT, "[sleep/shaders] chunk multi-draw: flushes %.3f ms (%.0f draws), shadow list reuses %d%n",
                        dev.lull.optsupport.ChunkMultiDraw.flushNanos / 1e6 / 300, dev.lull.optsupport.ChunkMultiDraw.flushDraws / 300.0, dev.lull.optsupport.ChunkMultiDraw.shadowReuses);
                System.out.printf(java.util.Locale.ROOT, "[sleep/shaders]   flush: offsets upload %.3f, commands upload %.3f, multi-draw calls %.3f ms%n", dev.lull.optsupport.ChunkMultiDraw.offNanos / 1e6 / 300, dev.lull.optsupport.ChunkMultiDraw.cmdUpNanos / 1e6 / 300, dev.lull.optsupport.ChunkMultiDraw.drawNanos / 1e6 / 300);
                System.out.printf(java.util.Locale.ROOT, "[sleep/shaders]   light volumes bind %.3f, draw loop %.3f ms%n", dev.lull.optsupport.ChunkMultiDraw.lvNanos / 1e6 / 300, dev.lull.optsupport.ChunkMultiDraw.loopNanos / 1e6 / 300);
                dev.lull.optsupport.ChunkMultiDraw.lvNanos = dev.lull.optsupport.ChunkMultiDraw.loopNanos = 0;
                dev.lull.optsupport.ChunkMultiDraw.offNanos = dev.lull.optsupport.ChunkMultiDraw.cmdUpNanos = dev.lull.optsupport.ChunkMultiDraw.drawNanos = 0;
                dev.lull.optsupport.ChunkMultiDraw.flushNanos = dev.lull.optsupport.ChunkMultiDraw.flushDraws = dev.lull.optsupport.ChunkMultiDraw.shadowReuses = 0;
                shadow.collectNanos = shadow.opaqueNanos = shadow.entityNanos = shadow.translucentNanos = 0;
                shadow.nanos = shadow.sections = shadow.draws = shadow.multiLayers = shadow.entities = shadow.blockEntities = 0;
                System.out.println("[sleep/shaders] terrain ids: blocks " + ShaderIds.blocks + ", vertices patched " + ShaderIds.patched + " (with an id " + ShaderIds.patchedWithId + ")");
                System.out.printf(java.util.Locale.ROOT, "[sleep/shaders] frame start: uniforms %.3f ms, clears %.3f ms%n", beginUniformNanos / 1e6 / 300, beginClearNanos / 1e6 / 300);
                beginUniformNanos = beginClearNanos = 0;
                System.out.printf(java.util.Locale.ROOT, "[sleep/shaders] uploads: frame values %.3f ms (%.0f calls), per draw %.3f ms (%.0f applies)%n",
                        PackUniforms.frameUploadNanos / 1e6 / 300, PackUniforms.frameUploads / 300.0, PackUniforms.drawUploadNanos / 1e6 / 300, PackUniforms.applies / 300.0);
                PackUniforms.frameUploadNanos = PackUniforms.frameUploads = PackUniforms.drawUploadNanos = PackUniforms.applies = 0;
            }
            bindTextures("gbuffers");
            bindGbuffers(BUFFER0);
        }
        phase = name;
        uniforms.renderStage = switch (name) {
            case "sky" -> 1;
            case "terrain" -> 8;
            case "entities" -> 11;
            case "blockentities" -> 12;
            case "destroyProgress" -> 13;
            case "outline" -> 14;
            case "translucent" -> 17;
            case "string" -> 18;
            case "particles", "solid_particles" -> 19;
            case "clouds" -> 20;
            case "weather" -> 21;
            default -> uniforms.renderStage;
        };
        if (name.equals("translucent") && !deferredDone) {
            deferredDone = true;
            targets.copyDepth(targets.depthTex1);
            for (CompositeProgram cp : deferred) runPass(cp, "deferred", false);
            if (dev.lull.DrawStats.ON) dev.lull.DrawStats.section("translucent");
            afterPasses();
            bindTextures("gbuffers");
            bindGbuffers(BUFFER0);
        }
    }

    /** GameRenderer, before the hand: depthtex2, the hand phase */
    public void beforeHand() {
        if (!inLevel) return;
        if (!deferredDone) phase("translucent");
        targets.copyDepth(targets.depthTex2);
        handPhase = true;
        phase = "hand";
        uniforms.renderStage = 16;
        bindGbuffers(BUFFER0);
    }

    /** GameRenderer.renderLevel's end: composite, final into the window */
    public void endLevel() {
        if (!inLevel) return;
        if (!deferredDone) phase("translucent");
        inLevel = false;
        ShaderIds.on = false;
        if (SHOW != null && SHOW.equals("gbuffers")) {
            show();
            return;
        }
        for (CompositeProgram cp : composite) {
            runPass(cp, "composite", false);
            if (SHOW != null && SHOW.equals(cp.program.name)) {
                show();
                return;
            }
        }
        if (finalPass != null && finalPass.id != 0) runPass(finalPass, "composite", true);
        else blitToMain();
        restoreVanillaState();
        if (dev.lull.DrawStats.ON) dev.lull.DrawStats.section("(after passes)");
    }

    /** -Dsleep.shaders.show=gbuffers|<pass> (+ showBuffer=N): colortex N as it is after that point, on the screen */
    private static final String SHOW = System.getProperty("sleep.shaders.show");
    private static final int SHOW_BUFFER = Integer.getInteger("sleep.shaders.showBuffer", 0);
    private static final int[] SHOW_BUFFERS = {SHOW_BUFFER};

    private void show() {
        var main = Minecraft.getInstance().getMainRenderTarget();
        int read = targets.framebuffer(SHOW_BUFFERS, false, false);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, targets.width, targets.height, 0, 0, main.width, main.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        restoreVanillaState();
    }

    /** the gbuffers framebuffer for these draw buffers (vanilla's main-target binds land here during the level) */
    public void bindGbuffers(int[] drawBuffers) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targets.framebuffer(drawBuffers, false, true));
        GL11.glViewport(0, 0, targets.width, targets.height);
    }

    private void runPass(CompositeProgram cp, String stage, boolean isFinal) {
        if (dev.lull.DrawStats.ON) dev.lull.DrawStats.section("pass/" + cp.program.name);   // GPU time per pass (string only with drawStats)
        for (int i = 0; i < RenderTargets.COLOR_TEX; i++) if (cp.wantsMipmap[i]) targets.generateMipmap(i);
        bindTextures(stage, cp.wantsMipmap);
        int[] db = cp.program.drawBuffers;
        if (isFinal) {
            var main = Minecraft.getInstance().getMainRenderTarget();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.frameBufferId);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glViewport(0, 0, main.width, main.height);
        } else {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targets.framebuffer(db, true, false));
            GL11.glViewport(0, 0, targets.width, targets.height);
        }
        // through GlStateManager: a deferred pass runs inside the level render, and a raw glDisable(DEPTH_TEST) left
        // its cache saying "enabled" - the water after it was drawn without a depth test (11-02: water and
        // caves through the terrain)
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.colorMask(true, true, true, true);
        if (cp.id != 0) {
            GL20.glUseProgram(cp.id);
            uniforms.uploadFrame(cp.binding);
            uniforms.uploadDraw(cp.binding, IDENTITY, IDENTITY);
            CompositeProgram.drawQuad();
        }
        if (!isFinal && cp.id != 0) {
            for (int b : db) {
                String f = props.get("flip." + cp.program.name + ".colortex" + b);
                if (f == null || f.equals("true")) targets.flip(b);
            }
        }
    }

    private static final Matrix4f IDENTITY = new Matrix4f();

    /** back into the level render after passes inside it: vanilla's program / buffer caches forget what we bound */
    private void afterPasses() {
        GL20.glUseProgram(0);
        ShaderPipelineHooks.resetProgramCache();
        com.mojang.blaze3d.vertex.BufferUploader.reset();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    /** no final program: colortex0 to the window as it is */
    private void blitToMain() {
        var main = Minecraft.getInstance().getMainRenderTarget();
        int read = targets.framebuffer(BUFFER0, false, false);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, targets.width, targets.height, 0, 0, main.width, main.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
    }

    /** the pack textures on their fixed units (main of each colortex; a stage's custom textures in place of samplers) */
    void bindTextures(String stage) {
        bindTextures(stage, NO_MIPMAPS);
    }

    private static final boolean[] NO_MIPMAPS = new boolean[RenderTargets.COLOR_TEX];

    /**
     * mipmapped: the buffers this pass asked mipmaps for get a mipmap filter, every other one plain LINEAR (as Iris: a
     * texture() in non-uniform control flow - Complementary's FXAA - may pick a level above 0, never filled for it)
     */
    void bindTextures(String stage, boolean[] mipmapped) {
        int active = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
        int[] over = stageColor(stage);
        for (int i = 0; i < RenderTargets.COLOR_TEX; i++) {
            int custom = over[i];
            int tex = custom != 0 ? custom : targets.used[i] ? targets.main(i) : 0;
            bind(12 + i, tex);
            if (custom == 0 && tex != 0 && targets.mipmapped(i) && !RenderTargets.isInteger(targets.internalFormat[i]))
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mipmapped[i] ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        }
        bind(28, targets.depthTex0);
        bind(29, targets.depthTex1);
        bind(30, targets.depthTex2);
        ShadowTargets st = shadow == null ? null : shadow.targets;
        bind(31, st == null ? shadowDepthTex : st.depth0);
        bind(32, st == null ? shadowDepthTex : st.depth1);
        bind(33, st == null ? shadowColorTex : st.color[0]);
        bind(34, st == null ? shadowColorTex : st.color[1]);
        bind(35, noiseTex);
        bind(36, normalsTex);
        bind(37, specularTex);
        int[] extra = stageExtra.get(stage);
        if (extra == null) stageColor(stage);
        extra = stageExtra.get(stage);
        for (int k = 0; k + 1 < extra.length; k += 2) bind(extra[k], extra[k + 1]);
        GL13.glActiveTexture(active);
    }

    /** a stage's texture overrides, looked up by name once: colortex0..15 (0 = none) and {unit, texture} pairs */
    private final Map<String, int[]> stageColor = new HashMap<>(), stageExtra = new HashMap<>();

    private int[] stageColor(String stage) {
        int[] over = stageColor.get(stage);
        if (over != null) return over;
        over = new int[RenderTargets.COLOR_TEX];
        for (int i = 0; i < RenderTargets.COLOR_TEX; i++) {
            Integer custom = stageTextures.get(stage + ".colortex" + i);
            if (custom == null && i >= 4 && i < 8) custom = stageTextures.get(stage + ".gaux" + (i - 3));
            over[i] = custom == null ? 0 : custom;
        }
        List<Integer> pairs = new ArrayList<>();
        for (var e : UNITS.entrySet()) {
            if (e.getValue() < 38) continue;
            Integer t = stageTextures.get(stage + "." + e.getKey());
            if (t == null) continue;
            pairs.add(e.getValue());
            pairs.add(t);
        }
        int[] extra = new int[pairs.size()];
        for (int k = 0; k < extra.length; k++) extra[k] = pairs.get(k);
        stageColor.put(stage, over);
        stageExtra.put(stage, extra);
        return over;
    }

    private static void bind(int unit, int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
    }

    /** after our raw GL: vanilla's caches (program in use, immediate buffer) see the truth again */
    private void restoreVanillaState() {
        GL20.glUseProgram(0);
        ShaderPipelineHooks.resetProgramCache();
        com.mojang.blaze3d.vertex.BufferUploader.reset();
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    // ------------------------------------------------------------------ programs in place of vanilla's

    private boolean prewarmed;
    /** the phases a vanilla shader can be drawn in: phase name, hand, shadow, shadow block entities */
    private static final Object[][] PHASES = {{"", false, false, false}, {"sky", false, false, false}, {"clouds", false, false, false},
            {"weather", false, false, false}, {"blockentities", false, false, false}, {"particles", false, false, false},
            {"hand", true, false, false}, {"", false, true, false}, {"", false, true, true}};

    /**
     * Every program this pack can give each loaded vanilla shader, in every phase, compiled before the pack draws -
     * not at the moment a mob or an effect is first drawn (a 20-100 ms hitch each). A new pack is prepared over frames
     * (stepPrewarm, a time budget per frame) while the game draws vanilla, with the progress on the screen, and is
     * switched on when ready: a driver's first compile of a pack is long (Intel UHD, Complementary: 57 s; later starts
     * 6 s from the driver's own cache), and the game no longer stops for it. After a resource reload (new vanilla
     * instances) the rest is prepared at once (prewarm).
     */
    private void prewarm() {
        startPrewarm();
        stepPrewarm(Long.MAX_VALUE);
    }

    /** what is left to build: {vanilla instance, phase row}; the sources made on other threads meanwhile */
    private java.util.ArrayDeque<Object[]> prewarmQueue;
    private java.util.concurrent.CompletableFuture<Void> prewarmSources;
    private int prewarmTotal, prewarmBefore;
    private long prewarmStart;

    /** the work of a prewarm, planned (render thread: cheap); the sources start on the common pool */
    private void startPrewarm() {
        if (prewarmQueue != null) return;
        prewarmStart = System.nanoTime();
        var caps = org.lwjgl.opengl.GL.getCapabilities();
        if (caps.GL_KHR_parallel_shader_compile) org.lwjgl.opengl.KHRParallelShaderCompile.glMaxShaderCompilerThreadsKHR(0xFFFFFFFF);
        Map<String, ShaderInstance> vanilla = ((dev.lull.shadermixin.ShaderGameRendererShadersAccessor) Minecraft.getInstance().gameRenderer).sleep$shaders();
        prewarmBefore = shared.size();
        prewarmQueue = new java.util.ArrayDeque<>();
        Map<String, Object[]> specs = new HashMap<>();
        withPhase(null, () -> {
            for (ShaderInstance v : vanilla.values()) {
                for (Object[] ph : PHASES) {
                    setPhase(ph);
                    if (shadowPhase && shadow == null) continue;
                    prewarmQueue.add(new Object[] {v, ph});
                    String program = programFor(v.getName());
                    if (program == null) continue;
                    ProgramSet.Program p = set.resolve(program);
                    if (p == null || p.sources[0] == null || p.sources[2] == null) continue;
                    String alpha = alphaTest(v.getName(), program);
                    boolean be = program.startsWith("gbuffers_block") || program.equals("shadow_block");
                    String key = p.name + "|" + System.identityHashCode(v.getVertexFormat()) + "|" + alpha + "|" + be;
                    if (!shared.containsKey(key)) specs.putIfAbsent(key, new Object[] {p, v.getVertexFormat(), alpha, program});
                }
            }
        });
        prewarmTotal = specs.size();
        // the sources: string work only, on every core, while the render thread goes on
        prewarmSources = java.util.concurrent.CompletableFuture.runAsync(() -> {
            long tp = System.nanoTime();
            specs.entrySet().parallelStream().forEach(e -> {
                Object[] sp = e.getValue();
                ProgramSet.Program p = (ProgramSet.Program) sp[0];
                try {
                    prepared.put(e.getKey(), PackShader.prepare(uniqueName(p, e.getKey()), p, (com.mojang.blaze3d.vertex.VertexFormat) sp[1], (String) sp[2],
                            (String) sp[3]));
                } catch (Exception ex) {
                    System.out.println("[sleep/shaders] preparing " + p.name + ": " + ex);
                }
            });
            PackShader.prepNanos += System.nanoTime() - tp;
        });
    }

    /**
     * Builds programs until budgetNanos is spent (at least one); true when every one is built. Waits for the sources
     * only with an unlimited budget.
     */
    private boolean stepPrewarm(long budgetNanos) {
        startPrewarm();
        if (prewarmed) return true;
        if (budgetNanos == Long.MAX_VALUE) prewarmSources.join();
        else if (!prewarmSources.isDone()) return false;
        long end = System.nanoTime() + budgetNanos;
        withPhase(null, () -> {
            while (!prewarmQueue.isEmpty()) {
                Object[] item = prewarmQueue.poll();
                setPhase((Object[]) item[1]);
                shaderFor((ShaderInstance) item[0]);
                if (budgetNanos != Long.MAX_VALUE && System.nanoTime() > end) break;
            }
        });
        if (!prewarmQueue.isEmpty()) return false;
        prewarmed = true;
        prewarmQueue = null;
        ShaderPipelineHooks.resetProgramCache();
        System.out.printf("[sleep/shaders] prepared %d program instances (%d vanilla shader / phase pairs) in %.0f ms"
                + " (sources %.0f ms, compile + link %.0f ms; binary cache: %d loaded, %d compiled, %d refused)%n", shared.size() - prewarmBefore, countShaders(),
                (System.nanoTime() - prewarmStart) / 1e6, PackShader.prepNanos / 1e6, PackShader.glNanos / 1e6, ProgramBinaryCache.hits, ProgramBinaryCache.misses,
                ProgramBinaryCache.rejected);
        return true;
    }

    /** programs built so far / to build (the progress shown while a pack is prepared) */
    public int prewarmBuilt() {
        return shared.size() - prewarmBefore;
    }

    public int prewarmTotal() {
        return prewarmTotal;
    }

    private void setPhase(Object[] ph) {
        phase = (String) ph[0];
        handPhase = (Boolean) ph[1];
        shadowPhase = (Boolean) ph[2];
        shadowBlockEntitiesPhase = (Boolean) ph[3];
    }

    /** runs r with inLevel on and the phase fields restored after */
    private void withPhase(Object[] ph, Runnable r) {
        String savedPhase = phase;
        boolean savedHand = handPhase, savedShadow = shadowPhase, savedBe = shadowBlockEntitiesPhase, savedIn = inLevel;
        inLevel = true;
        try {
            if (ph != null) setPhase(ph);
            r.run();
        } finally {
            phase = savedPhase;
            handPhase = savedHand;
            shadowPhase = savedShadow;
            shadowBlockEntitiesPhase = savedBe;
            inLevel = savedIn;
        }
    }

    private int countShaders() {
        int n = 0;
        for (Map<String, PackShader> m : shaders.values()) n += m.size();
        return n;
    }

    /** a resource reload made new vanilla instances: prepare again */
    public void reloaded() {
        prewarmed = false;
        prewarmQueue = null;
    }

    /** the pack's program for a vanilla shader in the current phase, or vanilla's own */
    public ShaderInstance shaderFor(ShaderInstance vanilla) {
        if (!inLevel || vanilla == null || vanilla instanceof PackShader) return vanilla;
        String program = programFor(vanilla.getName());
        if (program == null) return vanilla;
        ProgramSet.Program p = set.resolve(program);
        if (p == null || p.sources[0] == null || p.sources[2] == null) return vanilla;
        // by the vanilla instance, then the program name (a literal): no key string built per setShader call
        Map<String, PackShader> byProgram = shaders.get(vanilla);
        if (byProgram == null) shaders.put(vanilla, byProgram = new HashMap<>());
        PackShader s = byProgram.get(program);
        if (s == null && !byProgram.containsKey(program)) {
            // one instance for every vanilla shader giving the same source: the pack program, the vertex format, the
            // alpha test, which id the overlay slot holds (11-02: 326 instances, 88 ms each, became a few dozen)
            String alpha = alphaTest(vanilla.getName(), program);
            boolean be = program.startsWith("gbuffers_block") || program.equals("shadow_block");
            String key = p.name + "|" + System.identityHashCode(vanilla.getVertexFormat()) + "|" + alpha + "|" + be;
            s = shared.get(key);
            if (s == null && !shared.containsKey(key)) {
                try {
                    PackShader.Prepared pr = prepared.remove(key);
                    s = pr != null ? PackShader.build(pr, p, vanilla.getVertexFormat())
                            : PackShader.create(uniqueName(p, key), p, vanilla.getVertexFormat(), alpha, program);
                } catch (Exception e) {
                    System.out.println("[sleep/shaders] " + p.name + " for " + vanilla.getName() + ": " + e);
                    s = null;
                }
                shared.put(key, s);
            }
            byProgram.put(program, s);
        }
        return s != null ? s : vanilla;
    }

    /**
     * The alpha test of a legacy program for a vanilla shader: alphaTest.<program> from the pack, else Iris 1.8.14's
     * default for that kind of geometry (ShaderKey: solid / translucent terrain, lines, beams, sky off; cutout, entities,
     * particles, text, weather, clouds 0.1; glint, eyes, plain textured / coloured 1e-4).
     */
    String alphaTest(String vanilla, String program) {
        String p = props.get("alphaTest." + program);
        if (p != null) return p.equalsIgnoreCase("off") ? null : p;
        switch (vanilla) {
            case "rendertype_solid", "rendertype_translucent", "rendertype_tripwire", "rendertype_lightning", "rendertype_leash", "rendertype_beacon_beam",
                    "rendertype_lines", "position", "rendertype_entity_solid", "rendertype_end_portal", "rendertype_end_gateway":
                return null;
            case "rendertype_eyes", "rendertype_glint", "rendertype_glint_translucent", "rendertype_entity_glint", "rendertype_entity_glint_direct",
                    "rendertype_armor_glint", "rendertype_armor_entity_glint", "position_color", "position_tex":
                return "GREATER 0.0001";
            default:
        }
        if (phase.equals("sky")) return null;
        return "GREATER 0.1";
    }

    String programFor(String v) {
        if (shadowPhase) {
            switch (v) {
                case "rendertype_solid":
                    return shadowBlockEntitiesPhase ? "shadow_block" : "shadow_solid";
                case "rendertype_cutout_mipped", "rendertype_cutout":
                    return shadowBlockEntitiesPhase ? "shadow_block" : "shadow_cutout";
                case "rendertype_translucent", "rendertype_tripwire":
                    return shadowBlockEntitiesPhase ? "shadow_block" : "shadow_water";
                case "rendertype_lightning":
                    return "shadow_lightning";
                default:
                    return shadowBlockEntitiesPhase ? "shadow_block" : v.startsWith("rendertype_") ? "shadow_entities" : "shadow";
            }
        }
        switch (phase) {
            case "sky":
                return v.equals("position_tex") || v.equals("position_tex_color") ? "gbuffers_skytextured" : "gbuffers_skybasic";
            case "clouds":
                return "gbuffers_clouds";
            case "weather":
                return v.equals("rendertype_world_border") ? "gbuffers_textured" : v.startsWith("particle") ? "gbuffers_weather" : null;
            default:
        }
        boolean hand = handPhase, be = phase.equals("blockentities");
        switch (v) {
            case "rendertype_solid", "rendertype_cutout_mipped", "rendertype_cutout":
                return hand ? "gbuffers_hand" : be ? "gbuffers_block" : "gbuffers_terrain";
            case "rendertype_translucent", "rendertype_tripwire", "rendertype_translucent_moving_block":
                return hand ? "gbuffers_hand_water" : be ? "gbuffers_block_translucent" : "gbuffers_water";
            case "particle":
                return phase.equals("particles") ? "gbuffers_particles_translucent" : "gbuffers_particles";
            case "rendertype_lines":
                return "gbuffers_line";
            case "rendertype_crumbling":
                return "gbuffers_damagedblock";
            case "rendertype_beacon_beam":
                return "gbuffers_beaconbeam";
            case "rendertype_lightning":
                return "gbuffers_lightning";
            case "rendertype_eyes":
                return "gbuffers_spidereyes";
            case "rendertype_armor_glint", "rendertype_armor_entity_glint", "rendertype_glint", "rendertype_glint_translucent", "rendertype_entity_glint",
                    "rendertype_entity_glint_direct":
                return "gbuffers_armor_glint";
            case "rendertype_leash", "position_color", "rendertype_debug_line_strip", "rendertype_debug_quads":
                return "gbuffers_basic";
            case "rendertype_world_border":
                return "gbuffers_textured";
            case "rendertype_end_portal", "rendertype_end_gateway":
                return null;
            default:
        }
        if (v.startsWith("rendertype_entity") || v.startsWith("rendertype_item_entity") || v.startsWith("rendertype_armor") || v.startsWith("rendertype_text")
                || v.startsWith("rendertype_energy_swirl") || v.startsWith("rendertype_breeze_wind")) {
            boolean translucent = v.contains("translucent");
            if (hand) return translucent ? "gbuffers_hand_water" : "gbuffers_hand";
            if (be) return translucent ? "gbuffers_block_translucent" : "gbuffers_block";
            return translucent ? "gbuffers_entities_translucent" : "gbuffers_entities";
        }
        return null;
    }

    // ------------------------------------------------------------------ blend overrides (blend.<program>[.<buffer>])

    private final int[] savedBlend = new int[4];
    private boolean savedBlendOn, perBufferApplied;

    /** a program's blend.* overrides, read once: [0] the whole program's (null / {-1} off / 4 factors), then per output */
    private final Map<String, int[][]> blendSpecs = new HashMap<>();

    private int[][] blendSpec(String program) {
        int[][] spec = blendSpecs.get(program);
        if (spec != null) return spec;
        ProgramSet.Program p = set.programs.get(program);
        int n = p == null ? 0 : p.drawBuffers.length;
        spec = new int[1 + n][];
        String all = props.get("blend." + program);
        spec[0] = all == null ? null : all.equalsIgnoreCase("off") ? new int[] {-1} : factors(all);
        for (int i = 0; i < n; i++) {
            String b = props.get("blend." + program + ".colortex" + p.drawBuffers[i]);
            spec[1 + i] = b == null ? null : b.equalsIgnoreCase("off") ? new int[] {-1} : factors(b);
        }
        blendSpecs.put(program, spec);
        return spec;
    }

    void applyBlend(String program) {
        int[][] spec = blendSpec(program);
        int[] all = spec[0];
        if (all != null) {
            if (all.length == 1) RenderSystem.disableBlend();
            else {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(all[0], all[1], all[2], all[3]);
            }
        }
        perBufferApplied = false;
        for (int i = 0; i + 1 < spec.length; i++) {
            int[] b = spec[1 + i];
            if (b == null) continue;
            if (!perBufferApplied) {
                perBufferApplied = true;
                savedBlendOn = GL11.glIsEnabled(GL11.GL_BLEND);
                savedBlend[0] = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
                savedBlend[1] = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
                savedBlend[2] = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
                savedBlend[3] = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            }
            if (b.length == 1) GL30.glDisablei(GL11.GL_BLEND, i);
            else {
                GL30.glEnablei(GL11.GL_BLEND, i);
                GL40.glBlendFuncSeparatei(i, b[0], b[1], b[2], b[3]);
            }
        }
    }

    void clearBlend(String program) {
        if (!perBufferApplied) return;
        perBufferApplied = false;
        if (savedBlendOn) GL11.glEnable(GL11.GL_BLEND);
        else GL11.glDisable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(savedBlend[0], savedBlend[1], savedBlend[2], savedBlend[3]);
    }

    private static int[] factors(String s) {
        String[] p = s.trim().split("\\s+");
        if (p.length != 4) return null;
        int[] f = new int[4];
        for (int i = 0; i < 4; i++) {
            f[i] = switch (p[i]) {
                case "ZERO" -> GL11.GL_ZERO;
                case "ONE" -> GL11.GL_ONE;
                case "SRC_COLOR" -> GL11.GL_SRC_COLOR;
                case "ONE_MINUS_SRC_COLOR" -> GL11.GL_ONE_MINUS_SRC_COLOR;
                case "DST_COLOR" -> GL11.GL_DST_COLOR;
                case "ONE_MINUS_DST_COLOR" -> GL11.GL_ONE_MINUS_DST_COLOR;
                case "SRC_ALPHA" -> GL11.GL_SRC_ALPHA;
                case "ONE_MINUS_SRC_ALPHA" -> GL11.GL_ONE_MINUS_SRC_ALPHA;
                case "DST_ALPHA" -> GL11.GL_DST_ALPHA;
                case "ONE_MINUS_DST_ALPHA" -> GL11.GL_ONE_MINUS_DST_ALPHA;
                case "SRC_ALPHA_SATURATE" -> GL11.GL_SRC_ALPHA_SATURATE;
                default -> -1;
            };
            if (f[i] < 0) return null;
        }
        return f;
    }
}
