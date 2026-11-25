package net.minecraft.client.renderer;

import java.lang.reflect.Field;

/**
 * Sleepium (EntityCache): GlStateManager's cached GL state read as ints and set again through GlStateManager (so its
 * cache stays right). In GlStateManager's package: its state classes are package-private; the static fields holding
 * them are private (looked up once, by their MCP or SRG name).
 */
public final class SleepGlState {
    private SleepGlState() {}

    public static final int SIZE = 33;
    /** per texture its min / mag filter as last set through GlStateManager (texture object state: the atlas loses its
     *  mipmaps while items are drawn) */
    private static final java.util.HashMap<Integer, int[]> FILTERS = new java.util.HashMap<>();
    private static final java.util.ArrayList<Integer> TOUCHED = new java.util.ArrayList<>();
    private static GlStateManager.AlphaState alpha;
    private static GlStateManager.BlendState blend;
    private static GlStateManager.BooleanState lighting, normalize, rescale;
    private static GlStateManager.BooleanState[] lights;
    private static GlStateManager.ColorMaterialState colorMaterial;
    private static GlStateManager.DepthState depth;
    private static GlStateManager.CullState cull;
    private static GlStateManager.PolygonOffsetState poly;
    private static GlStateManager.TextureState[] tex;
    private static GlStateManager.FogState fog;
    private static GlStateManager.ColorMask mask;
    private static GlStateManager.Color color;
    private static Field activeTex, shadeModel, boolState;
    private static GlStateManager.BooleanState[] all;
    /** the booleans whose real GL state is not what GlStateManager believes (after glPopAttrib) */
    private static final java.util.IdentityHashMap<Object, Boolean> REAL = new java.util.IdentityHashMap<>();
    private static final java.util.ArrayDeque<boolean[]> PUSHED = new java.util.ArrayDeque<>();
    private static final float[] realColor = {1, 1, 1, 1};
    private static boolean ready, failed;

    private static Field field(Class<?> c, String mcp, String srg) throws NoSuchFieldException {
        Field f;
        try {
            f = c.getDeclaredField(mcp);
        } catch (NoSuchFieldException e) {
            f = c.getDeclaredField(srg);
        }
        f.setAccessible(true);
        return f;
    }

    /** false: the fields could not be found (another GlStateManager) - the cache stays off */
    public static boolean init() {
        if (ready || failed) return ready;
        try {
            Class<?> g = GlStateManager.class;
            alpha = (GlStateManager.AlphaState) field(g, "alphaState", "field_179160_a").get(null);
            lighting = (GlStateManager.BooleanState) field(g, "lightingState", "field_179158_b").get(null);
            lights = (GlStateManager.BooleanState[]) field(g, "lightState", "field_179159_c").get(null);
            colorMaterial = (GlStateManager.ColorMaterialState) field(g, "colorMaterialState", "field_179156_d").get(null);
            blend = (GlStateManager.BlendState) field(g, "blendState", "field_179157_e").get(null);
            depth = (GlStateManager.DepthState) field(g, "depthState", "field_179154_f").get(null);
            fog = (GlStateManager.FogState) field(g, "fogState", "field_179155_g").get(null);
            cull = (GlStateManager.CullState) field(g, "cullState", "field_179167_h").get(null);
            poly = (GlStateManager.PolygonOffsetState) field(g, "polygonOffsetState", "field_179168_i").get(null);
            normalize = (GlStateManager.BooleanState) field(g, "normalizeState", "field_179161_n").get(null);
            tex = (GlStateManager.TextureState[]) field(g, "textureState", "field_179174_p").get(null);
            rescale = (GlStateManager.BooleanState) field(g, "rescaleNormalState", "field_179172_r").get(null);
            color = (GlStateManager.Color) field(g, "colorState", "field_179170_t").get(null);
            mask = (GlStateManager.ColorMask) field(g, "colorMaskState", "field_179171_s").get(null);
            activeTex = field(g, "activeTextureUnit", "field_179162_o");
            shadeModel = field(g, "activeShadeModel", "field_179173_q");
            boolState = field(GlStateManager.BooleanState.class, "currentState", "field_179201_b");
            java.util.List<GlStateManager.BooleanState> l = new java.util.ArrayList<>();
            l.add(alpha.alphaTest);
            l.add(lighting);
            for (GlStateManager.BooleanState x : lights) l.add(x);
            l.add(colorMaterial.colorMaterial);
            l.add(blend.blend);
            l.add(depth.depthTest);
            l.add(fog.fog);
            l.add(cull.cullFace);
            l.add(poly.polygonOffsetFill);
            l.add(poly.polygonOffsetLine);
            l.add(normalize);
            l.add(rescale);
            for (GlStateManager.TextureState t : tex) l.add(t.texture2DState);
            all = l.toArray(new GlStateManager.BooleanState[0]);
            ready = true;
        } catch (Throwable t) {
            failed = true;
            System.out.println("[sleepium] EntityCache off: GlStateManager's state not found (" + t + ")");
        }
        return ready;
    }

    private static boolean cached(GlStateManager.BooleanState s) {
        try {
            return boolState.getBoolean(s);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** the real GL state of a boolean (GlStateManager's belief, unless a glPopAttrib changed GL behind it) */
    private static int b(GlStateManager.BooleanState s) {
        Boolean r = REAL.get(s);
        return (r != null ? r : cached(s)) ? 1 : 0;
    }

    // ------------------------------------------------------------------ the real state behind GlStateManager's cache

    /** BooleanState.setState (before it runs): GL is called only when the cache differs - then GL and the cache agree */
    public static void onSet(Object state, boolean value, boolean cache) {
        if (value != cache && !REAL.isEmpty()) REAL.remove(state);
    }

    /** glPushAttrib(GL_ENABLE_BIT | GL_LIGHTING_BIT): the real enables saved */
    public static void onPushAttrib() {
        if (!ready) return;
        boolean[] v = new boolean[all.length];
        for (int i = 0; i < all.length; i++) v[i] = b(all[i]) != 0;
        PUSHED.push(v);
    }

    /** glPopAttrib: GL's enables are back to the saved ones, GlStateManager's cache is not */
    public static void onPopAttrib() {
        if (!ready || PUSHED.isEmpty()) return;
        boolean[] v = PUSHED.pop();
        for (int i = 0; i < all.length; i++) {
            if (v[i] != cached(all[i])) REAL.put(all[i], v[i]);
            else REAL.remove(all[i]);
        }
    }

    /** GlStateManager.glTexParameteri/f on the bound 2D texture of the active unit */
    public static void onTexParameter(int target, int pname, int value) {
        if (target != 3553 || (pname != 10241 && pname != 10240) || !init()) return;
        try {
            int t = tex[activeTex.getInt(null)].textureName;
            int[] f = FILTERS.get(t);
            if (f == null) FILTERS.put(t, f = new int[] {-1, -1});
            f[pname == 10241 ? 0 : 1] = value;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** GlStateManager.color (before it runs): GL gets the colour only when it differs from the cache */
    public static void onColor(float r, float g, float b, float a) {
        if (color != null && (r != color.red || g != color.green || b != color.blue || a != color.alpha)) {
            realColor[0] = r;
            realColor[1] = g;
            realColor[2] = b;
            realColor[3] = a;
        }
    }

    /** the state a draw happens in (what EntityCache groups draws by) */
    public static int[] snapshot() {
        int[] o = new int[SIZE];
        try {
            o[0] = b(tex[0].texture2DState);
            o[1] = tex[0].textureName;
            o[2] = b(tex[1].texture2DState);
            o[3] = b(lighting);
            o[4] = b(lights[0]);
            o[5] = b(lights[1]);
            o[6] = b(colorMaterial.colorMaterial);
            o[7] = colorMaterial.face;
            o[8] = colorMaterial.mode;
            o[9] = b(alpha.alphaTest);
            o[10] = alpha.func;
            o[11] = Float.floatToRawIntBits(alpha.ref);
            o[12] = b(blend.blend);
            o[13] = blend.srcFactor;
            o[14] = blend.dstFactor;
            o[15] = blend.srcFactorAlpha;
            o[16] = blend.dstFactorAlpha;
            o[17] = b(depth.depthTest);
            o[18] = depth.maskEnabled ? 1 : 0;
            o[19] = depth.depthFunc;
            o[20] = b(cull.cullFace);
            o[21] = cull.mode;
            o[22] = b(poly.polygonOffsetFill);
            o[23] = Float.floatToRawIntBits(poly.factor);
            o[24] = Float.floatToRawIntBits(poly.units);
            o[25] = b(normalize);
            o[26] = b(rescale);
            o[27] = shadeModel.getInt(null);
            o[28] = b(fog.fog);
            o[29] = (mask.red ? 1 : 0) | (mask.green ? 2 : 0) | (mask.blue ? 4 : 0) | (mask.alpha ? 8 : 0);
            o[30] = activeTex.getInt(null);
            int[] f = FILTERS.get(o[1]);
            o[31] = f == null ? -1 : f[0];
            o[32] = f == null ? -1 : f[1];
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    /** the current colour (glColor) GL really has */
    public static float[] color() {
        return realColor.clone();
    }

    private static void gl(boolean on, int cap) {
        if (on) org.lwjgl.opengl.GL11.glEnable(cap);
        else org.lwjgl.opengl.GL11.glDisable(cap);
    }

    /**
     * EntityCache's replay: the state set on GL directly, GlStateManager's cache left as it is (with restore() of the
     * state before, vanilla goes on exactly as if nothing had been drawn)
     */
    public static void direct(int[] s) {
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        gl(s[2] != 0, 3553);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        gl(s[0] != 0, 3553);
        org.lwjgl.opengl.GL11.glBindTexture(3553, s[1]);
        if (s[31] != -1) {   // the filters it was drawn with (put back in restore)
            org.lwjgl.opengl.GL11.glTexParameteri(3553, 10241, s[31]);
            if (s[32] != -1) org.lwjgl.opengl.GL11.glTexParameteri(3553, 10240, s[32]);
            if (!TOUCHED.contains(s[1])) TOUCHED.add(s[1]);
        }
        gl(s[3] != 0, 2896);
        gl(s[4] != 0, 16384);
        gl(s[5] != 0, 16385);
        org.lwjgl.opengl.GL11.glColorMaterial(s[7], s[8]);
        gl(s[6] != 0, 2903);
        org.lwjgl.opengl.GL11.glAlphaFunc(s[10], Float.intBitsToFloat(s[11]));
        gl(s[9] != 0, 3008);
        OpenGlHelper.glBlendFunc(s[13], s[14], s[15], s[16]);
        gl(s[12] != 0, 3042);
        org.lwjgl.opengl.GL11.glDepthFunc(s[19]);
        org.lwjgl.opengl.GL11.glDepthMask(s[18] != 0);
        gl(s[17] != 0, 2929);
        org.lwjgl.opengl.GL11.glCullFace(s[21]);
        gl(s[20] != 0, 2884);
        org.lwjgl.opengl.GL11.glPolygonOffset(Float.intBitsToFloat(s[23]), Float.intBitsToFloat(s[24]));
        gl(s[22] != 0, 32823);
        gl(s[25] != 0, 2977);
        gl(s[26] != 0, 32826);
        org.lwjgl.opengl.GL11.glShadeModel(s[27]);
        gl(s[28] != 0, 2912);
        org.lwjgl.opengl.GL11.glColorMask((s[29] & 1) != 0, (s[29] & 2) != 0, (s[29] & 4) != 0, (s[29] & 8) != 0);
    }

    /** after the replay: the state before it, with the active texture unit and the colour */
    public static void restore(int[] before) {
        for (int t : TOUCHED) {   // the textures' own filters again
            int[] f = FILTERS.get(t);
            org.lwjgl.opengl.GL11.glBindTexture(3553, t);
            if (f[0] != -1) org.lwjgl.opengl.GL11.glTexParameteri(3553, 10241, f[0]);
            if (f[1] != -1) org.lwjgl.opengl.GL11.glTexParameteri(3553, 10240, f[1]);
        }
        TOUCHED.clear();
        int[] b = before.clone();
        b[31] = -1;   // (restored above)
        direct(b);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit + before[30]);
        org.lwjgl.opengl.GL11.glColor4f(realColor[0], realColor[1], realColor[2], realColor[3]);
    }

    private static void set(boolean on, Runnable enable, Runnable disable) {
        if (on) enable.run();
        else disable.run();
    }

    /** the state set again (texture units: the lightmap's, then the default one, left active) */
    public static void apply(int[] s) {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        set(s[2] != 0, GlStateManager::enableTexture2D, GlStateManager::disableTexture2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        set(s[0] != 0, GlStateManager::enableTexture2D, GlStateManager::disableTexture2D);
        GlStateManager.bindTexture(s[1]);
        set(s[3] != 0, GlStateManager::enableLighting, GlStateManager::disableLighting);
        if (s[4] != 0) GlStateManager.enableLight(0);
        else GlStateManager.disableLight(0);
        if (s[5] != 0) GlStateManager.enableLight(1);
        else GlStateManager.disableLight(1);
        GlStateManager.colorMaterial(s[7], s[8]);
        set(s[6] != 0, GlStateManager::enableColorMaterial, GlStateManager::disableColorMaterial);
        GlStateManager.alphaFunc(s[10], Float.intBitsToFloat(s[11]));
        set(s[9] != 0, GlStateManager::enableAlpha, GlStateManager::disableAlpha);
        GlStateManager.tryBlendFuncSeparate(s[13], s[14], s[15], s[16]);
        set(s[12] != 0, GlStateManager::enableBlend, GlStateManager::disableBlend);
        GlStateManager.depthFunc(s[19]);
        GlStateManager.depthMask(s[18] != 0);
        set(s[17] != 0, GlStateManager::enableDepth, GlStateManager::disableDepth);
        if (cull.mode != s[21]) {
            cull.mode = s[21];
            org.lwjgl.opengl.GL11.glCullFace(s[21]);
        }
        set(s[20] != 0, GlStateManager::enableCull, GlStateManager::disableCull);
        GlStateManager.doPolygonOffset(Float.intBitsToFloat(s[23]), Float.intBitsToFloat(s[24]));
        set(s[22] != 0, GlStateManager::enablePolygonOffset, GlStateManager::disablePolygonOffset);
        set(s[25] != 0, GlStateManager::enableNormalize, GlStateManager::disableNormalize);
        set(s[26] != 0, GlStateManager::enableRescaleNormal, GlStateManager::disableRescaleNormal);
        GlStateManager.shadeModel(s[27]);
        set(s[28] != 0, GlStateManager::enableFog, GlStateManager::disableFog);
        GlStateManager.colorMask((s[29] & 1) != 0, (s[29] & 2) != 0, (s[29] & 4) != 0, (s[29] & 8) != 0);
    }
}
