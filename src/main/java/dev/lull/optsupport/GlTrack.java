package dev.lull.optsupport;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

/**
 * The GPU terrain's GL objects, every one it creates and deletes going through here (render thread only), so that a
 * stop frees them all: on leaving a world, a dimension of another height, another view distance, a resource reload
 * (VoxelStream.lifecycle). After the delete every static int / int[] field of the owner classes still holding one of
 * the freed names is set to 0 - the lazy "create when 0" places create theirs again on the next start.
 */
public final class GlTrack {
    private GlTrack() {}

    private static final IntOpenHashSet BUF = new IntOpenHashSet(), TEX = new IntOpenHashSet(), VAO = new IntOpenHashSet(),
            PROG = new IntOpenHashSet(), SAMPLER = new IntOpenHashSet(), FBO = new IntOpenHashSet(), QUERY = new IntOpenHashSet();

    public static int buf(int id) { BUF.add(id); return id; }
    public static int tex(int id) { TEX.add(id); return id; }
    public static int vao(int id) { VAO.add(id); return id; }
    public static int prog(int id) { PROG.add(id); return id; }
    public static int sampler(int id) { SAMPLER.add(id); return id; }
    public static int fbo(int id) { FBO.add(id); return id; }

    /** glGenQueries(q), each name tracked */
    public static void queries(int[] q) {
        GL15C.glGenQueries(q);
        for (int id : q) QUERY.add(id);
    }

    public static void delBuf(int id) { if (id != 0) { BUF.remove(id); GL15C.glDeleteBuffers(id); } }
    public static void delBufs(int[] ids) { for (int id : ids) delBuf(id); }
    public static void delTex(int id) { if (id != 0) { TEX.remove(id); GL11C.glDeleteTextures(id); } }
    public static void delFbo(int id) { if (id != 0) { FBO.remove(id); GL30C.glDeleteFramebuffers(id); } }
    public static void delQueries(int[] q) { for (int id : q) QUERY.remove(id); GL15C.glDeleteQueries(q); }

    /** objects alive (a report line: what a stop frees) */
    public static int alive() {
        return BUF.size() + TEX.size() + VAO.size() + PROG.size() + SAMPLER.size() + FBO.size() + QUERY.size();
    }

    /** every tracked object deleted; the owners' static fields holding a freed name set to 0. Returns the count freed. */
    public static int freeAll(Class<?>... owners) {
        IntOpenHashSet all = new IntOpenHashSet();
        int n = 0;
        for (int id : BUF) { GL15C.glDeleteBuffers(id); all.add(id); n++; }
        for (int id : TEX) { GL11C.glDeleteTextures(id); all.add(id); n++; }
        for (int id : VAO) { GL30C.glDeleteVertexArrays(id); all.add(id); n++; }
        for (int id : PROG) { GL20C.glDeleteProgram(id); all.add(id); n++; }
        for (int id : SAMPLER) { GL33C.glDeleteSamplers(id); all.add(id); n++; }
        for (int id : FBO) { GL30C.glDeleteFramebuffers(id); all.add(id); n++; }
        for (int id : QUERY) { GL15C.glDeleteQueries(id); all.add(id); n++; }
        BUF.clear(); TEX.clear(); VAO.clear(); PROG.clear(); SAMPLER.clear(); FBO.clear(); QUERY.clear();
        for (Class<?> c : owners) {
            zero(c, all);
            zeroCapacities(c);
        }
        return n;
    }

    /** a buffer's capacity next to its name ("...Cap(s)", "...Capacity"; final constants stay): 0 with the name, so the next use makes a new buffer */
    private static void zeroCapacities(Class<?> c) {
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !f.getName().matches("(?i).*(caps?|capacity)")) continue;
            try {
                f.setAccessible(true);
                Class<?> t = f.getType();
                if (!Modifier.isFinal(f.getModifiers()) && t == long.class) f.setLong(null, 0);
                else if (!Modifier.isFinal(f.getModifiers()) && t == int.class) f.setInt(null, 0);
                else if (t == long[].class) java.util.Arrays.fill((long[]) f.get(null), 0);
                else if (t == int[].class) java.util.Arrays.fill((int[]) f.get(null), 0);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // not ours to reset
            }
        }
    }

    /** a static int / int[] / int[][] field whose value is a freed name: back to 0 (names are > 0; counters are long) */
    /** the fields that hold GL names by their names (a capacity or a counter that happens to equal a name stays) */
    private static final java.util.regex.Pattern HANDLE = java.util.regex.Pattern.compile(
            "(?i).*(buf|prog|tex|vao|fbo|sampler|quer).*|eq|tq|mq[be]|q[be]|bench_q|lean_c|draw[A-Z].*|low(color|depth)|occ[ab]"
                    + "|compactIdx|occVis(Prev|Next)|bench(Sec|Bases|Counts)");   // (a dev check lists handle fields this misses)

    private static void zero(Class<?> c, IntOpenHashSet freed) {
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !HANDLE.matcher(f.getName()).matches()) continue;
            try {
                f.setAccessible(true);
                Class<?> t = f.getType();
                if (t == int.class) {
                    if (Modifier.isFinal(f.getModifiers())) continue;
                    if (freed.contains(f.getInt(null))) f.setInt(null, 0);
                } else if (t == int[].class) {
                    zeroArray((int[]) f.get(null), freed);
                } else if (t == int[][].class) {
                    int[][] a = (int[][]) f.get(null);
                    if (a != null) for (int[] r : a) zeroArray(r, freed);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // a field we cannot reach holds no GL name of ours
            }
        }
    }

    private static void zeroArray(int[] a, IntOpenHashSet freed) {
        if (a == null) return;
        for (int i = 0; i < a.length; i++) if (a[i] > 0 && freed.contains(a[i])) a[i] = 0;
    }
}
