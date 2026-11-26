package dev.lull.sleepium.gl;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import org.lwjgl.opengl.GL11;

/**
 * Sleepium's GL11 for 1.7.10 (GlRedirect points every class's GL11 calls of these methods here): each call goes on to
 * GL11 as it was, and what the render cache needs is kept on the way - the drawing state (what GlStateManager keeps on
 * 1.8+, here real: every call passes, glPushAttrib / glPopAttrib included), the current colour and normal, the texture
 * filters, and while an object is recorded the model view matrix relative to where its drawing began, the immediate-mode
 * vertices and the display lists being called. -Dsleep.gl.redirect=false: none of it (GlRedirect rewrites nothing).
 */
public final class GL {
    private GL() {}

    // ------------------------------------------------------------------ the state (GL's defaults until set)

    static final int UNITS = 8;
    static boolean[] tex2d = new boolean[UNITS];
    static int[] bound = new int[UNITS];
    static int activeUnit;
    static boolean lighting, light0, light1, colorMaterial, alphaTest, blend, depthTest, depthMask = true, cull, polyFill, normalize, rescale, fog;
    static int cmFace = 1032, cmMode = 5634, alphaFunc = 519, blendSrc = 1, blendDst = 0, blendSrcA = 1, blendDstA = 0, depthFunc = 513, cullMode = 1029, shade = 7425, colorMask = 15;
    static float alphaRef, polyFactor, polyUnits;
    static final float[] color = {1, 1, 1, 1};
    static final float[] normal = {0, 0, 1};
    /** per texture its min / mag filter as last set (the atlas loses its mipmaps while items are drawn) */
    static final HashMap<Integer, int[]> FILTERS = new HashMap<>();
    /** a probe's dry run: colour and depth writes held off, the game's own calls to them not passed on */
    public static boolean dry;

    public static int activeUnit() {
        return activeUnit;
    }

    /** OpenGlHelper.setActiveTexture (GlActiveTextureMixin) */
    public static void activeTexture(int unit) {
        activeUnit = Math.max(0, Math.min(UNITS - 1, unit - org.lwjgl.opengl.GL13.GL_TEXTURE0));
    }

    /** OpenGlHelper.glBlendFunc (a separate alpha function) */
    public static void blendSeparate(int src, int dst, int srcA, int dstA) {
        blendSrc = src;
        blendDst = dst;
        blendSrcA = srcA;
        blendDstA = dstA;
    }

    private static void cap(int c, boolean on) {
        switch (c) {
            case 3553: tex2d[activeUnit] = on; break;      // GL_TEXTURE_2D
            case 2896: lighting = on; break;
            case 16384: light0 = on; break;
            case 16385: light1 = on; break;
            case 2903: colorMaterial = on; break;
            case 3008: alphaTest = on; break;
            case 3042: blend = on; break;
            case 2929: depthTest = on; break;
            case 2884: cull = on; break;
            case 32823: polyFill = on; break;
            case 2977: normalize = on; break;
            case 32826: rescale = on; break;               // GL_RESCALE_NORMAL
            case 2912: fog = on; break;
            case 3168: case 3169: case 3170: case 3171:      // texgen S T R Q
            case 3058:                                       // colour logic op
                if (on) Capture.unsupported("texgen / logic op");
                break;
            default: break;
        }
    }

    public static void glEnable(int c) {
        Lists.other();
        cap(c, true);
        GL11.glEnable(c);
    }

    public static void glDisable(int c) {
        Lists.other();
        cap(c, false);
        GL11.glDisable(c);
    }

    public static void glBlendFunc(int s, int d) {
        Lists.other();
        blendSrc = blendSrcA = s;
        blendDst = blendDstA = d;
        GL11.glBlendFunc(s, d);
    }

    public static void glAlphaFunc(int f, float r) {
        Lists.other();
        alphaFunc = f;
        alphaRef = r;
        GL11.glAlphaFunc(f, r);
    }

    public static void glDepthFunc(int f) {
        Lists.other();
        depthFunc = f;
        GL11.glDepthFunc(f);
    }

    public static void glDepthMask(boolean m) {
        Lists.other();
        depthMask = m;   // (a dry run: kept as the drawing set it, not passed on - the writes stay off)
        if (!dry) GL11.glDepthMask(m);
    }

    public static void glCullFace(int m) {
        Lists.other();
        cullMode = m;
        GL11.glCullFace(m);
    }

    public static void glShadeModel(int m) {
        Lists.other();
        shade = m;
        GL11.glShadeModel(m);
    }

    public static void glColorMask(boolean r, boolean g, boolean b, boolean a) {
        Lists.other();
        colorMask = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
        if (!dry) GL11.glColorMask(r, g, b, a);
    }

    public static void glColorMaterial(int face, int mode) {
        Lists.other();
        cmFace = face;
        cmMode = mode;
        GL11.glColorMaterial(face, mode);
    }

    public static void glPolygonOffset(float f, float u) {
        Lists.other();
        polyFactor = f;
        polyUnits = u;
        GL11.glPolygonOffset(f, u);
    }

    public static void glBindTexture(int target, int t) {
        Lists.other();
        if (target == 3553) bound[activeUnit] = t;
        GL11.glBindTexture(target, t);
    }

    private static void filter(int target, int pname, int v) {
        if (target != 3553 || (pname != 10241 && pname != 10240)) return;
        int[] f = FILTERS.get(bound[activeUnit]);
        if (f == null) FILTERS.put(bound[activeUnit], f = new int[] {-1, -1});
        f[pname == 10241 ? 0 : 1] = v;
    }

    public static void glTexParameteri(int target, int pname, int v) {
        Lists.other();
        filter(target, pname, v);
        GL11.glTexParameteri(target, pname, v);
    }

    public static void glTexParameterf(int target, int pname, float v) {
        Lists.other();
        filter(target, pname, (int) v);
        GL11.glTexParameterf(target, pname, v);
    }

    public static void glColor4f(float r, float g, float b, float a) {
        Lists.other();
        color[0] = r;
        color[1] = g;
        color[2] = b;
        color[3] = a;
        GL11.glColor4f(r, g, b, a);
    }

    public static void glColor3f(float r, float g, float b) {
        Lists.other();
        color[0] = r;
        color[1] = g;
        color[2] = b;
        color[3] = 1;
        GL11.glColor3f(r, g, b);
    }

    public static void glColor4ub(byte r, byte g, byte b, byte a) {
        Lists.other();
        color[0] = (r & 255) / 255f;
        color[1] = (g & 255) / 255f;
        color[2] = (b & 255) / 255f;
        color[3] = (a & 255) / 255f;
        GL11.glColor4ub(r, g, b, a);
    }

    public static void glColor3ub(byte r, byte g, byte b) {
        Lists.other();
        color[0] = (r & 255) / 255f;
        color[1] = (g & 255) / 255f;
        color[2] = (b & 255) / 255f;
        color[3] = 1;
        GL11.glColor3ub(r, g, b);
    }

    public static void glColor4d(double r, double g, double b, double a) {
        Lists.other();
        glColor4f((float) r, (float) g, (float) b, (float) a);
    }

    public static void glColor3d(double r, double g, double b) {
        Lists.other();
        glColor3f((float) r, (float) g, (float) b);
    }

    public static void glNormal3f(float x, float y, float z) {
        Lists.other();
        normal[0] = x;
        normal[1] = y;
        normal[2] = z;
        Capture.imNormal(x, y, z);
        GL11.glNormal3f(x, y, z);
    }

    // ------------------------------------------------------------------ the attribute stack (what is tracked, by the pushed bits)

    private static final ArrayDeque<Object[]> ATTRIB = new ArrayDeque<>();

    public static void glPushAttrib(int mask) {
        Lists.other();
        ATTRIB.push(new Object[] {mask, tex2d.clone(), bound.clone(), new boolean[] {lighting, light0, light1, colorMaterial, alphaTest, blend, depthTest, depthMask, cull, polyFill, normalize, rescale, fog},
                new int[] {cmFace, cmMode, alphaFunc, blendSrc, blendDst, blendSrcA, blendDstA, depthFunc, cullMode, shade, colorMask, activeUnit},
                new float[] {alphaRef, polyFactor, polyUnits, color[0], color[1], color[2], color[3], normal[0], normal[1], normal[2]}});
        GL11.glPushAttrib(mask);
    }

    public static void glPopAttrib() {
        Lists.other();
        GL11.glPopAttrib();
        Object[] s = ATTRIB.poll();
        if (s == null) return;
        int m = (Integer) s[0];
        boolean[] t = (boolean[]) s[1], b = (boolean[]) s[3];
        int[] bo = (int[]) s[2], i = (int[]) s[4];
        float[] f = (float[]) s[5];
        boolean all = m == GL11.GL_ALL_ATTRIB_BITS;
        if (all || (m & GL11.GL_ENABLE_BIT) != 0) {
            System.arraycopy(t, 0, tex2d, 0, UNITS);
            lighting = b[0];
            light0 = b[1];
            light1 = b[2];
            colorMaterial = b[3];
            alphaTest = b[4];
            blend = b[5];
            depthTest = b[6];
            cull = b[8];
            polyFill = b[9];
            normalize = b[10];
            rescale = b[11];
            fog = b[12];
        }
        if (all || (m & GL11.GL_LIGHTING_BIT) != 0) {
            lighting = b[0];
            light0 = b[1];
            light1 = b[2];
            colorMaterial = b[3];
            cmFace = i[0];
            cmMode = i[1];
            shade = i[9];
        }
        if (all || (m & GL11.GL_COLOR_BUFFER_BIT) != 0) {
            alphaTest = b[4];
            alphaFunc = i[2];
            alphaRef = f[0];
            blend = b[5];
            blendSrc = i[3];
            blendDst = i[4];
            blendSrcA = i[5];
            blendDstA = i[6];
            colorMask = i[10];
        }
        if (all || (m & GL11.GL_DEPTH_BUFFER_BIT) != 0) {
            depthTest = b[6];
            depthMask = b[7];
            depthFunc = i[7];
        }
        if (all || (m & GL11.GL_POLYGON_BIT) != 0) {
            cull = b[8];
            cullMode = i[8];
            polyFill = b[9];
            polyFactor = f[1];
            polyUnits = f[2];
        }
        if (all || (m & GL11.GL_TRANSFORM_BIT) != 0) normalize = b[10];
        if (all || (m & GL11.GL_TEXTURE_BIT) != 0) {
            System.arraycopy(bo, 0, bound, 0, UNITS);
            activeUnit = i[11];
        }
        if (all || (m & GL11.GL_CURRENT_BIT) != 0) {
            System.arraycopy(f, 3, color, 0, 4);
            System.arraycopy(f, 7, normal, 0, 3);
        }
        if (all || (m & GL11.GL_FOG_BIT) != 0) fog = b[12];
    }

    // ------------------------------------------------------------------ calls a recording cannot represent

    public static void glTexEnvi(int t, int p, int v) {
        Lists.other();
        Capture.unsupported("texture environment");
        GL11.glTexEnvi(t, p, v);
    }

    public static void glTexEnvf(int t, int p, float v) {
        Lists.other();
        Capture.unsupported("texture environment");
        GL11.glTexEnvf(t, p, v);
    }

    public static void glTexEnv(int t, int p, FloatBuffer v) {
        Lists.other();
        Capture.unsupported("texture environment");
        GL11.glTexEnv(t, p, v);
    }

    public static void glTexEnv(int t, int p, IntBuffer v) {
        Lists.other();
        Capture.unsupported("texture environment");
        GL11.glTexEnv(t, p, v);
    }

    public static void glTexGeni(int c, int p, int v) {
        Lists.other();
        Capture.unsupported("texgen");
        GL11.glTexGeni(c, p, v);
    }

    public static void glTexGen(int c, int p, FloatBuffer v) {
        Lists.other();
        Capture.unsupported("texgen");
        GL11.glTexGen(c, p, v);
    }

    public static void glLineWidth(float w) {
        Lists.other();
        Capture.unsupported("lines");
        GL11.glLineWidth(w);
    }

    public static void glPolygonMode(int f, int m) {
        Lists.other();
        Capture.unsupported("polygon mode");
        GL11.glPolygonMode(f, m);
    }

    public static void glDrawArrays(int mode, int first, int count) {
        if (!Capture.inTessellator) {
            Capture.unsupported("a draw of its own");
            Lists.other();
        }
        GL11.glDrawArrays(mode, first, count);
    }

    // ------------------------------------------------------------------ display lists

    public static void glNewList(int list, int mode) {
        Lists.begin(list, mode);
        GL11.glNewList(list, mode);
    }

    public static void glEndList() {
        Lists.end();
        GL11.glEndList();
    }

    public static void glDeleteLists(int list, int range) {
        Lists.deleted(list, range);
        GL11.glDeleteLists(list, range);
    }

    public static void glCallList(int list) {
        Lists.other();
        Capture.callList(list);
        GL11.glCallList(list);
    }

    // ------------------------------------------------------------------ the model view matrix (mirrored while an object is recorded)

    public static void glMatrixMode(int m) {
        Lists.other();
        Mirror.mode(m);
        GL11.glMatrixMode(m);
    }

    public static void glPushMatrix() {
        Lists.other();
        Mirror.push();
        GL11.glPushMatrix();
    }

    public static void glPopMatrix() {
        Lists.other();
        Mirror.pop();
        GL11.glPopMatrix();
    }

    public static void glLoadIdentity() {
        Lists.other();
        Mirror.lost();
        GL11.glLoadIdentity();
    }

    public static void glLoadMatrix(FloatBuffer m) {
        Lists.other();
        Mirror.lost();
        GL11.glLoadMatrix(m);
    }

    public static void glOrtho(double l, double r, double b, double t, double n, double f) {
        Lists.other();
        Mirror.lost();
        GL11.glOrtho(l, r, b, t, n, f);
    }

    public static void glTranslatef(float x, float y, float z) {
        Lists.other();
        Mirror.translate(x, y, z);
        GL11.glTranslatef(x, y, z);
    }

    public static void glTranslated(double x, double y, double z) {
        Lists.other();
        Mirror.translate(x, y, z);
        GL11.glTranslated(x, y, z);
    }

    public static void glRotatef(float a, float x, float y, float z) {
        Lists.other();
        Mirror.rotate(a, x, y, z);
        GL11.glRotatef(a, x, y, z);
    }

    public static void glRotated(double a, double x, double y, double z) {
        Lists.other();
        Mirror.rotate(a, x, y, z);
        GL11.glRotated(a, x, y, z);
    }

    public static void glScalef(float x, float y, float z) {
        Lists.other();
        Mirror.scale(x, y, z);
        GL11.glScalef(x, y, z);
    }

    public static void glScaled(double x, double y, double z) {
        Lists.other();
        Mirror.scale(x, y, z);
        GL11.glScaled(x, y, z);
    }

    public static void glMultMatrix(FloatBuffer m) {
        Lists.other();
        Mirror.mult(m);
        GL11.glMultMatrix(m);
    }

    // ------------------------------------------------------------------ immediate mode (the font's glyphs)

    public static void glBegin(int mode) {
        Lists.other();
        Capture.imBegin(mode);
        GL11.glBegin(mode);
    }

    public static void glEnd() {
        Lists.other();
        GL11.glEnd();
        Capture.imEnd();
    }

    public static void glTexCoord2f(float u, float v) {
        Lists.other();
        Capture.imTex(u, v);
        GL11.glTexCoord2f(u, v);
    }

    public static void glTexCoord2d(double u, double v) {
        Lists.other();
        Capture.imTex((float) u, (float) v);
        GL11.glTexCoord2d(u, v);
    }

    public static void glVertex3f(float x, float y, float z) {
        Lists.other();
        Capture.imVertex(x, y, z);
        GL11.glVertex3f(x, y, z);
    }

    public static void glVertex2f(float x, float y) {
        Lists.other();
        Capture.imVertex(x, y, 0);
        GL11.glVertex2f(x, y);
    }

    public static void glVertex3d(double x, double y, double z) {
        Lists.other();
        Capture.imVertex((float) x, (float) y, (float) z);
        GL11.glVertex3d(x, y, z);
    }

    public static void glVertex2d(double x, double y) {
        Lists.other();
        Capture.imVertex((float) x, (float) y, 0);
        GL11.glVertex2d(x, y);
    }
}
