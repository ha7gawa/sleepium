package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * -Dlab.voxel.compute.exact=K (with lab.voxel.compute, notes/61): the GPU mesher against vanilla, vertex by vertex, on
 * K sections spread over the view. Vanilla's side is the real BlockRenderDispatcher.renderBatched per cell (the seed,
 * model data and render types SectionCompiler uses) into a capturing consumer; the GPU's side runs the same vanilla
 * putBulkData with the GPU's per-face brightnesses and lightmap values (the tint from the real BlockColors, the quad
 * data from the library), so everything the GPU decides - which quads, their light and AO - is what is compared.
 * Per cell and layer the quads are matched in order; mismatches are counted by kind and by block.
 */
final class VoxelExact {
    private VoxelExact() {}

    /** a consumer that keeps every vertex as 10 ints: x y z u v (float bits), colour, light, normal x y z (float bits) */
    private static final class Capture implements VertexConsumer {
        final List<int[]> verts = new ArrayList<>();

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            verts.add(new int[] {Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), Float.floatToRawIntBits(z), Float.floatToRawIntBits(u),
                    Float.floatToRawIntBits(v), color, light, Float.floatToRawIntBits(nx), Float.floatToRawIntBits(ny), Float.floatToRawIntBits(nz)});
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            throw new UnsupportedOperationException("vertex by parts");
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    private static final String[] FIELDS = {"x", "y", "z", "u", "v", "colour", "light", "nx", "ny", "nz"};

    /** quads (4 vertices) grouped by cell and layer, in emission order */
    private static void group(Map<String, List<int[][]>> into, String key, List<int[]> verts) {
        List<int[][]> list = into.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i + 3 < verts.size(); i += 4) list.add(new int[][] {verts.get(i), verts.get(i + 1), verts.get(i + 2), verts.get(i + 3)});
    }

    static String run(int k, int sectionCount, List<int[]> sections, int[] ranges, java.util.function.IntFunction<int[]> faces,
            int gx0, int gy0, int gz0, List<BakedQuad> libQuads, int[] libLayer, float[] quadOffsetTable) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var br = mc.getBlockRenderer();
        var colors = mc.getBlockColors();
        RenderType[] layers = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};
        int step = Math.max(1, sectionCount / Math.max(1, k));
        long lightChanged = 0, unexplained = 0, uMissing = 0, uExtra = 0, uColour = 0, uLight = 0;
        Map<BlockState, Integer> localOf = new java.util.IdentityHashMap<>();
        for (int i = 0; i < VoxelCompute.snapStates.size(); i++) localOf.put(VoxelCompute.snapStates.get(i), i);
        List<String> uSamples = new ArrayList<>();
        long vanillaQuads = 0, gpuQuads = 0, exact = 0, colourOnly = 0, lightOnly = 0, colourLight = 0, otherField = 0, missing = 0, extra = 0;
        Map<String, int[]> byBlock = new HashMap<>();   // block -> missing, extra, colour, light, other
        List<String> samples = new ArrayList<>();
        int checked = 0;
        RandomSource rnd = RandomSource.create();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int si = 0; si < sectionCount && checked < k; si += step, checked++) {
            int[] s = sections.get(si);
            int ox = gx0 + s[0], oy = gy0 + s[1], oz = gz0 + s[2];
            Map<String, List<int[][]>> van = new LinkedHashMap<>(), gpu = new LinkedHashMap<>();
            if (VoxelCompute.lightSnap != null) {   // light that changed since the GPU's snapshot (the section and its rim)
                var skyL = level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.SKY);
                var blkL = level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
                for (int y = -1; y <= 16; y++)
                    for (int z = -1; z <= 16; z++)
                        for (int x = -1; x <= 16; x++) {
                            int gx = s[0] + x, gy = s[1] + y, gz = s[2] + z;
                            if (gx < 0 || gy < 0 || gz < 0 || gx >= VoxelCompute.gridNx || gy >= VoxelCompute.gridNy || gz >= VoxelCompute.gridNz) continue;
                            mp.set(gx0 + gx, gy0 + gy, gz0 + gz);
                            int now = skyL.getLightValue(mp) << 4 | blkL.getLightValue(mp);
                            int then = VoxelCompute.lightSnap[gx + VoxelCompute.gridNx * (gy + VoxelCompute.gridNy * gz)] & 255;
                            if (now != then) lightChanged++;
                        }
            }
            // vanilla: SectionCompiler's per-block path (no fluids, no block entities)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockPos pos = new BlockPos(ox + x, oy + y, oz + z);
                        BlockState st = level.getBlockState(pos);
                        if (st.getRenderShape() != RenderShape.MODEL) continue;
                        var model = br.getBlockModel(st);
                        var data = model.getModelData(level, pos, st, level.getModelData(pos));
                        rnd.setSeed(st.getSeed(pos));
                        for (RenderType rt : model.getRenderTypes(st, rnd, data)) {
                            int li = rt == layers[0] ? 0 : rt == layers[1] ? 1 : rt == layers[2] ? 2 : 3;
                            Capture c = new Capture();
                            PoseStack ps = new PoseStack();
                            ps.translate((float) x, (float) y, (float) z);
                            br.renderBatched(st, pos, level, ps, c, true, rnd, data, rt);
                            group(van, x + "," + y + "," + z + "/" + li, c.verts);
                        }
                    }
            // the GPU: its faces through vanilla's putBulkData
            int base = ranges[si * 2], count = ranges[si * 2 + 1];
            int[] f = faces.apply(si);
            for (int i = 0; i < count; i++) {
                int pk = f[i * 8], q = f[i * 8 + 1] & 0xFFFF, nib = f[i * 8 + 1] >>> 16;
                int cx = pk & 1023, cy = pk >>> 10 & 1023, cz = pk >>> 20 & 1023;
                int x = cx - s[0], y = cy - s[1], z = cz - s[2];
                BlockPos pos = new BlockPos(gx0 + cx, gy0 + cy, gz0 + cz);
                BlockState st = level.getBlockState(pos);
                BakedQuad bq = libQuads.get(q);
                float[] bright = new float[4];
                int[] lm = new int[4];
                for (int v = 0; v < 4; v++) {
                    bright[v] = Float.intBitsToFloat(f[i * 8 + 2 + v]);
                    int w = f[i * 8 + 6 + (v >> 1)] >>> ((v & 1) * 16);
                    lm[v] = (w & 255) | (w >>> 8 & 255) << 16;
                }
                float r = 1f, g = 1f, b = 1f;
                if (bq.isTinted()) {
                    int col = colors.getColor(st, level, pos, bq.getTintIndex());
                    r = (col >> 16 & 255) / 255.0F;
                    g = (col >> 8 & 255) / 255.0F;
                    b = (col & 255) / 255.0F;
                }
                Capture c = new Capture();
                PoseStack ps = new PoseStack();
                ps.translate((float) x, (float) y, (float) z);
                int ot = (int) quadOffsetTable[q];
                if (ot > 0) {   // vanilla: poseStack.translate(state.getOffset(...)) after the cell's translate
                    float[] tab = VoxelCompute.OFFSET_TABLES.get(ot - 1);
                    ps.translate(tab[nib & 15], tab[16 + (nib >> 4 & 15)], tab[32 + (nib >> 8 & 15)]);
                }
                c.putBulkData(ps.last(), bq, bright, r, g, b, 1.0F, lm, OverlayTexture.NO_OVERLAY, true);
                group(gpu, x + "," + y + "," + z + "/" + libLayer[q], c.verts);
            }
            // compare per cell and layer, in order
            java.util.Set<String> keys = new java.util.LinkedHashSet<>(van.keySet());
            keys.addAll(gpu.keySet());
            for (String key : keys) {
                List<int[][]> a = van.getOrDefault(key, List.of()), bb = gpu.getOrDefault(key, List.of());
                vanillaQuads += a.size();
                gpuQuads += bb.size();
                String[] xyz = key.substring(0, key.indexOf('/')).split(",");
                mp.set(ox + Integer.parseInt(xyz[0]), oy + Integer.parseInt(xyz[1]), oz + Integer.parseInt(xyz[2]));
                String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(mp).getBlock()).getPath();
                int[] tally = byBlock.computeIfAbsent(block, kk -> new int[5]);
                boolean[] used = new boolean[bb.size()];
                for (int[][] qa : a) {
                    int hit = -1;
                    for (int j = 0; j < bb.size() && hit < 0; j++)   // the same corners (position and UV) = the same quad
                        if (!used[j] && sameGeometry(qa, bb.get(j))) hit = j;
                    if (hit < 0) {
                        missing++;
                        tally[0]++;
                        if (!changedNear(level, mp.getX() - gx0, mp.getY() - gy0, mp.getZ() - gz0, gx0, gy0, gz0, localOf)) {
                            unexplained++;
                            uMissing++;
                            if (uSamples.size() < 12) uSamples.add("missing " + block + " @" + mp.toShortString() + " " + key + " " + quadStr(qa));
                        }
                        if (samples.size() < 12) samples.add("missing " + block + " @" + mp.toShortString() + " " + key + " " + quadStr(qa));
                        continue;
                    }
                    used[hit] = true;
                    int[][] qb = bb.get(hit);
                    boolean col = false, lig = false, oth = false;
                    for (int v = 0; v < 4; v++)
                        for (int fi = 0; fi < 10; fi++)
                            if (qa[v][fi] != qb[v][fi]) {
                                if (fi == 5) col = true;
                                else if (fi == 6) lig = true;
                                else oth = true;
                            }
                    if (!col && !lig && !oth) exact++;
                    else if (oth) {
                        otherField++;
                        tally[4]++;
                    } else if (col && lig) colourLight++;
                    else if (col) colourOnly++;
                    else lightOnly++;
                    if (col) tally[2]++;
                    if (lig) tally[3]++;
                    if ((col || lig || oth) && !changedNear(level, mp.getX() - gx0, mp.getY() - gy0, mp.getZ() - gz0, gx0, gy0, gz0, localOf)) {
                        unexplained++;
                        if (col) uColour++;
                        if (lig) uLight++;
                        if (uSamples.size() < 12) uSamples.add("diff " + block + " @" + mp.toShortString() + " " + key + " " + diffStr(qa, qb));
                    }
                    if ((col || lig || oth) && samples.size() < 12) samples.add("diff " + block + " @" + mp.toShortString() + " " + key + " " + diffStr(qa, qb));
                }
                for (int j = 0; j < bb.size(); j++)
                    if (!used[j]) {
                        extra++;
                        tally[1]++;
                        if (!changedNear(level, mp.getX() - gx0, mp.getY() - gy0, mp.getZ() - gz0, gx0, gy0, gz0, localOf)) {
                            unexplained++;
                            uExtra++;
                            if (uSamples.size() < 12) uSamples.add("extra " + block + " @" + mp.toShortString() + " " + key + " " + quadStr(bb.get(j)));
                        }
                        if (samples.size() < 12) samples.add("extra " + block + " @" + mp.toShortString() + " " + key + " " + quadStr(bb.get(j)));
                    }
            }
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "voxel exact: %d sections checked; vanilla quads %d, GPU quads %d; exact %d (%.3f %%), colour only %d, light only %d, colour + light %d, other field %d, missing %d, extra %d; cells whose light changed since the GPU's snapshot %d",
                checked, vanillaQuads, gpuQuads, exact, 100.0 * exact / Math.max(1, vanillaQuads), colourOnly, lightOnly, colourLight, otherField, missing, extra, lightChanged));
        List<Map.Entry<String, int[]>> top = new ArrayList<>(byBlock.entrySet());
        top.removeIf(e -> e.getValue()[0] + e.getValue()[1] + e.getValue()[2] + e.getValue()[3] + e.getValue()[4] == 0);
        top.sort((x, y) -> Integer.compare(y.getValue()[0] + y.getValue()[1] + y.getValue()[2] + y.getValue()[3] + y.getValue()[4],
                x.getValue()[0] + x.getValue()[1] + x.getValue()[2] + x.getValue()[3] + x.getValue()[4]));
        sb.append("\n  by block (missing / extra / colour / light / other):");
        for (int i = 0; i < Math.min(15, top.size()); i++) {
            int[] t = top.get(i).getValue();
            sb.append(String.format(Locale.ROOT, " %s %d/%d/%d/%d/%d;", top.get(i).getKey(), t[0], t[1], t[2], t[3], t[4]));
        }
        sb.append(String.format(Locale.ROOT, "%n  NOT explained by a block or light change within 2 cells after the snapshot: %d (missing %d, extra %d, colour %d, light %d)",
                unexplained, uMissing, uExtra, uColour, uLight));
        for (String s : (uSamples.isEmpty() ? samples : uSamples)) sb.append("\n  ").append(s);
        return sb.toString();
    }

    /** did a block or the light within 2 cells of the grid cell (gx, gy, gz) change after the GPU's snapshot? */
    private static boolean changedNear(net.minecraft.world.level.Level level, int gx, int gy, int gz, int gx0, int gy0, int gz0,
            Map<BlockState, Integer> localOf) {
        var skyL = level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        var blkL = level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -2; dy <= 2; dy++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dx = -2; dx <= 2; dx++) {
                    int x = gx + dx, y = gy + dy, z = gz + dz;
                    if (x < 0 || y < 0 || z < 0 || x >= VoxelCompute.gridNx || y >= VoxelCompute.gridNy || z >= VoxelCompute.gridNz) continue;
                    int i = x + VoxelCompute.gridNx * (y + VoxelCompute.gridNy * z);
                    m.set(gx0 + x, gy0 + y, gz0 + z);
                    if (VoxelCompute.lightSnap != null && (VoxelCompute.lightSnap[i] & 255) != (skyL.getLightValue(m) << 4 | blkL.getLightValue(m))) return true;
                    if (VoxelCompute.gridSnap != null) {
                        BlockState now = level.getBlockState(m);
                        int local = now.isAir() ? 0 : localOf.getOrDefault(now, -1);
                        if (local != (VoxelCompute.gridSnap[i] & 0xFFFF)) return true;
                    }
                }
        return false;
    }

    private static boolean sameGeometry(int[][] a, int[][] b) {
        for (int v = 0; v < 4; v++)
            for (int fi = 0; fi < 5; fi++)
                if (a[v][fi] != b[v][fi]) return false;
        return true;
    }

    private static String quadStr(int[][] q) {
        StringBuilder sb = new StringBuilder("[");
        for (int v = 0; v < 4; v++)
            sb.append(String.format(Locale.ROOT, "(%.3f %.3f %.3f uv %.4f %.4f c %08x l %06x)", Float.intBitsToFloat(q[v][0]), Float.intBitsToFloat(q[v][1]),
                    Float.intBitsToFloat(q[v][2]), Float.intBitsToFloat(q[v][3]), Float.intBitsToFloat(q[v][4]), q[v][5], q[v][6]));
        return sb.append(']').toString();
    }

    private static String diffStr(int[][] a, int[][] b) {
        StringBuilder sb = new StringBuilder();
        for (int v = 0; v < 4; v++)
            for (int fi = 0; fi < 10; fi++)
                if (a[v][fi] != b[v][fi])
                    sb.append(String.format(Locale.ROOT, " v%d.%s vanilla %s gpu %s", v, FIELDS[fi],
                            fi == 5 || fi == 6 ? Integer.toHexString(a[v][fi]) : Float.toString(Float.intBitsToFloat(a[v][fi])),
                            fi == 5 || fi == 6 ? Integer.toHexString(b[v][fi]) : Float.toString(Float.intBitsToFloat(b[v][fi]))));
        return sb.toString();
    }
}
