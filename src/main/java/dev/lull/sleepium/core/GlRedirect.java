package dev.lull.sleepium.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Every class's calls of the GL11 methods Sleepium's GL wraps (dev.lull.sleepium.gl.GL, the same names and
 * signatures) pointed there: the call goes on to GL11 as before, and the drawing state, the matrix and the display lists
 * are seen on the way (1.7.10 has no GlStateManager: the game and every mod call GL11 directly). Only classes that
 * call GL11 are read; LWJGL, the JDK and Sleepium's GL itself are left alone. -Dsleep.gl.redirect=false: nothing changed.
 */
public final class GlRedirect implements IClassTransformer {
    static final boolean ON = !"false".equals(System.getProperty("sleep.gl.redirect"));
    private static final String GL11 = "org/lwjgl/opengl/GL11", OURS = "dev/lull/sleepium/gl/GL";
    /** generated from GL's methods (name + descriptor) */
    static final Set<String> WRAPPED = new HashSet<>(Arrays.asList(
            "glEnable(I)V",
            "glDisable(I)V",
            "glBlendFunc(II)V",
            "glAlphaFunc(IF)V",
            "glDepthFunc(I)V",
            "glDepthMask(Z)V",
            "glCullFace(I)V",
            "glShadeModel(I)V",
            "glColorMask(ZZZZ)V",
            "glColorMaterial(II)V",
            "glPolygonOffset(FF)V",
            "glBindTexture(II)V",
            "glTexParameteri(III)V",
            "glTexParameterf(IIF)V",
            "glColor4f(FFFF)V",
            "glColor3f(FFF)V",
            "glColor4ub(BBBB)V",
            "glColor3ub(BBB)V",
            "glColor4d(DDDD)V",
            "glColor3d(DDD)V",
            "glNormal3f(FFF)V",
            "glPushAttrib(I)V",
            "glPopAttrib()V",
            "glTexEnvi(III)V",
            "glTexEnvf(IIF)V",
            "glTexEnv(IILjava/nio/FloatBuffer;)V",
            "glTexEnv(IILjava/nio/IntBuffer;)V",
            "glTexGeni(III)V",
            "glTexGen(IILjava/nio/FloatBuffer;)V",
            "glLineWidth(F)V",
            "glPolygonMode(II)V",
            "glDrawArrays(III)V",
            "glNewList(II)V",
            "glEndList()V",
            "glDeleteLists(II)V",
            "glCallList(I)V",
            "glMatrixMode(I)V",
            "glPushMatrix()V",
            "glPopMatrix()V",
            "glLoadIdentity()V",
            "glLoadMatrix(Ljava/nio/FloatBuffer;)V",
            "glOrtho(DDDDDD)V",
            "glTranslatef(FFF)V",
            "glTranslated(DDD)V",
            "glRotatef(FFFF)V",
            "glRotated(DDDD)V",
            "glScalef(FFF)V",
            "glScaled(DDD)V",
            "glMultMatrix(Ljava/nio/FloatBuffer;)V",
            "glBegin(I)V",
            "glEnd()V",
            "glTexCoord2f(FF)V",
            "glTexCoord2d(DD)V",
            "glVertex3f(FFF)V",
            "glVertex2f(FF)V",
            "glVertex3d(DDD)V",
            "glVertex2d(DD)V"));
    private static final byte[] NEEDLE = GL11.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (!ON || bytes == null || transformedName.startsWith("org.lwjgl.") || transformedName.startsWith("java.") || transformedName.startsWith("sun.")
                || transformedName.startsWith("dev.lull.sleepium.gl.") || transformedName.startsWith("dev.lull.sleepium.core.") || !contains(bytes, NEEDLE)) return bytes;
        ClassReader r = new ClassReader(bytes);
        ClassWriter w = new ClassWriter(r, 0);
        boolean[] changed = {false};
        r.accept(new ClassVisitor(Opcodes.ASM5, w) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, mname, desc, sig, ex)) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC && GL11.equals(owner) && WRAPPED.contains(n + d)) {
                            owner = OURS;
                            changed[0] = true;
                        }
                        super.visitMethodInsn(op, owner, n, d, itf);
                    }
                };
            }
        }, 0);
        if (!changed[0]) return bytes;
        CLASSES++;
        return w.toByteArray();
    }

    public static int CLASSES;

    private static boolean contains(byte[] h, byte[] n) {
        outer:
        for (int i = 0, last = h.length - n.length; i <= last; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return true;
        }
        return false;
    }
}
