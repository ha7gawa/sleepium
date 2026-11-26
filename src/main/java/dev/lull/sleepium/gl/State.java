package dev.lull.sleepium.gl;

import org.lwjgl.opengl.GL11;

/** The drawing state as ints (what the render cache groups draws by), and setting it on GL directly for the replay. */
public final class State {
    private State() {}

    public static final int SIZE = 33;

    /** the state now (real: GL's own, every call passes through GL) */
    public static int[] snapshot() {
        int[] o = new int[SIZE];
        o[0] = GL.tex2d[0] ? 1 : 0;
        o[1] = GL.bound[0];
        o[2] = GL.tex2d[1] ? 1 : 0;
        o[3] = GL.lighting ? 1 : 0;
        o[4] = GL.light0 ? 1 : 0;
        o[5] = GL.light1 ? 1 : 0;
        o[6] = GL.colorMaterial ? 1 : 0;
        o[7] = GL.cmFace;
        o[8] = GL.cmMode;
        o[9] = GL.alphaTest ? 1 : 0;
        o[10] = GL.alphaFunc;
        o[11] = Float.floatToRawIntBits(GL.alphaRef);
        o[12] = GL.blend ? 1 : 0;
        o[13] = GL.blendSrc;
        o[14] = GL.blendDst;
        o[15] = GL.blendSrcA;
        o[16] = GL.blendDstA;
        o[17] = GL.depthTest ? 1 : 0;
        o[18] = GL.depthMask ? 1 : 0;
        o[19] = GL.depthFunc;
        o[20] = GL.cull ? 1 : 0;
        o[21] = GL.cullMode;
        o[22] = GL.polyFill ? 1 : 0;
        o[23] = Float.floatToRawIntBits(GL.polyFactor);
        o[24] = Float.floatToRawIntBits(GL.polyUnits);
        o[25] = GL.normalize ? 1 : 0;
        o[26] = GL.rescale ? 1 : 0;
        o[27] = GL.shade;
        o[28] = GL.fog ? 1 : 0;
        o[29] = GL.colorMask;
        o[30] = GL.activeUnit;
        int[] f = GL.FILTERS.get(GL.bound[0]);
        o[31] = f == null ? -1 : f[0];
        o[32] = f == null ? -1 : f[1];
        return o;
    }

    /** as it matters for drawing: a switched-off feature's parameters zeroed (for keys and comparisons; restoring uses the raw one) */
    public static int[] normalized(int[] s) {
        int[] o = s.clone();
        if (o[6] == 0) o[7] = o[8] = 0;
        if (o[9] == 0) o[10] = o[11] = 0;
        if (o[12] == 0) o[13] = o[14] = o[15] = o[16] = 0;
        if (o[17] == 0) o[19] = 0;
        if (o[20] == 0) o[21] = 0;
        if (o[22] == 0) o[23] = o[24] = 0;
        if (o[0] == 0) o[1] = o[31] = o[32] = 0;
        if (o[3] == 0) o[4] = o[5] = o[6] = o[7] = o[8] = o[25] = o[26] = 0;
        return o;
    }

    public static float[] color() {
        return GL.color.clone();
    }

    public static float[] normal() {
        return GL.normal.clone();
    }

    private static void gl(boolean on, int cap) {
        if (on) GL11.glEnable(cap);
        else GL11.glDisable(cap);
    }

    private static final java.util.ArrayList<Integer> TOUCHED = new java.util.ArrayList<>();

    /** the replay's state, set on GL directly (the tracked state is left as it is: restore() puts GL back to it) */
    public static void direct(int[] s) {
        net.minecraft.client.renderer.OpenGlHelper.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit);
        gl(s[2] != 0, 3553);
        net.minecraft.client.renderer.OpenGlHelper.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
        gl(s[0] != 0, 3553);
        GL11.glBindTexture(3553, s[1]);
        if (s[31] != -1) {   // the filters it was drawn with (put back in restore)
            GL11.glTexParameteri(3553, 10241, s[31]);
            if (s[32] != -1) GL11.glTexParameteri(3553, 10240, s[32]);
            if (!TOUCHED.contains(s[1])) TOUCHED.add(s[1]);
        }
        gl(s[3] != 0, 2896);
        gl(s[4] != 0, 16384);
        gl(s[5] != 0, 16385);
        if (s[6] != 0) GL11.glColorMaterial(s[7], s[8]);
        gl(s[6] != 0, 2903);
        if (s[9] != 0) GL11.glAlphaFunc(s[10], Float.intBitsToFloat(s[11]));
        gl(s[9] != 0, 3008);
        if (s[12] != 0) net.minecraft.client.renderer.OpenGlHelper.glBlendFunc(s[13], s[14], s[15], s[16]);
        gl(s[12] != 0, 3042);
        if (s[17] != 0) GL11.glDepthFunc(s[19]);
        GL11.glDepthMask(s[18] != 0);
        gl(s[17] != 0, 2929);
        if (s[20] != 0) GL11.glCullFace(s[21]);
        gl(s[20] != 0, 2884);
        if (s[22] != 0) GL11.glPolygonOffset(Float.intBitsToFloat(s[23]), Float.intBitsToFloat(s[24]));
        gl(s[22] != 0, 32823);
        gl(s[25] != 0, 2977);
        gl(s[26] != 0, 32826);
        GL11.glShadeModel(s[27]);
        gl(s[28] != 0, 2912);
        GL11.glColorMask((s[29] & 1) != 0, (s[29] & 2) != 0, (s[29] & 4) != 0, (s[29] & 8) != 0);
    }

    /** after the replay: GL as the tracked state says (raw values, the active unit, the colour, the touched textures' filters) */
    public static void restore(int[] before) {
        for (int t : TOUCHED) {
            int[] f = GL.FILTERS.get(t);
            GL11.glBindTexture(3553, t);
            if (f != null && f[0] != -1) GL11.glTexParameteri(3553, 10241, f[0]);
            if (f != null && f[1] != -1) GL11.glTexParameteri(3553, 10240, f[1]);
        }
        TOUCHED.clear();
        int[] b = before.clone();
        b[31] = -1;
        // every feature's parameters as they are (a switched-off one's too: the game may switch it on without setting them)
        GL11.glColorMaterial(b[7], b[8]);
        GL11.glAlphaFunc(b[10], Float.intBitsToFloat(b[11]));
        net.minecraft.client.renderer.OpenGlHelper.glBlendFunc(b[13], b[14], b[15], b[16]);
        GL11.glDepthFunc(b[19]);
        GL11.glCullFace(b[21]);
        GL11.glPolygonOffset(Float.intBitsToFloat(b[23]), Float.intBitsToFloat(b[24]));
        direct(b);
        net.minecraft.client.renderer.OpenGlHelper.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit + b[30]);
        GL11.glColor4f(GL.color[0], GL.color[1], GL.color[2], GL.color[3]);
        GL11.glNormal3f(GL.normal[0], GL.normal[1], GL.normal[2]);
    }
}
