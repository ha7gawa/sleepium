package dev.lull.sleepium.gl;

import java.nio.FloatBuffer;

/**
 * The model view matrix while an object is recorded (Capture.begin .. end), relative to where its drawing began
 * (identity there): the GL calls that move it, mirrored in double precision (column-major as GL). Calls it cannot follow
 * (loadIdentity, loadMatrix, ortho in the model view; popping past the start) make it lost: the object is not cached.
 */
public final class Mirror {
    private Mirror() {}

    private static final int MODELVIEW = 5888;
    static int mode = MODELVIEW;
    private static final double[][] STACK = new double[64][16];
    private static int top;
    private static boolean on, lost;

    static void begin() {
        on = true;
        lost = false;
        top = 0;
        identity(STACK[0]);
    }

    static void end() {
        on = false;
    }

    /** the matrix now, relative to the object's start (null: lost or another matrix mode) */
    static double[] current() {
        return lost || mode != MODELVIEW ? null : STACK[top];
    }

    static int depth() {
        return top;
    }

    static void mode(int m) {
        mode = m;
        if (on && m != MODELVIEW) Capture.unsupported("matrix mode " + m);
    }

    static void push() {
        if (!on || mode != MODELVIEW || lost) return;
        if (top + 1 >= STACK.length) {
            lost("deep matrix stack");
            return;
        }
        System.arraycopy(STACK[top], 0, STACK[top + 1], 0, 16);
        top++;
    }

    static void pop() {
        if (!on || mode != MODELVIEW || lost) return;
        if (top == 0) {
            lost("popped past the start");
            return;
        }
        top--;
    }

    static void lost() {
        if (on && mode == MODELVIEW) lost("matrix replaced");
    }

    private static void lost(String why) {
        lost = true;
        Capture.unsupported(why);
    }

    static void translate(double x, double y, double z) {
        if (!on || mode != MODELVIEW || lost) return;
        translate(STACK[top], x, y, z);
    }

    static void scale(double x, double y, double z) {
        if (!on || mode != MODELVIEW || lost) return;
        double[] m = STACK[top];
        for (int i = 0; i < 4; i++) {
            m[i] *= x;
            m[4 + i] *= y;
            m[8 + i] *= z;
        }
    }

    static void rotate(double angle, double ax, double ay, double az) {
        if (!on || mode != MODELVIEW || lost) return;
        rotate(STACK[top], angle, ax, ay, az);
    }

    static void mult(FloatBuffer b) {
        if (!on || mode != MODELVIEW || lost) return;
        double[] r = new double[16];
        int p = b.position();
        for (int i = 0; i < 16; i++) r[i] = b.get(p + i);
        mulRight(STACK[top], r);
    }

    // ------------------------------------------------------------------ column-major 4x4 (as GL)

    static void identity(double[] m) {
        java.util.Arrays.fill(m, 0);
        m[0] = m[5] = m[10] = m[15] = 1;
    }

    static void translate(double[] m, double x, double y, double z) {
        for (int i = 0; i < 4; i++) m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z;
    }

    private static final double[] R = new double[16], T = new double[16];

    /** glRotate's matrix (OpenGL 2.1 spec), multiplied on the right */
    static void rotate(double[] m, double angle, double ax, double ay, double az) {
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        if (len == 0) return;
        double x = ax / len, y = ay / len, z = az / len, rad = Math.toRadians(angle), c = Math.cos(rad), s = Math.sin(rad), t = 1 - c;
        identity(R);
        R[0] = x * x * t + c;
        R[1] = y * x * t + z * s;
        R[2] = x * z * t - y * s;
        R[4] = x * y * t - z * s;
        R[5] = y * y * t + c;
        R[6] = y * z * t + x * s;
        R[8] = x * z * t + y * s;
        R[9] = y * z * t - x * s;
        R[10] = z * z * t + c;
        mulRight(m, R);
    }

    static void mulRight(double[] m, double[] r) {
        for (int col = 0; col < 4; col++)
            for (int row = 0; row < 4; row++)
                T[col * 4 + row] = m[row] * r[col * 4] + m[4 + row] * r[col * 4 + 1] + m[8 + row] * r[col * 4 + 2] + m[12 + row] * r[col * 4 + 3];
        System.arraycopy(T, 0, m, 0, 16);
    }
}
