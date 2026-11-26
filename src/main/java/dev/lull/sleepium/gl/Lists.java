package dev.lull.sleepium.gl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Display lists as they are compiled: a list made only of Tessellator draws (a model part's: ModelRenderer compiles its
 * faces this way, mods' models too) is kept as those draws' vertices, so a recording can take it when it is called -
 * through the matrix of that moment, in the state of that moment, the attributes the draws did not carry taken from
 * the current ones (as GL does). A list with anything else in it (state, matrices, its own GL draws) is "complex": an
 * object calling it is not cached.
 */
public final class Lists {
    private Lists() {}

    /** one Tessellator draw inside a list: its raw vertices (8 ints each: xyz, uv, colour, normal, lightmap) and what they carry */
    static final class Draw {
        final int[] raw;
        final int count, mode;
        final boolean tex, color, normals, light;

        Draw(int[] raw, int count, int mode, boolean tex, boolean color, boolean normals, boolean light) {
            this.raw = raw;
            this.count = count;
            this.mode = mode;
            this.tex = tex;
            this.color = color;
            this.normals = normals;
            this.light = light;
        }
    }

    static final class Rec {
        final List<Draw> draws = new ArrayList<>();
        boolean complex;
    }

    private static final HashMap<Integer, Rec> LISTS = new HashMap<>();
    private static Rec compiling;
    private static boolean execute;

    static void begin(int list, int mode) {
        compiling = new Rec();
        execute = mode == 4865;   // GL_COMPILE_AND_EXECUTE
        LISTS.put(list, compiling);
        if (execute) Capture.unsupported("a display list compiled while drawn");
    }

    static void end() {
        compiling = null;
    }

    public static boolean compiling() {
        return compiling != null;
    }

    /** a GL call inside a list being compiled that is not a Tessellator draw */
    static void other() {
        if (compiling != null) compiling.complex = true;
    }

    /** TessellatorCaptureMixin: a draw while a list is compiled */
    public static void tessellated(int[] raw, int count, int mode, boolean tex, boolean color, boolean normals, boolean light) {
        if (compiling == null) return;
        int[] copy = new int[count * 8];
        System.arraycopy(raw, 0, copy, 0, count * 8);
        compiling.draws.add(new Draw(copy, count, mode, tex, color, normals, light));
    }

    static Rec get(int list) {
        return LISTS.get(list);
    }

    /** glDeleteLists: the ids may be reused */
    public static void deleted(int list, int range) {
        for (int i = 0; i < range; i++) LISTS.remove(list + i);
    }
}
