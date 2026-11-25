package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * exp/shader-light, step 1: a vertex's smooth light as a recipe instead of a baked value. For an AO face on vanilla's
 * integer path, each vertex's light is blend(a, b, c, center) of four getLightColor values taken at positions within
 * one block of the block (ModelBlockRenderer.AmbientOcclusionFace.calculate: two edges, a corner or its fallback
 * edge, the center). Which positions depends on blocks only (occlusion flags, the face offset), not on light, so it
 * can be computed once at compile time and the light looked up later — in the shader, from a light volume.
 *
 * <p>Recipe: four offsets from the block, each in {-1, 0, 1}^3 (5 bits: (dx + 1) * 9 + (dy + 1) * 3 + dz + 1), and
 * the block's position in its section (12 bits): 32 bits. {@link #emulate} evaluates it exactly as the shader will.
 */
public final class LightRecipe {
    private LightRecipe() {}

    public static final boolean VERIFY = Boolean.getBoolean("lab.lightrecipe.verify");
    public static final AtomicLong faces = new AtomicLong(), vertices = new AtomicLong(), matches = new AtomicLong(), mismatches = new AtomicLong(),
            weighted = new AtomicLong();

    // ModelBlockRenderer's package-private AO tables, by reflection once (dev and runtime names are Mojang's)
    private static final Direction[][] CORNERS = new Direction[6][];
    private static final boolean[] NON_CUBIC = new boolean[6];
    private static final int[][] REMAP = new int[6][4];

    static {
        try {
            Class<?> adj = Class.forName("net.minecraft.client.renderer.block.ModelBlockRenderer$AdjacencyInfo");
            Class<?> remap = Class.forName("net.minecraft.client.renderer.block.ModelBlockRenderer$AmbientVertexRemap");
            Method adjOf = adj.getDeclaredMethod("fromFacing", Direction.class), remapOf = remap.getDeclaredMethod("fromFacing", Direction.class);
            adjOf.setAccessible(true);
            remapOf.setAccessible(true);
            Field corners = adj.getDeclaredField("corners"), nonCubic = adj.getDeclaredField("doNonCubicWeight");
            corners.setAccessible(true);
            nonCubic.setAccessible(true);
            String[] vn = {"vert0", "vert1", "vert2", "vert3"};
            for (Direction d : Direction.values()) {
                Object a = adjOf.invoke(null, d), r = remapOf.invoke(null, d);
                CORNERS[d.ordinal()] = (Direction[]) corners.get(a);
                NON_CUBIC[d.ordinal()] = nonCubic.getBoolean(a);
                for (int i = 0; i < 4; i++) {
                    Field f = remap.getDeclaredField(vn[i]);
                    f.setAccessible(true);
                    REMAP[d.ordinal()][i] = f.getInt(r);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AO tables not found", e);
        }
    }

    /** vanilla's full-face path is taken (not the weighted float blend of partial faces) */
    public static boolean integerPath(Direction dir, BitSet flags) {
        return !(flags.get(1) && NON_CUBIC[dir.ordinal()]);
    }

    private static int code(int dx, int dy, int dz) {
        return (dx + 1) * 9 + (dy + 1) * 3 + dz + 1;
    }

    private static int code(BlockPos pos, BlockPos p) {
        return code(p.getX() - pos.getX(), p.getY() - pos.getY(), p.getZ() - pos.getZ());
    }

    private static boolean open(BlockAndTintGetter level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.isViewBlocking(level, p) || s.getLightBlock(level, p) == 0;
    }

    /**
     * The four recipes of a face, indexed like AmbientOcclusionFace.lightmap (after its vertex remap). Mirrors
     * calculate()'s choices on the integer path (with NeoForge's MC-43968 fix: the flags are read at base + corner).
     */
    public static void recipes(BlockAndTintGetter level, BlockPos pos, Direction dir, BitSet flags, int[] out) {
        BlockPos base = flags.get(0) ? pos.relative(dir) : pos;
        Direction[] c = CORNERS[dir.ordinal()];
        BlockPos e0 = base.relative(c[0]), e1 = base.relative(c[1]), e2 = base.relative(c[2]), e3 = base.relative(c[3]);
        boolean f0 = open(level, e0), f1 = open(level, e1), f2 = open(level, e2), f3 = open(level, e3);
        BlockPos cA = !f2 && !f0 ? e0 : e0.relative(c[2]);   // i1
        BlockPos cB = !f3 && !f0 ? e0 : e0.relative(c[3]);   // j1
        BlockPos cC = !f2 && !f1 ? e1 : e1.relative(c[2]);   // k1
        BlockPos cD = !f3 && !f1 ? e1 : e1.relative(c[3]);   // l1
        BlockPos front = pos.relative(dir);
        BlockPos center = flags.get(0) || !level.getBlockState(front).isSolidRender(level, front) ? front : pos;
        int ce = code(pos, center);
        int local = ((pos.getX() & 15) << 8) | ((pos.getY() & 15) << 4) | (pos.getZ() & 15);
        int[] r = REMAP[dir.ordinal()];
        out[r[0]] = pack(code(pos, e3), code(pos, e0), code(pos, cB), ce, local);
        out[r[1]] = pack(code(pos, e2), code(pos, e0), code(pos, cA), ce, local);
        out[r[2]] = pack(code(pos, e2), code(pos, e1), code(pos, cC), ce, local);
        out[r[3]] = pack(code(pos, e3), code(pos, e1), code(pos, cD), ce, local);
    }

    /** second sample code markers: 30 = one sample with the block light raised to the quad's own emission (s2); 29 = full bright */
    public static final int FLAT_SELF = 30, FULL_BRIGHT = 29, FLUID_MAX = 28;

    /** LiquidBlockRenderer.getLightColor(level, p): per channel the max of p's and p.above()'s light */
    public static int fluid(BlockPos fluidPos, BlockPos p) {
        int local = ((fluidPos.getX() & 15) << 8) | ((fluidPos.getY() & 15) << 4) | (fluidPos.getZ() & 15);
        return pack(code(fluidPos, p), FLUID_MAX, code(fluidPos, p.above()), 0, local);
    }

    /**
     * A flat quad lit the way ModelBlockRenderer's flat paths and NeoForge's calculateFaceWithoutAO light it:
     * LevelRenderer.getLightColor(level, state of the quad's own block, sample) — the sample's light, but the block
     * light at least the block's own emission, or full bright for an emissive-rendering state.
     */
    public static int flatSelf(BlockAndTintGetter level, BlockState self, BlockPos pos, BlockPos sample) {
        int local = ((pos.getX() & 15) << 8) | ((pos.getY() & 15) << 4) | (pos.getZ() & 15);
        if (self.emissiveRendering(level, sample)) return pack(0, FULL_BRIGHT, 0, 0, local);
        int e = self.getLightEmission();
        return pack(code(pos, sample), FLAT_SELF, Math.min(15, e), 0, local);
    }

    /** a flat quad's recipe: its one light sample, four times (blend(x, x, x, x) = x) */
    public static int flat(BlockPos pos, BlockPos sample) {
        int c = code(pos, sample);
        int local = ((pos.getX() & 15) << 8) | ((pos.getY() & 15) << 4) | (pos.getZ() & 15);
        return pack(c, c, c, c, local);
    }

    private static int pack(int s0, int s1, int s2, int s3, int local) {
        return s0 | s1 << 5 | s2 << 10 | s3 << 15 | local << 20;
    }

    private static BlockPos at(BlockPos pos, int code) {
        return pos.offset(code / 9 - 1, code / 3 % 3 - 1, code % 3 - 1);
    }

    private static int light(BlockAndTintGetter level, BlockPos p) {
        return LevelRenderer.getLightColor(level, level.getBlockState(p), p);
    }

    /** What the shader computes: vanilla's integer blend of the four samples (the fourth, the center, fills zeros). */
    public static int emulate(BlockAndTintGetter level, BlockPos pos, int recipe) {
        int marker = recipe >>> 5 & 31;
        if (marker == FULL_BRIGHT) return 0xF000F0;
        if (marker == FLUID_MAX) {
            int i = light(level, at(pos, recipe & 31)), j = light(level, at(pos, recipe >>> 10 & 31));
            return Math.max(i & 255, j & 255) | Math.max(i >> 16 & 255, j >> 16 & 255) << 16;
        }
        if (marker == FLAT_SELF) {
            int lc = light(level, at(pos, recipe & 31)), e = recipe >>> 10 & 31;
            int block = Math.max(lc >> 4 & 15, e);
            return (lc & 0xF00000) | block << 4;
        }
        int a = light(level, at(pos, recipe & 31)), b = light(level, at(pos, recipe >>> 5 & 31)), c = light(level, at(pos, recipe >>> 10 & 31)),
                d = light(level, at(pos, recipe >>> 15 & 31));
        if (a == 0) a = d;
        if (b == 0) b = d;
        if (c == 0) c = d;
        return a + b + c + d >> 2 & 0xFF00FF;
    }

    /** Step 1 check, at the end of AmbientOcclusionFace.calculate: the recipes reproduce vanilla's lightmap. */
    public static void verify(BlockAndTintGetter level, BlockPos pos, Direction dir, BitSet flags, int[] lightmap) {
        faces.incrementAndGet();
        if (!integerPath(dir, flags)) {
            weighted.incrementAndGet();
            return;
        }
        int[] rec = new int[4];
        recipes(level, pos, dir, flags, rec);
        for (int i = 0; i < 4; i++) {
            vertices.incrementAndGet();
            int e = emulate(level, pos, rec[i]);
            if (e == lightmap[i]) matches.incrementAndGet();
            else if (mismatches.incrementAndGet() <= 10)
                System.out.println("[sleep/lightrecipe] MISMATCH " + level.getBlockState(pos) + " at " + pos + " face " + dir + " vertex " + i + ": recipe "
                        + Integer.toHexString(e) + " vanilla " + Integer.toHexString(lightmap[i]) + " flags " + flags);
        }
    }

    public static String report() {
        long v = Math.max(1, vertices.get());
        return String.format(java.util.Locale.ROOT, "light recipe: %d AO faces (%d weighted, left baked), %d vertices on the integer path, recipe = vanilla %.4f %% (%d mismatches)",
                faces.get(), weighted.get(), vertices.get(), 100.0 * matches.get() / v, mismatches.get());
    }
}
