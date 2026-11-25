package dev.lull.sleepium.render;

import java.nio.FloatBuffer;
import java.util.List;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

/**
 * Model parts drawn in batches (-Dsleep.opt.partBatch): while an entity or block entity is drawn, the model matrix is
 * mirrored on the CPU (GlStateManager's matrix calls) and a model part (ModelRenderer) is not drawn as vanilla does it -
 * a push, translations, rotations and a display list per part - but its faces' vertices are computed on the CPU into
 * one buffer; the buffer is drawn when anything else is about to change GL's state or draw (every GlStateManager call
 * but the matrix ones, the lightmap coordinates, the end of the object). An armor stand in armour: a few draws instead
 * of ~250 GL calls. The vertices are in the space the object began in; the draw puts the matrix back there (the inverse
 * of what the renderer has applied since). Differences from vanilla: float rounding (CPU vs the driver's matrix math).
 * Not batched: inside a display list being compiled, a matrix mode other than the model view, a part tree with a mod's
 * own ModelRenderer / ModelBox / TexturedQuad class (drawn the vanilla way).
 */
public final class PartBatch {
    private PartBatch() {}

    public static final boolean ON = Boolean.getBoolean("sleep.opt.partBatch");
    private static final int MODELVIEW = GL11.GL_MODELVIEW;

    private static int scopes;
    private static boolean valid, flushing, compiling;
    private static int mode = MODELVIEW;
    private static final double[][] STACK = new double[64][16];
    private static int top;
    /** the batch: per vertex x y z (floats), u v (floats), the normal (3 bytes and a pad) - OLDMODEL_POSITION_TEX_NORMAL, 24 bytes */
    private static final int STRIDE_INTS = 6, CAP_VERTS = 1 << 16;
    private static final java.nio.ByteBuffer BYTES = GLAllocation.createDirectByteBuffer(CAP_VERTS * STRIDE_INTS * 4);
    private static final java.nio.IntBuffer INTS = BYTES.asIntBuffer();
    /** the draws' vertices go through one streamed GL buffer (client arrays make a threaded driver copy and wait at every draw) */
    private static final int RING = 16 << 20;
    private static int vbo, ringAt;
    private static final FloatBuffer FB = GLAllocation.createDirectFloatBuffer(16);
    private static int pending;
    public static long quads, draws, partsBatched, treesVanilla, scopesStarted;

    public static boolean active() {
        return ON && scopes > 0 && valid && !flushing && !compiling && mode == MODELVIEW;
    }

    /** the mirror's matrix now (relative to where the object began) */
    public static double[] current() {
        return STACK[top];
    }

    /** the mirror cannot follow the matrix (or is not running, or another matrix mode is current) */
    public static boolean mirrorLost() {
        return scopes == 0 || !valid || mode != MODELVIEW || compiling;
    }

    private static final double[][] CSTACK = new double[64][16];
    private static int ctop;

    /** EntityCache: a part tree's faces through a copy of the mirror (vanilla then draws it as usual) */
    public static void captureTree(ModelRenderer r, float scale) {
        if (!batchable(r)) {
            EntityCache.unsupported("a mod's model part");
            return;
        }
        System.arraycopy(STACK[top], 0, CSTACK[0], 0, 16);
        ctop = 0;
        cpart(r, scale);
    }

    private static void cpart(ModelRenderer r, float scale) {
        if (r.isHidden || !r.showModel) return;
        translate(CSTACK[ctop], r.offsetX, r.offsetY, r.offsetZ);
        if (r.rotateAngleX == 0.0F && r.rotateAngleY == 0.0F && r.rotateAngleZ == 0.0F) {
            if (r.rotationPointX == 0.0F && r.rotationPointY == 0.0F && r.rotationPointZ == 0.0F) {
                EntityCache.partQuads(data(r, scale), CSTACK[ctop]);
                cchildren(r, scale);
            } else {
                translate(CSTACK[ctop], r.rotationPointX * scale, r.rotationPointY * scale, r.rotationPointZ * scale);
                EntityCache.partQuads(data(r, scale), CSTACK[ctop]);
                cchildren(r, scale);
                translate(CSTACK[ctop], -r.rotationPointX * scale, -r.rotationPointY * scale, -r.rotationPointZ * scale);
            }
        } else {
            System.arraycopy(CSTACK[ctop], 0, CSTACK[ctop + 1], 0, 16);
            ctop++;
            translate(CSTACK[ctop], r.rotationPointX * scale, r.rotationPointY * scale, r.rotationPointZ * scale);
            if (r.rotateAngleZ != 0.0F) rotate(CSTACK[ctop], r.rotateAngleZ * (180F / (float) Math.PI), 0.0F, 0.0F, 1.0F);
            if (r.rotateAngleY != 0.0F) rotate(CSTACK[ctop], r.rotateAngleY * (180F / (float) Math.PI), 0.0F, 1.0F, 0.0F);
            if (r.rotateAngleX != 0.0F) rotate(CSTACK[ctop], r.rotateAngleX * (180F / (float) Math.PI), 1.0F, 0.0F, 0.0F);
            EntityCache.partQuads(data(r, scale), CSTACK[ctop]);
            cchildren(r, scale);
            ctop--;
        }
        translate(CSTACK[ctop], -r.offsetX, -r.offsetY, -r.offsetZ);
    }

    private static void cchildren(ModelRenderer r, float scale) {
        List<ModelRenderer> ch = r.childModels;
        if (ch != null) for (int i = 0; i < ch.size(); i++) cpart(ch.get(i), scale);
    }

    // ------------------------------------------------------------------ scopes (an entity / a block entity)

    /** an object's drawing begins; allowed: its renderer may be batched (for now: vanilla's renderers) */
    public static void begin(boolean allowed) {
        if (!ON && !EntityCache.ON) return;
        if (scopes++ > 0) return;   // nested (an entity inside a block entity's renderer): the same mirror goes on
        valid = allowed;
        mode = MODELVIEW;
        top = 0;
        identity(STACK[0]);
        scopesStarted++;
        if (MCHECK) {
            FB.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, FB);
            for (int i = 0; i < 16; i++) M0[i] = FB.get(i);
        }
    }

    /** -Dsleep.debug.mirrorCheck (debug, slow: reads GL's matrix back): the mirror against GL's real matrix at a draw */
    public static final boolean MCHECK = Boolean.getBoolean("sleep.debug.mirrorCheck");
    private static final double[] M0 = new double[16], CHK = new double[16];
    private static int mismatchesLogged;

    public static void mirrorCheck(String what) {
        if (!MCHECK || mismatchesLogged > 12 || scopes == 0 || !valid) return;
        FB.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, FB);
        System.arraycopy(M0, 0, CHK, 0, 16);
        mulRight(CHK, STACK[top]);
        double worst = 0;
        for (int i = 0; i < 16; i++) worst = Math.max(worst, Math.abs(CHK[i] - FB.get(i)));
        if (worst > 1e-3) {
            mismatchesLogged++;
            StringBuilder b = new StringBuilder("[sleepium] mirror off by " + worst + " at " + what + ": gl");
            for (int i = 0; i < 16; i++) b.append(' ').append(String.format(java.util.Locale.ROOT, "%.3f", FB.get(i)));
            b.append(" mirror");
            for (int i = 0; i < 16; i++) b.append(' ').append(String.format(java.util.Locale.ROOT, "%.3f", CHK[i]));
            System.out.println(b);
            new Throwable("where").printStackTrace(System.out);
        }
    }

    public static void end() {
        if (scopes == 0) return;
        if (--scopes > 0) return;
        flush();
        valid = false;
    }

    // ------------------------------------------------------------------ GlStateManager hooks

    /** any GL state change or draw is about to happen: what is batched goes first */
    public static void stateChange() {
        if (pending > 0 && !flushing) flush();
    }

    public static void newList() {
        stateChange();
        compiling = true;
    }

    public static void endList() {
        compiling = false;
    }

    public static void matrixMode(int m) {
        if (scopes == 0 || flushing) return;
        stateChange();
        mode = m;
    }

    public static void push() {
        if (scopes == 0 || flushing || mode != MODELVIEW || !valid) return;
        if (top + 1 >= STACK.length) {
            invalidate();
            return;
        }
        System.arraycopy(STACK[top], 0, STACK[top + 1], 0, 16);
        top++;
    }

    public static void pop() {
        if (scopes == 0 || flushing || mode != MODELVIEW || !valid) return;
        if (top == 0) {   // popped past where the object began: the mirror cannot follow
            invalidate();
            return;
        }
        top--;
    }

    public static void translate(double x, double y, double z) {
        if (scopes == 0 || flushing || mode != MODELVIEW || !valid) return;
        translate(STACK[top], x, y, z);
    }

    public static void scale(double x, double y, double z) {
        if (scopes == 0 || flushing || mode != MODELVIEW || !valid) return;
        double[] m = STACK[top];
        for (int i = 0; i < 4; i++) {
            m[i] *= x;
            m[4 + i] *= y;
            m[8 + i] *= z;
        }
    }

    public static void rotate(float angle, float x, float y, float z) {
        if (scopes == 0 || flushing || mode != MODELVIEW || !valid) return;
        rotate(STACK[top], angle, x, y, z);
    }

    public static void mult(FloatBuffer b) {
        if (scopes == 0 || flushing || mode != MODELVIEW || !valid) return;
        double[] r = new double[16];
        int p = b.position();
        for (int i = 0; i < 16; i++) r[i] = b.get(p + i);
        mulRight(STACK[top], r);
    }

    /** a matrix call the mirror cannot follow (loadIdentity / ortho in the model view): the rest of the object the vanilla way */
    public static void invalidate() {
        if (scopes == 0 || flushing) return;
        stateChange();
        valid = false;
    }

    // ------------------------------------------------------------------ model parts

    /** ModelRenderer.render in a scope: true when done here (the part tree batched), false: the vanilla way */
    public static boolean render(ModelRenderer r, float scale) {
        if (!batchable(r)) {
            stateChange();
            treesVanilla++;
            return false;
        }
        part(r, scale);
        return true;
    }

    /** ModelRenderer.renderWithRotation (no offset, no children, Y X Z) */
    public static boolean renderWithRotation(ModelRenderer r, float scale) {
        if (r.getClass() != ModelRenderer.class || !boxesVanilla(r.cubeList)) {
            stateChange();
            treesVanilla++;
            return false;
        }
        if (r.isHidden || !r.showModel) return true;
        push();
        translate(STACK[top], r.rotationPointX * scale, r.rotationPointY * scale, r.rotationPointZ * scale);
        if (r.rotateAngleY != 0.0F) rotate(STACK[top], r.rotateAngleY * (180F / (float) Math.PI), 0.0F, 1.0F, 0.0F);
        if (r.rotateAngleX != 0.0F) rotate(STACK[top], r.rotateAngleX * (180F / (float) Math.PI), 1.0F, 0.0F, 0.0F);
        if (r.rotateAngleZ != 0.0F) rotate(STACK[top], r.rotateAngleZ * (180F / (float) Math.PI), 0.0F, 0.0F, 1.0F);
        emit(r, scale);
        pop();
        return true;
    }

    private static boolean batchable(ModelRenderer r) {
        if (r.getClass() != ModelRenderer.class || !boxesVanilla(r.cubeList)) return false;
        List<ModelRenderer> ch = r.childModels;
        if (ch != null) for (int i = 0; i < ch.size(); i++) if (!batchable(ch.get(i))) return false;
        return true;
    }

    private static boolean boxesVanilla(List<ModelBox> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            ModelBox b = boxes.get(i);
            if (b.getClass() != ModelBox.class) return false;
            for (TexturedQuad q : ((dev.lull.sleepium.mixin.ModelBoxAccessor) b).sleep$quads())
                if (q.getClass() != TexturedQuad.class) return false;
        }
        return true;
    }

    /** ModelRenderer.render's transforms on the mirror, then its faces and its children */
    private static void part(ModelRenderer r, float scale) {
        if (r.isHidden || !r.showModel) return;
        partsBatched++;
        double[] m = STACK[top];
        translate(m, r.offsetX, r.offsetY, r.offsetZ);
        if (r.rotateAngleX == 0.0F && r.rotateAngleY == 0.0F && r.rotateAngleZ == 0.0F) {
            if (r.rotationPointX == 0.0F && r.rotationPointY == 0.0F && r.rotationPointZ == 0.0F) {
                emit(r, scale);
                children(r, scale);
            } else {
                translate(STACK[top], r.rotationPointX * scale, r.rotationPointY * scale, r.rotationPointZ * scale);
                emit(r, scale);
                children(r, scale);
                translate(STACK[top], -r.rotationPointX * scale, -r.rotationPointY * scale, -r.rotationPointZ * scale);
            }
        } else {
            push();
            translate(STACK[top], r.rotationPointX * scale, r.rotationPointY * scale, r.rotationPointZ * scale);
            if (r.rotateAngleZ != 0.0F) rotate(STACK[top], r.rotateAngleZ * (180F / (float) Math.PI), 0.0F, 0.0F, 1.0F);
            if (r.rotateAngleY != 0.0F) rotate(STACK[top], r.rotateAngleY * (180F / (float) Math.PI), 0.0F, 1.0F, 0.0F);
            if (r.rotateAngleX != 0.0F) rotate(STACK[top], r.rotateAngleX * (180F / (float) Math.PI), 1.0F, 0.0F, 0.0F);
            emit(r, scale);
            children(r, scale);
            pop();
        }
        translate(STACK[top], -r.offsetX, -r.offsetY, -r.offsetZ);
    }

    private static void children(ModelRenderer r, float scale) {
        List<ModelRenderer> ch = r.childModels;
        if (ch != null) for (int i = 0; i < ch.size(); i++) part(ch.get(i), scale);
    }

    /** a part's faces in its own space, computed once: per quad 4 x (x y z u v), scaled; per quad its normal (TexturedQuad.draw's) */
    public static final class PartData {
        final float scale;
        final int boxes, quads;
        final float[] v, n;

        PartData(ModelRenderer r, float scale) {
            this.scale = scale;
            List<ModelBox> bl = r.cubeList;
            boxes = bl.size();
            int nq = 0;
            for (int i = 0; i < bl.size(); i++) nq += ((dev.lull.sleepium.mixin.ModelBoxAccessor) bl.get(i)).sleep$quads().length;
            quads = nq;
            v = new float[nq * 20];
            n = new float[nq * 3];
            int qi = 0;
            for (int bi = 0; bi < bl.size(); bi++) {
                for (TexturedQuad q : ((dev.lull.sleepium.mixin.ModelBoxAccessor) bl.get(bi)).sleep$quads()) {
                    PositionTextureVertex[] pv = q.vertexPositions;
                    Vec3d a = pv[1].vector3D.subtractReverse(pv[0].vector3D);
                    Vec3d b = pv[1].vector3D.subtractReverse(pv[2].vector3D);
                    Vec3d c = b.crossProduct(a).normalize();
                    float nx = (float) c.x, ny = (float) c.y, nz = (float) c.z;
                    if (((dev.lull.sleepium.mixin.TexturedQuadAccessor) q).sleep$inverted()) {
                        nx = -nx;
                        ny = -ny;
                        nz = -nz;
                    }
                    n[qi * 3] = nx;
                    n[qi * 3 + 1] = ny;
                    n[qi * 3 + 2] = nz;
                    for (int i = 0; i < 4; i++) {
                        PositionTextureVertex p = pv[i];
                        int o = qi * 20 + i * 5;
                        v[o] = (float) (p.vector3D.x * (double) scale);
                        v[o + 1] = (float) (p.vector3D.y * (double) scale);
                        v[o + 2] = (float) (p.vector3D.z * (double) scale);
                        v[o + 3] = p.texturePositionX;
                        v[o + 4] = p.texturePositionY;
                    }
                    qi++;
                }
            }
        }
    }

    public interface Holder {
        PartData sleep$partData();

        void sleep$partData(PartData d);
    }

    private static PartData data(ModelRenderer r, float scale) {
        Holder h = (Holder) r;
        PartData d = h.sleep$partData();
        if (d == null || d.scale != scale || d.boxes != r.cubeList.size()) h.sleep$partData(d = new PartData(r, scale));
        return d;
    }

    /** the part's faces through the mirror's matrix into the batch (a tight loop: no BufferBuilder, no objects) */
    private static void emit(ModelRenderer r, float scale) {
        PartData d = data(r, scale);
        if (d.quads == 0) return;
        if ((pending + d.quads) * 4 > CAP_VERTS) flush();
        double[] m = STACK[top];
        double m0 = m[0], m1 = m[1], m2 = m[2], m4 = m[4], m5 = m[5], m6 = m[6], m8 = m[8], m9 = m[9], m10 = m[10], m12 = m[12], m13 = m[13], m14 = m[14];
        float[] v = d.v, n = d.n;
        java.nio.IntBuffer out = INTS;
        int w = pending * 4 * STRIDE_INTS;
        for (int q = 0; q < d.quads; q++) {
            double nx = n[q * 3], ny = n[q * 3 + 1], nz = n[q * 3 + 2];
            double tx = m0 * nx + m4 * ny + m8 * nz, ty = m1 * nx + m5 * ny + m9 * nz, tz = m2 * nx + m6 * ny + m10 * nz;
            double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (len > 0) {
                tx /= len;
                ty /= len;
                tz /= len;
            }
            int packed = ((byte) ((int) ((float) tx * 127.0F)) & 255) | (((byte) ((int) ((float) ty * 127.0F)) & 255) << 8) | (((byte) ((int) ((float) tz * 127.0F)) & 255) << 16);
            for (int i = 0; i < 4; i++) {
                int o = q * 20 + i * 5;
                double x = v[o], y = v[o + 1], z = v[o + 2];
                out.put(w, Float.floatToRawIntBits((float) (m0 * x + m4 * y + m8 * z + m12)));
                out.put(w + 1, Float.floatToRawIntBits((float) (m1 * x + m5 * y + m9 * z + m13)));
                out.put(w + 2, Float.floatToRawIntBits((float) (m2 * x + m6 * y + m10 * z + m14)));
                out.put(w + 3, Float.floatToRawIntBits(v[o + 3]));
                out.put(w + 4, Float.floatToRawIntBits(v[o + 4]));
                out.put(w + 5, packed);
                w += STRIDE_INTS;
            }
        }
        pending += d.quads;
        quads += d.quads;
    }

    /** the batch drawn with the matrix where the object began (the renderer's transforms since then undone) */
    public static void flush() {
        if (pending == 0 || flushing) return;
        flushing = true;
        try {
            double[] inv = invert(STACK[top]);
            FB.clear();
            for (int i = 0; i < 16; i++) FB.put((float) inv[i]);
            FB.flip();
            GL11.glPushMatrix();
            GL11.glMultMatrix(FB);
            java.nio.ByteBuffer b = BYTES;
            int bytes = pending * 4 * STRIDE_INTS * 4;
            b.limit(bytes);
            b.position(0);
            if (vbo == 0) {
                vbo = org.lwjgl.opengl.GL15.glGenBuffers();
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, vbo);
                org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, RING, org.lwjgl.opengl.GL15.GL_STREAM_DRAW);
            } else org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, vbo);
            if (ringAt + bytes > RING) {   // a fresh buffer store (the driver keeps the old one for draws in flight)
                org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, RING, org.lwjgl.opengl.GL15.GL_STREAM_DRAW);
                ringAt = 0;
            }
            org.lwjgl.opengl.GL15.glBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, ringAt, b);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, 24, ringAt);
            net.minecraft.client.renderer.OpenGlHelper.setClientActiveTexture(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 24, ringAt + 12);
            GL11.glNormalPointer(GL11.GL_BYTE, 24, ringAt + 20);
            ringAt += bytes;
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, pending * 4);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
            b.clear();
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
            GL11.glPopMatrix();
            draws++;
        } finally {
            pending = 0;
            flushing = false;
        }
    }

    // ------------------------------------------------------------------ column-major 4x4 matrices (as GL)

    private static void identity(double[] m) {
        java.util.Arrays.fill(m, 0);
        m[0] = m[5] = m[10] = m[15] = 1;
    }

    private static void translate(double[] m, double x, double y, double z) {
        for (int i = 0; i < 4; i++) m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z;
    }

    private static final double[] R = new double[16];

    /** glRotate's matrix (OpenGL 2.1 spec), multiplied on the right */
    private static void rotate(double[] m, float angle, float ax, float ay, float az) {
        double len = Math.sqrt((double) ax * ax + (double) ay * ay + (double) az * az);
        if (len == 0) return;
        double x = ax / len, y = ay / len, z = az / len, rad = Math.toRadians(angle), c = Math.cos(rad), s = Math.sin(rad), t = 1 - c;
        double[] r = R;
        identity(r);
        r[0] = x * x * t + c;
        r[1] = y * x * t + z * s;
        r[2] = x * z * t - y * s;
        r[4] = x * y * t - z * s;
        r[5] = y * y * t + c;
        r[6] = y * z * t + x * s;
        r[8] = x * z * t + y * s;
        r[9] = y * z * t - x * s;
        r[10] = z * z * t + c;
        mulRight(m, r);
    }

    private static final double[] T = new double[16];

    private static void mulRight(double[] m, double[] r) {
        for (int col = 0; col < 4; col++)
            for (int row = 0; row < 4; row++)
                T[col * 4 + row] = m[row] * r[col * 4] + m[4 + row] * r[col * 4 + 1] + m[8 + row] * r[col * 4 + 2] + m[12 + row] * r[col * 4 + 3];
        System.arraycopy(T, 0, m, 0, 16);
    }

    private static final double[] INV = new double[16];

    /** the inverse of an affine matrix (the last row 0 0 0 1) */
    private static double[] invert(double[] m) {
        double a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        double[] o = INV;
        identity(o);
        if (det == 0) return o;
        double id = 1 / det;
        o[0] = A * id;
        o[4] = -(b * i - c * h) * id;
        o[8] = (b * f - c * e) * id;
        o[1] = B * id;
        o[5] = (a * i - c * g) * id;
        o[9] = -(a * f - c * d) * id;
        o[2] = C * id;
        o[6] = -(a * h - b * g) * id;
        o[10] = (a * e - b * d) * id;
        double tx = m[12], ty = m[13], tz = m[14];
        o[12] = -(o[0] * tx + o[4] * ty + o[8] * tz);
        o[13] = -(o[1] * tx + o[5] * ty + o[9] * tz);
        o[14] = -(o[2] * tx + o[6] * ty + o[10] * tz);
        return o;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "part batch: %d objects, %d parts batched, %d quads, %d draws, %d trees the vanilla way", scopesStarted, partsBatched, quads, draws, treesVanilla);
    }
}
