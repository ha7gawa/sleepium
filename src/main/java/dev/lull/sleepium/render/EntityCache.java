package dev.lull.sleepium.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.SleepGlState;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;

/**
 * The render cache for 1.12 (-Dsleep.opt.entityCache, lab): an entity's or block entity's drawing recorded once and
 * drawn from video memory afterwards, with every other cached object that needs the same GL state in one draw.
 *
 * <p>Recording: the object is drawn the vanilla way while its output is captured - model parts (ModelRenderer, through
 * PartBatch's mirror of the model matrix), Tessellator draws, the item / block model buffers - into one vertex layout
 * (position, uv, colour, normal, lightmap; 32 bytes) in the object's own space (minus the camera-relative position it
 * was drawn at), each piece with the GL state it was drawn in (SleepGlState). Two captures in a row the same (another
 * partial tick, another time): the object is still and is drawn from the cache; different: it moves (left to vanilla).
 * Anything the capture cannot represent (another display list, immediate mode, the texture matrix, texture
 * environment, shaders) leaves the object to vanilla.
 *
 * <p>Drawing: at the end of the entity pass, per GL state one glMultiDrawArrays over the cached objects seen this frame,
 * from a GL buffer of all cached objects' vertices (world positions relative to an origin). Differences from vanilla:
 * float rounding (positions computed on the CPU) and the order of equal-depth pixels.
 *
 * <p>Lab state: no change detection yet beyond the probe - an object is recorded again only when it leaves and comes
 * back. Measuring the ceiling on still scenes.
 */
public final class EntityCache {
    private EntityCache() {}

    public static final boolean ON = Boolean.getBoolean("sleep.opt.entityCache");
    private static final int STRIDE = 32;

    /** one object's recording */
    static final class Rec {
        int stage;                 // 0 none, 2 ready
        byte[] verts;              // STRIDE bytes per vertex, positions relative to the anchor
        int vertCount;
        List<int[]> sigs = new ArrayList<>();
        int[] segFirst = new int[8], segCount = new int[8], segMode = new int[8];
        int segs;
        double ax, ay, az;         // the anchor: where the object is (absolute)
        float lightX, lightY;      // the lightmap coordinates it was drawn with (its light)
        long seenFrame, vanillaFrame;
        int seenPass, vanillaPass;
        // validity (as 1.20.1's RenderCache): the state hash once a tick, renewal, probes, skipped while animated
        long state, stateNow, hashTick = Long.MIN_VALUE, refreshAt, probedAt = Long.MIN_VALUE / 2, skipUntil = Long.MIN_VALUE;
        long[] fieldsAt;
        int[] start;               // the state the drawing began in (normalized): what it inherits from what came before
        String cls;
    }

    /** the object drawn again for a probe (another partial tick), its output captured, nothing written to the screen */
    public interface Probe {
        void draw(float partialTicks);
    }

    private static final Map<Object, Rec> RECS = new IdentityHashMap<>();
    private static long frame;
    private static boolean dirty;
    public static long hits, captures, recorded, sameAfterChange, animated, unsupported, refreshes, probes;
    private static final Map<String, Integer> WHY = new HashMap<>();
    private static final int VOLATILE_TICKS = 40, PROBE_EVERY = 200, REFRESH = Math.max(1, Integer.getInteger("sleep.rcache.refresh", 20));
    private static final boolean LEARN = !"false".equals(System.getProperty("sleep.rcache.learnFields"));

    // ------------------------------------------------------------------ capture state

    private static Rec cap;            // the recording under way (null: none)
    private static Object capKey;
    private static int capDepth;       // nested objects inside the one being captured
    private static boolean capBad;
    private static double capX, capY, capZ, capAbsX, capAbsY, capAbsZ;
    private static float capLightX, capLightY;
    private static long capTick;
    private static ByteBuffer cb = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
    private static int cVerts;
    private static final List<int[]> cSigs = new ArrayList<>();
    private static int[] cFirst = new int[16], cCount = new int[16], cMode = new int[16];
    private static int cSegs;
    private static int partDepth;      // inside a model part tree already captured
    /** a probe's dry run: GL's colour and depth writes held off (GlStateManager.colorMask / depthMask ignored meanwhile) */
    public static boolean dry;

    public static boolean capturing() {
        return cap != null && !capBad;
    }

    /** the object being recorded ends now (not a nested one): end() wants its probe */
    public static boolean endingRecording() {
        return cap != null && capDepth == 0;
    }

    private static long tick() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        return mc.world == null ? 0 : mc.world.getTotalWorldTime();
    }

    /**
     * An object is about to be drawn (key: the entity / block entity; x y z: where, camera-relative; cam: the camera).
     * True: drawn from the cache this frame (skip vanilla).
     */
    public static boolean begin(Object key, boolean allowed, double x, double y, double z, double camX, double camY, double camZ, int pass) {
        if (!ON) return false;
        if (cap != null) {   // inside a recording: part of it (drawn the vanilla way)
            capDepth++;
            if (!allowed) unsupported("a renderer inside not the game's");
            return false;
        }
        if (!allowed || !StateHash.available() || !SleepGlState.init()) return false;
        long tick = tick();
        Rec r = RECS.get(key);
        if (r == null) RECS.put(key, r = new Rec());
        r.seenFrame = frame;
        r.seenPass = pass;
        if (r.skipUntil > tick) return false;   // animated / unsupported a while ago: the vanilla way for now
        if (r.hashTick != tick) {   // the whole state, once per tick (packets and ticks change it)
            r.stateNow = StateHash.of(key);
            r.hashTick = tick;
        }
        double ax = x + camX, ay = y + camY, az = z + camZ;
        if (r.stage == 2 && ax == r.ax && ay == r.ay && az == r.az && r.lightX == OpenGlHelper.lastBrightnessX && r.lightY == OpenGlHelper.lastBrightnessY
                && r.state == r.stateNow && tick < r.refreshAt) {
            hits++;
            return true;
        }
        if (r.stage == 2 && tick >= r.refreshAt) refreshes++;
        r.vanillaFrame = frame;   // drawn by vanilla this frame (the pass's replay leaves it out)
        r.vanillaPass = pass;
        cap = r;
        capKey = key;
        capDepth = 0;
        capBad = false;
        capX = x;
        capY = y;
        capZ = z;
        capAbsX = ax;
        capAbsY = ay;
        capAbsZ = az;
        capLightX = OpenGlHelper.lastBrightnessX;
        capLightY = OpenGlHelper.lastBrightnessY;
        capTick = tick;
        startCapture();
        return false;
    }

    private static int[] cStart;

    private static void startCapture() {
        cStart = SleepGlState.normalized(SleepGlState.snapshot());
        cb.clear();
        cVerts = 0;
        cSigs.clear();
        cSegs = 0;
        partDepth = 0;
    }

    /** the object's drawing ended (probe: draws it again for the probes; null: no probes possible) */
    public static void end(String cls, Probe probe) {
        if (!ON || cap == null) return;
        if (capDepth > 0) {
            capDepth--;
            return;
        }
        Rec r = cap;
        Object key = capKey;
        long tick = capTick;
        r.cls = cls;
        captures++;
        if (capBad) {
            cap = null;
            drop(r, tick);
            unsupported++;
            return;
        }
        Rec now = snapshotCapture();
        boolean sameOut = r.stage == 2 && r.ax == capAbsX && r.ay == capAbsY && r.az == capAbsZ && r.lightX == capLightX && r.lightY == capLightY && same(r, now, false);
        if (LEARN && r.stage == 2 && r.state != r.stateNow && r.fieldsAt != null) {   // only the state changed: does it draw something else?
            long[] f = StateHash.fields(key);
            if (sameOut) {
                StateHash.unchanged(key, r.fieldsAt, f);
                sameAfterChange++;
            } else StateHash.changedOutput(key, r.fieldsAt, f);
        }
        boolean changed = !sameOut;
        if (changed && r.stage == 2) why("changed " + cls + ": " + (r.ax != capAbsX || r.ay != capAbsY || r.az != capAbsZ ? "moved"
                : r.lightX != capLightX || r.lightY != capLightY ? "light" : r.vertCount != cVerts ? "vertices" : r.segs != cSegs ? "pieces" : diffWhere(r, now)));
        if ((changed || tick - r.probedAt >= PROBE_EVERY) && probe != null) {
            r.probedAt = tick;
            if (animated(key, probe, now)) {
                cap = null;
                drop(r, tick);
                animated++;
                why("animated: " + cls);
                return;
            }
        }
        cap = null;
        if (changed) {
            r.verts = now.verts;
            r.vertCount = now.vertCount;
            r.sigs = now.sigs;
            r.segFirst = now.segFirst;
            r.segCount = now.segCount;
            r.segMode = now.segMode;
            r.segs = now.segs;
            r.start = now.start;
            r.ax = capAbsX;
            r.ay = capAbsY;
            r.az = capAbsZ;
            r.lightX = capLightX;
            r.lightY = capLightY;
            dirty = true;
            recorded++;
        }
        r.stage = 2;
        r.state = r.stateNow;
        if (LEARN) r.fieldsAt = StateHash.fields(key);
        // safety net for what no key covers: recorded again after REFRESH..2*REFRESH ticks, spread over objects
        r.refreshAt = tick + REFRESH + Math.floorMod(System.identityHashCode(key), REFRESH);
    }

    private static void drop(Rec r, long tick) {
        r.skipUntil = tick + VOLATILE_TICKS;
        if (r.stage == 2) dirty = true;
        r.stage = 0;
    }

    /** the capture so far as a recording */
    private static Rec snapshotCapture() {
        Rec n = new Rec();
        n.verts = Arrays.copyOf(cb.array(), cVerts * STRIDE);
        n.vertCount = cVerts;
        n.sigs = new ArrayList<>(cSigs);
        n.segFirst = Arrays.copyOf(cFirst, cSegs);
        n.segCount = Arrays.copyOf(cCount, cSegs);
        n.segMode = Arrays.copyOf(cMode, cSegs);
        n.segs = cSegs;
        n.start = cStart;
        return n;
    }

    /**
     * Probes (1.20.1's): the object drawn again dry with another partial tick, with the camera turned, with its tick
     * count moved on; any difference from the capture = it animates (drawn the vanilla way for a while).
     */
    private static boolean animated(Object key, Probe probe, Rec main) {
        net.minecraft.client.renderer.entity.RenderManager rm = net.minecraft.client.Minecraft.getMinecraft().getRenderManager();
        float pt = net.minecraft.client.Minecraft.getMinecraft().getRenderPartialTicks();
        if (!dryEquals(probe, pt < 0.5f ? pt + 0.5f : pt - 0.5f, main)) return probeWhy("partial tick", main);
        float vy = rm.playerViewY, vx = rm.playerViewX;
        rm.playerViewY = vy + 75;
        rm.playerViewX = vx + 23;
        boolean same;
        try {
            same = dryEquals(probe, pt, main);
        } finally {
            rm.playerViewY = vy;
            rm.playerViewX = vx;
        }
        if (!same) return probeWhy("camera", main);
        if (key instanceof net.minecraft.entity.Entity) {
            StateHash.addTicks(key, 7);
            try {
                same = dryEquals(probe, pt, main);
            } finally {
                StateHash.addTicks(key, -7);
            }
        }
        return !same && probeWhy("ticks", main);
    }

    private static boolean probeWhy(String probe, Rec main) {
        Rec now = snapshotCapture();
        why("probe " + probe + ": " + (main.vertCount != now.vertCount ? "vertices " + main.vertCount + "->" + now.vertCount : main.segs != now.segs ? "pieces" : diffWhere(main, now)));
        return true;
    }

    private static boolean dryEquals(Probe probe, float pt, Rec main) {
        probes++;
        startCapture();
        capBad = false;
        boolean depth = SleepGlState.depthMask();
        int color = SleepGlState.colorMask();
        GlStateManager.depthMask(false);
        GlStateManager.colorMask(false, false, false, false);
        dry = true;
        try {
            probe.draw(pt);
        } finally {
            dry = false;
            GlStateManager.depthMask(depth);
            GlStateManager.colorMask((color & 1) != 0, (color & 2) != 0, (color & 4) != 0, (color & 8) != 0);
        }
        return !capBad && same(main, snapshotCapture(), true);
    }

    /** where two captures of the same size differ first: a state index or a vertex attribute offset */
    private static String diffWhere(Rec r, Rec c) {
        for (int i = 0; i < c.segs; i++) {
            if (r.segFirst[i] != c.segFirst[i] || r.segCount[i] != c.segCount[i] || r.segMode[i] != c.segMode[i]) return "piece layout";
            int[] a = r.sigs.get(i), b = c.sigs.get(i);
            for (int k = 0; k < a.length; k++) if (a[k] != b[k]) return "state[" + k + "] " + a[k] + "->" + b[k];
        }
        ByteBuffer a = ByteBuffer.wrap(r.verts).order(ByteOrder.LITTLE_ENDIAN), b = ByteBuffer.wrap(c.verts).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < c.vertCount; i++) {
            int o = i * STRIDE;
            for (int k = 0; k < 3; k++) if (Math.abs(a.getFloat(o + k * 4) - b.getFloat(o + k * 4)) > 1e-4f) return "position " + a.getFloat(o + k * 4) + "->" + b.getFloat(o + k * 4);
            for (int k = 12; k < STRIDE; k++) if (r.verts[o + k] != c.verts[o + k]) return "byte " + k;
        }
        return "?";
    }

    /** two captures: the same pieces, states and vertices (positions within float noise); dry: the write masks not compared */
    private static boolean same(Rec r, Rec c, boolean dryRun) {
        if (r.vertCount != c.vertCount || r.segs != c.segs) return false;
        for (int i = 0; i < c.segs; i++) {
            if (r.segFirst[i] != c.segFirst[i] || r.segCount[i] != c.segCount[i] || r.segMode[i] != c.segMode[i]) return false;
            int[] a = r.sigs.get(i), b = c.sigs.get(i);
            // a state both drawings only inherited from what was drawn before them (culling for the chest, rescaled normals
            // for an item frame's item: their renderers never set it) flips with the order neighbours are recorded in -
            // vanilla's own order-dependence: not a change, the recording keeps its first one
            for (int k = 0; k < a.length; k++)
                if (a[k] != b[k] && !(dryRun && (k == 18 || k == 29)) && !(r.start != null && c.start != null && a[k] == r.start[k] && b[k] == c.start[k])) return false;
        }
        ByteBuffer a = ByteBuffer.wrap(r.verts).order(ByteOrder.LITTLE_ENDIAN), b = ByteBuffer.wrap(c.verts).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < c.vertCount; i++) {
            int o = i * STRIDE;
            for (int k = 0; k < 3; k++) if (Math.abs(a.getFloat(o + k * 4) - b.getFloat(o + k * 4)) > 1e-4f) return false;
            for (int k = 12; k < STRIDE; k++) if (r.verts[o + k] != c.verts[o + k]) return false;
        }
        return true;
    }

    /** something the capture cannot represent happened while recording */
    public static void unsupported(String why) {
        if (cap == null || capBad) return;
        capBad = true;
        why(why);
    }

    private static void why(String w) {
        WHY.merge(w, 1, Integer::sum);
    }

    // ------------------------------------------------------------------ capture: pieces

    private static void piece(int mode, int verts) {
        int[] sig = SleepGlState.normalized(SleepGlState.snapshot());
        if (sig[30] != 0) {   // drawn with another texture unit active: not what replay sets
            unsupported("active texture unit");
            return;
        }
        if (cSegs > 0 && cMode[cSegs - 1] == mode && Arrays.equals(cSigs.get(cSegs - 1), sig)) {
            cCount[cSegs - 1] += verts;
            return;
        }
        if (cSegs == cFirst.length) {
            cFirst = Arrays.copyOf(cFirst, cSegs * 2);
            cCount = Arrays.copyOf(cCount, cSegs * 2);
            cMode = Arrays.copyOf(cMode, cSegs * 2);
        }
        cFirst[cSegs] = cVerts;
        cCount[cSegs] = verts;
        cMode[cSegs] = mode;
        cSigs.add(sig);
        cSegs++;
    }

    private static void room(int verts) {
        int need = (cVerts + verts) * STRIDE;
        if (need > cb.capacity()) {
            ByteBuffer n = ByteBuffer.allocate(Math.max(need, cb.capacity() * 2)).order(ByteOrder.LITTLE_ENDIAN);
            n.put(cb.array(), 0, cVerts * STRIDE);
            cb = n;
        }
    }

    private static void vertex(double[] m, double x, double y, double z, float u, float v, int rgba, double nx, double ny, double nz, short lu, short lv) {
        int o = cVerts * STRIDE;
        cb.putFloat(o, (float) (m[0] * x + m[4] * y + m[8] * z + m[12] - capX));
        cb.putFloat(o + 4, (float) (m[1] * x + m[5] * y + m[9] * z + m[13] - capY));
        cb.putFloat(o + 8, (float) (m[2] * x + m[6] * y + m[10] * z + m[14] - capZ));
        cb.putFloat(o + 12, u);
        cb.putFloat(o + 16, v);
        cb.putInt(o + 20, rgba);
        double tx = m[0] * nx + m[4] * ny + m[8] * nz, ty = m[1] * nx + m[5] * ny + m[9] * nz, tz = m[2] * nx + m[6] * ny + m[10] * nz;
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len > 0) {
            tx /= len;
            ty /= len;
            tz /= len;
        }
        cb.put(o + 24, (byte) (int) ((float) tx * 127.0F));
        cb.put(o + 25, (byte) (int) ((float) ty * 127.0F));
        cb.put(o + 26, (byte) (int) ((float) tz * 127.0F));
        cb.put(o + 27, (byte) 0);
        cb.putShort(o + 28, lu);
        cb.putShort(o + 30, lv);
        cVerts++;
    }

    private static int currentColor() {
        float[] c = SleepGlState.color();
        return (clamp(c[0])) | (clamp(c[1]) << 8) | (clamp(c[2]) << 16) | (clamp(c[3]) << 24);
    }

    private static int clamp(float f) {
        if (f < 0) f = 1;   // GlStateManager's "unknown" (-1) after resetColor: GL still holds the last colour; white is the usual
        return Math.min(255, Math.round(f * 255.0F));
    }

    /** a model part tree (ModelRenderer.render at its root): its faces through the mirror (vanilla still draws it) */
    public static void part(ModelRenderer r, float scale) {
        if (!capturing() || partDepth++ > 0) return;
        if (PartBatch.mirrorLost()) {
            unsupported("matrix mirror lost");
            return;
        }
        PartBatch.captureTree(r, scale);
    }

    public static void partDone() {
        if (cap != null && partDepth > 0) partDepth--;
    }

    public static boolean inPart() {
        return cap != null && partDepth > 0;
    }

    /** PartBatch.captureTree: one part's quads (its own space data, the mirror's matrix for it) */
    static void partQuads(PartBatch.PartData d, double[] m) {
        if (d.quads == 0) return;
        room(d.quads * 4);
        piece(7, d.quads * 4);
        int rgba = currentColor();
        short lu = (short) OpenGlHelper.lastBrightnessX, lv = (short) OpenGlHelper.lastBrightnessY;
        for (int q = 0; q < d.quads; q++)
            for (int i = 0; i < 4; i++) {
                int o = q * 20 + i * 5;
                vertex(m, d.v[o], d.v[o + 1], d.v[o + 2], d.v[o + 3], d.v[o + 4], rgba, d.n[q * 3], d.n[q * 3 + 1], d.n[q * 3 + 2], lu, lv);
            }
    }

    /** a buffer about to be drawn (Tessellator / WorldVertexBufferUploader, the item and block model buffers) */
    public static void buffer(ByteBuffer src, VertexFormat fmt, int mode, int count) {
        if (!capturing() || count == 0) return;
        PartBatch.mirrorCheck("buffer " + fmt);
        if (inPart()) return;
        if (PartBatch.mirrorLost()) {
            unsupported("matrix mirror lost");
            return;
        }
        int outMode;
        if (mode == GL11.GL_QUADS) outMode = 7;
        else if (mode == GL11.GL_TRIANGLES) outMode = 4;
        else if (mode == GL11.GL_TRIANGLE_STRIP && count == 4) outMode = 7;
        else {
            unsupported("draw mode " + mode);
            return;
        }
        int pos = -1, uv = -1, col = -1, nor = -1, lm = -1, norType = 0;
        List<VertexFormatElement> els = fmt.getElements();
        for (int j = 0; j < els.size(); j++) {
            VertexFormatElement e = els.get(j);
            int off = fmt.getOffset(j);
            switch (e.getUsage()) {
                case POSITION:
                    if (e.getType() != VertexFormatElement.EnumType.FLOAT || e.getElementCount() != 3) {
                        unsupported("position type");
                        return;
                    }
                    pos = off;
                    break;
                case UV:
                    if (e.getIndex() == 0 && e.getType() == VertexFormatElement.EnumType.FLOAT) uv = off;
                    else if (e.getIndex() == 1 && e.getType() == VertexFormatElement.EnumType.SHORT) lm = off;
                    else {
                        unsupported("uv type");
                        return;
                    }
                    break;
                case COLOR:
                    if (e.getType() != VertexFormatElement.EnumType.UBYTE || e.getElementCount() != 4) {
                        unsupported("colour type");
                        return;
                    }
                    col = off;
                    break;
                case NORMAL:
                    nor = off;
                    norType = e.getType() == VertexFormatElement.EnumType.FLOAT ? 1 : 0;
                    break;
                case PADDING:
                    break;
                default:
                    unsupported("element " + e.getUsage());
                    return;
            }
        }
        if (pos < 0) {
            unsupported("no position");
            return;
        }
        int[] sig0 = SleepGlState.snapshot();
        if (uv < 0 && sig0[0] != 0) {
            unsupported("textured without uv");
            return;
        }
        double[] m = PartBatch.current();
        int stride = fmt.getSize();
        ByteBuffer s = src.duplicate().order(ByteOrder.nativeOrder());
        int base = s.position();
        room(count);
        piece(outMode, count);
        int rgba0 = col < 0 ? currentColor() : 0;
        short lu0 = (short) OpenGlHelper.lastBrightnessX, lv0 = (short) OpenGlHelper.lastBrightnessY;
        int[] order = mode == GL11.GL_TRIANGLE_STRIP ? new int[] {0, 1, 3, 2} : null;
        for (int k = 0; k < count; k++) {
            int i = order != null ? order[k] : k;
            int o = base + i * stride;
            double x = s.getFloat(o + pos), y = s.getFloat(o + pos + 4), z = s.getFloat(o + pos + 8);
            float u = uv < 0 ? 0 : s.getFloat(o + uv), v = uv < 0 ? 0 : s.getFloat(o + uv + 4);
            int rgba = col < 0 ? rgba0 : s.getInt(o + col);
            if (s.order() != ByteOrder.LITTLE_ENDIAN && col >= 0) rgba = Integer.reverseBytes(rgba);
            double nx, ny, nz;
            if (nor < 0) {
                nx = 0;
                ny = 0;
                nz = 1;
            } else if (norType == 1) {
                nx = s.getFloat(o + nor);
                ny = s.getFloat(o + nor + 4);
                nz = s.getFloat(o + nor + 8);
            } else {
                nx = s.get(o + nor) / 127.0;
                ny = s.get(o + nor + 1) / 127.0;
                nz = s.get(o + nor + 2) / 127.0;
            }
            short lu = lm < 0 ? lu0 : s.getShort(o + lm), lv = lm < 0 ? lv0 : s.getShort(o + lm + 2);
            vertex(m, x, y, z, u, v, rgba, nx, ny, nz, lu, lv);
        }
    }

    // ------------------------------------------------------------------ capture: immediate mode (FontRenderer's glyphs)

    private static int imMode = -1, imN;
    private static float imU, imV;
    private static final float[] IM = new float[5 * 16];

    public static void imBegin(int mode) {
        if (!capturing() || inPart()) return;
        imMode = mode;
        imN = 0;
    }

    public static void imTex(float u, float v) {
        imU = u;
        imV = v;
    }

    public static void imVertex(float x, float y, float z) {
        if (imMode < 0) return;
        if (imN == 16) {
            unsupported("immediate mode: many vertices");
            imMode = -1;
            return;
        }
        int o = imN * 5;
        IM[o] = x;
        IM[o + 1] = y;
        IM[o + 2] = z;
        IM[o + 3] = imU;
        IM[o + 4] = imV;
        imN++;
    }

    public static void imEnd() {
        if (imMode < 0) return;
        int mode = imMode;
        imMode = -1;
        if (!capturing()) return;
        if (PartBatch.mirrorLost()) {
            unsupported("matrix mirror lost");
            return;
        }
        if (!(mode == GL11.GL_TRIANGLE_STRIP && imN == 4 || mode == GL11.GL_QUADS && imN % 4 == 0)) {
            unsupported("immediate mode " + mode + "/" + imN);
            return;
        }
        double[] m = PartBatch.current();
        room(imN);
        piece(7, imN);
        int rgba = currentColor();
        short lu = (short) OpenGlHelper.lastBrightnessX, lv = (short) OpenGlHelper.lastBrightnessY;
        int[] order = mode == GL11.GL_TRIANGLE_STRIP ? new int[] {0, 1, 3, 2} : null;
        for (int k = 0; k < imN; k++) {
            int o = (order != null ? order[k] : k) * 5;
            vertex(m, IM[o], IM[o + 1], IM[o + 2], IM[o + 3], IM[o + 4], rgba, 0, 0, 1, lu, lv);
        }
    }

    // ------------------------------------------------------------------ drawing the cached objects

    private static final class Key {
        final int[] sig;
        final int mode;
        final int hash;

        Key(int[] sig, int mode) {
            this.sig = sig;
            this.mode = mode;
            hash = Arrays.hashCode(sig) * 31 + mode;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).mode == mode && Arrays.equals(((Key) o).sig, sig);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** one GL state's draws: per cached object its vertex range in the group's buffer */
    private static final class Group {
        final Key key;
        int vbo, verts;
        final List<Rec> recs = new ArrayList<>();
        int[] first, count;

        Group(Key key) {
            this.key = key;
        }
    }

    private static final List<Group> GROUPS = new ArrayList<>();
    private static double ox, oy, oz;
    private static IntBuffer firstBuf = GLAllocation.createDirectIntBuffer(4096), countBuf = GLAllocation.createDirectIntBuffer(4096);
    private static final java.nio.FloatBuffer MAT = GLAllocation.createDirectFloatBuffer(16);
    public static long groupDraws, rebuilds, rebuildNanos;

    /** the frame's entity pass begins */
    public static void passStart(int pass) {
        if (!ON) return;
        if (pass == 0) frame++;
        if (pass == 0 && (frame & 255) == 0) {   // objects not seen for a while are forgotten
            for (Iterator<Map.Entry<Object, Rec>> it = RECS.entrySet().iterator(); it.hasNext(); ) {
                Rec r = it.next().getValue();
                if (frame - r.seenFrame > 600) {
                    if (r.stage == 2) dirty = true;
                    it.remove();
                }
            }
        }
    }

    private static void rebuild() {
        for (Group g : GROUPS) if (g.vbo != 0) GL15.glDeleteBuffers(g.vbo);
        GROUPS.clear();
        Map<Key, Group> by = new HashMap<>();
        boolean originSet = false;
        for (Rec r : RECS.values()) {
            if (r.stage != 2) continue;
            if (!originSet) {
                ox = Math.floor(r.ax);
                oy = Math.floor(r.ay);
                oz = Math.floor(r.az);
                originSet = true;
            }
            for (int s = 0; s < r.segs; s++) {
                Key k = new Key(r.sigs.get(s), r.segMode[s]);
                Group g = by.get(k);
                if (g == null) {
                    by.put(k, g = new Group(k));
                    GROUPS.add(g);
                }
                if (g.recs.isEmpty() || g.recs.get(g.recs.size() - 1) != r) g.recs.add(r);
            }
        }
        for (Group g : GROUPS) {
            int total = 0;
            for (Rec r : g.recs) for (int s = 0; s < r.segs; s++) if (g.key.equals(new Key(r.sigs.get(s), r.segMode[s]))) total += r.segCount[s];
            ByteBuffer out = GLAllocation.createDirectByteBuffer(total * STRIDE).order(ByteOrder.nativeOrder());
            g.first = new int[g.recs.size()];
            g.count = new int[g.recs.size()];
            int at = 0;
            for (int ri = 0; ri < g.recs.size(); ri++) {
                Rec r = g.recs.get(ri);
                g.first[ri] = at;
                float dx = (float) (r.ax - ox), dy = (float) (r.ay - oy), dz = (float) (r.az - oz);
                ByteBuffer src = ByteBuffer.wrap(r.verts).order(ByteOrder.LITTLE_ENDIAN);
                for (int s = 0; s < r.segs; s++) {
                    if (!g.key.equals(new Key(r.sigs.get(s), r.segMode[s]))) continue;
                    for (int i = 0; i < r.segCount[s]; i++) {
                        int o = (r.segFirst[s] + i) * STRIDE, w = (at + i) * STRIDE;
                        out.putFloat(w, src.getFloat(o) + dx);
                        out.putFloat(w + 4, src.getFloat(o + 4) + dy);
                        out.putFloat(w + 8, src.getFloat(o + 8) + dz);
                        out.putFloat(w + 12, src.getFloat(o + 12));
                        out.putFloat(w + 16, src.getFloat(o + 16));
                        out.putInt(w + 20, src.getInt(o + 20));
                        out.putInt(w + 24, src.getInt(o + 24));
                        out.putShort(w + 28, src.getShort(o + 28));
                        out.putShort(w + 30, src.getShort(o + 30));
                    }
                    at += r.segCount[s];
                }
                g.count[ri] = at - g.first[ri];
            }
            g.verts = at;
            out.position(0).limit(at * STRIDE);
            g.vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, g.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, out, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
        dirty = false;
    }

    /** the entity pass ends: the cached objects seen in it, a multi-draw per GL state */
    public static void passEnd(int pass, double camX, double camY, double camZ) {
        if (!ON || hits == 0 && !dirty) return;
        if (dirty) {
            long t0 = System.nanoTime();
            rebuild();
            rebuilds++;
            rebuildNanos += System.nanoTime() - t0;
        }
        if (GROUPS.isEmpty()) return;
        int[] before = SleepGlState.snapshot();
        GL11.glPushMatrix();
        GL11.glTranslated(ox - camX, oy - camY, oz - camZ);
        boolean any = false;
        for (Group g : GROUPS) {
            firstBuf.clear();
            countBuf.clear();
            int n = 0;
            for (int i = 0; i < g.recs.size(); i++) {
                Rec r = g.recs.get(i);
                if (r.stage != 2 || r.seenFrame != frame || r.seenPass != pass || g.count[i] == 0 || r.vanillaFrame == frame && r.vanillaPass == pass) continue;
                if (n == firstBuf.capacity()) {
                    firstBuf = grow(firstBuf);
                    countBuf = grow(countBuf);
                }
                firstBuf.put(g.first[i]);
                countBuf.put(g.count[i]);
                n++;
            }
            if (n == 0) continue;
            firstBuf.flip();
            countBuf.flip();
            SleepGlState.direct(g.key.sig);
            if (!any) {
                arrays(true);
                any = true;
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, g.vbo);
            pointers();
            GL14.glMultiDrawArrays(g.key.mode, firstBuf, countBuf);
            groupDraws++;
        }
        if (any) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            arrays(false);
        }
        GL11.glPopMatrix();
        SleepGlState.restore(before);
    }

    private static IntBuffer grow(IntBuffer b) {
        IntBuffer n = GLAllocation.createDirectIntBuffer(b.capacity() * 2);
        b.flip();
        n.put(b);
        return n;
    }

    private static void pointers() {
        GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, 20);
        GL11.glNormalPointer(GL11.GL_BYTE, STRIDE, 24);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, STRIDE, 28);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 12);
    }

    private static void arrays(boolean on) {
        if (on) {
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        } else {
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
    }

    public static String report() {
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT,
                "entity cache: %d hits, %d captures, %d recorded, %d same after a state change, %d refreshes, %d probes, %d animated, %d unsupported, %d groups, %d group draws, %d fields learned, %d rebuilds %.1f ms each",
                hits, captures, recorded, sameAfterChange, refreshes, probes, animated, unsupported, GROUPS.size(), groupDraws, StateHash.learned, rebuilds, rebuildNanos / 1e6 / Math.max(1, rebuilds)));
        WHY.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(14).forEach(e -> b.append("; ").append(e.getKey()).append(' ').append(e.getValue()));
        return b.toString();
    }
}
