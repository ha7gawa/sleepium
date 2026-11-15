package dev.lull.optsupport;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.system.MemoryUtil;

/**
 * exp/item-gpu (client): item models in the world (dropped items, items in frames, in hands of mobs) drawn from VRAM -
 * PartBatch's geometry arena and multi-draw - instead of their quads written vertex by vertex every frame and, in the
 * sorted item sheet, sorted with every other quad of it.
 *
 * <p>ItemRenderer.renderModelLists (ItemGpuMixin) becomes one record {pose, normal matrix, light, overlay} per model and
 * render type when the model's geometry for this stack is known: its quads (the seven lists vanilla asks for, the same
 * list objects), each tinted quad's colour (ItemColors, per tint index) - built once on the render thread from the
 * quads with vanilla's own arithmetic (putBulkData: the colour bytes, the baked light, the baked or face normal as
 * NeoForge carries it from vertex to vertex). The shader (mode 7) computes position and normal from the record: the GPU's
 * float rounding, the render cache's allowance.
 *
 * <p>Order: a record's type is drawn with vanilla's batch of it, the records first. A type whose draws commute (opaque,
 * LEQUAL, writing depth: EntityBatch.commutes) takes any model. A sorted translucent type takes a model only when its
 * fragments do not depend on order: every texel of its sprites fully opaque (alpha 255) or below the shaders' discard
 * (alpha <= 24), every vertex alpha 255, no animated sprite - such a fragment replaces the pixel exactly where it is
 * nearest and is discarded or hidden where not, whatever was drawn before; drawn ahead of the sorted quads, these meet
 * them only through depth. (Vanilla sorts per quad by its centre: where a translucent quad sorts behind such an item's
 * quad but covers it, vanilla loses the item's pixel and this keeps it.)
 *
 * <p>Inside a ParallelRender job the record goes into the job (merged in order on the render thread); a geometry or
 * type not yet known is prepared on the render thread through the job's journal and the model drawn the CPU way this
 * once. -Dlab.itemgpu.contexts: display contexts taken (default ground, fixed, third person, head, none).
 */
public final class ItemGpu {
    public static long records, built, refused, orderBound;
    /** test switch (ClientAuto's ab): false - every model drawn the vanilla way from the next frame on */
    public static volatile boolean enabled = true;

    private ItemGpu() {}

    private static final java.util.EnumSet<ItemDisplayContext> CONTEXTS = contexts();

    private static java.util.EnumSet<ItemDisplayContext> contexts() {
        java.util.EnumSet<ItemDisplayContext> s = java.util.EnumSet.noneOf(ItemDisplayContext.class);
        String p = System.getProperty("lab.itemgpu.contexts", "ground,fixed,third_person_left_hand,third_person_right_hand,head,none");
        for (String n : p.split(","))
            for (ItemDisplayContext c : ItemDisplayContext.values())
                if (c.getSerializedName().equals(n.trim())) s.add(c);
        return s;
    }

    // ------------------------------------------------------------------ geometry per model and stack

    private static final Direction[] DIRS = Direction.values();

    /** One model's geometry for one set of quad lists and tints. */
    static final class Geo {
        final List<?>[] lists;
        final int[] tintIdx, tints;
        final int first, quads;
        final boolean orderFree;

        Geo(List<?>[] lists, int[] tintIdx, int[] tints, int first, int quads, boolean orderFree) {
            this.lists = lists;
            this.tintIdx = tintIdx;
            this.tints = tints;
            this.first = first;
            this.quads = quads;
            this.orderFree = orderFree;
        }
    }

    private static final Geo[] NONE = new Geo[0];
    /** render thread: model -> its geometries; workers read the published copy */
    private static final Map<BakedModel, Geo[]> GEOS = new IdentityHashMap<>();
    private static volatile Map<BakedModel, Geo[]> geoSnapshot = new IdentityHashMap<>();
    private static final java.util.Set<Object> ASKED = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** a model asked for more geometries than this (quads made anew each time): drawn the vanilla way */
    private static final int MAX_VARIANTS = 16;

    /** per thread: the quad lists of the current call and a random source seeded as vanilla does */
    private static final class Scratch {
        final List<?>[] lists = new List<?>[7];
        final RandomSource random = RandomSource.create();
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** the model's quad lists as renderModelLists asks for them (seed 42 before each) */
    private static List<?>[] listsOf(BakedModel model, Scratch s) {
        for (int d = 0; d < 6; d++) {
            s.random.setSeed(42L);
            s.lists[d] = model.getQuads(null, DIRS[d], s.random);
        }
        s.random.setSeed(42L);
        s.lists[6] = model.getQuads(null, null, s.random);
        return s.lists;
    }

    private static Geo find(Geo[] gs, List<?>[] lists, ItemStack stack, ItemColors colors) {
        next:
        for (Geo g : gs) {
            for (int i = 0; i < 7; i++) if (g.lists[i] != lists[i]) continue next;
            for (int k = 0; k < g.tintIdx.length; k++) if (colors.getColor(stack, g.tintIdx[k]) != g.tints[k]) continue next;
            return g;
        }
        return null;
    }

    // ------------------------------------------------------------------ capture

    private static ItemColors colors;

    /**
     * From ItemRenderer.render in place of renderModelLists: true if the model was queued as a record (nothing written).
     */
    public static boolean capture(BakedModel model, ItemStack stack, int light, int overlay, PoseStack poseStack, VertexConsumer vc,
                                  RenderType type, ItemDisplayContext ctx, MultiBufferSource buffers) {
        if (!enabled || !CONTEXTS.contains(ctx) || stack.isEmpty()) return false;
        if (type.format() != DefaultVertexFormat.NEW_ENTITY || type.mode() != VertexFormat.Mode.QUADS) return false;
        ItemColors ic = colors;
        if (ic == null) colors = ic = Minecraft.getInstance().getItemColors();
        PartBatch.JobParts jp;
        if (vc instanceof RecordingBuilder rb) {
            // a ParallelRender job: the record into the job, merged on the render thread (PartBatch.mergeJob); another
            // recorder (the render cache's) keeps the bytes
            if (rb.type != type || (jp = ParallelRender.jobParts()) == null || !PartBatch.itemTypeKnown(type)) return false;
            Scratch s = SCRATCH.get();
            List<?>[] lists = listsOf(model, s);
            Geo[] gs = geoSnapshot.get(model);
            Geo g = gs == null ? null : find(gs, lists, stack, ic);
            if (g == null) {
                ask(model, lists, stack, ic);
                return false;
            }
            if (g.quads == 0 || !g.orderFree && !EntityBatch.commutes(type)) return false;
            PartBatch.itemInJob(jp, type, g.first, g.quads, poseStack.last(), light, overlay);
            records++;
            return true;
        }
        if (!(vc instanceof BufferBuilder bb) || !RenderSystem.isOnRenderThread() || !PartBatch.itemTarget(type, bb, buffers)) return false;
        Scratch s = SCRATCH.get();
        List<?>[] lists = listsOf(model, s);
        Geo[] gs = GEOS.get(model);
        Geo g = gs == null ? null : find(gs, lists, stack, ic);
        if (g == null) {
            if (gs != null && gs.length >= MAX_VARIANTS) return false;
            g = build(model, lists, stack, ic);
            if (g == null) return false;
        }
        if (g.quads == 0 || !g.orderFree && !EntityBatch.commutes(type)) return false;
        PartBatch.queueItem(type, bb, g.first, g.quads, poseStack.last(), light, overlay);
        records++;
        return true;
    }

    /** a worker's miss: the geometry built on the render thread (job journal), published for the next frame */
    private static void ask(BakedModel model, List<?>[] lists, ItemStack stack, ItemColors ic) {
        if (!ASKED.add(model)) return;
        List<?>[] copy = lists.clone();
        ItemStack st = stack.copy();
        ParallelRender.prepare(() -> {
            ASKED.remove(model);
            Geo[] gs = GEOS.get(model);
            if (gs != null && (gs.length >= MAX_VARIANTS || find(gs, copy, st, ic) != null)) return;
            build(model, copy, st, ic);
        });
    }

    private static BakedModel missing;

    /** Render thread, at a frame's end: requests of jobs that failed were dropped - asked again; models reloaded. */
    public static void endFrame() {
        ASKED.clear();
        BakedModel m = Minecraft.getInstance().getModelManager().getMissingModel();
        if (m != missing) {   // a new model set (resource reload): the old models and sprites are gone
            missing = m;
            reload();
        }
    }

    // ------------------------------------------------------------------ build (render thread)

    private static ByteBuffer scratch = MemoryUtil.memAlloc(1 << 16);

    /** The model's geometry for these lists and this stack's tints, uploaded and published; null if it cannot be. */
    private static Geo build(BakedModel model, List<?>[] lists, ItemStack stack, ItemColors ic) {
        int n = 0;
        int[] tintIdx = new int[0], tints = new int[0];
        for (List<?> l : lists) {
            if (l == null) return null;
            for (Object o : l) {
                if (!(o instanceof BakedQuad q) || q.getVertices().length != 32) return null;   // whole quads of the BLOCK format only
                n += 4;
                if (q.isTinted() && indexOf(tintIdx, q.getTintIndex()) < 0) {
                    tintIdx = java.util.Arrays.copyOf(tintIdx, tintIdx.length + 1);
                    tints = java.util.Arrays.copyOf(tints, tints.length + 1);
                    tintIdx[tintIdx.length - 1] = q.getTintIndex();
                    tints[tints.length - 1] = ic.getColor(stack, q.getTintIndex());
                }
            }
        }
        if (scratch.capacity() < n * PartBatch.STRIDE) {
            MemoryUtil.memFree(scratch);
            scratch = MemoryUtil.memAlloc(Math.max(n * PartBatch.STRIDE, scratch.capacity() * 2));
        }
        long base = MemoryUtil.memAddress(scratch);
        boolean orderFree = true;
        int v = 0;
        for (List<?> l : lists) {
            for (Object o : l) {
                BakedQuad q = (BakedQuad) o;
                int i = -1;
                if (q.isTinted()) i = tints[indexOf(tintIdx, q.getTintIndex())];
                // renderQuadList: the tint as floats; putBulkData (readExistingColor): byte * brightness 1 * tint, truncated
                float f = (float) (i >>> 24) / 255.0F, r = (float) (i >> 16 & 255) / 255.0F, gr = (float) (i >> 8 & 255) / 255.0F, b = (float) (i & 255) / 255.0F;
                int[] d = q.getVertices();
                var dn = q.getDirection().getNormal();
                byte cx = (byte) (dn.getX() * 127), cy = (byte) (dn.getY() * 127), cz = (byte) (dn.getZ() * 127);
                boolean carried = false;   // NeoForge's applyBakedNormals keeps the last baked normal for the quad's next vertices
                if (!orderFree || !spritesOrderFree(q)) orderFree = false;
                for (int k = 0; k < 4; k++) {
                    long at = base + (long) v * PartBatch.STRIDE;
                    int o8 = k * 8;
                    MemoryUtil.memPutFloat(at, Float.intBitsToFloat(d[o8]));
                    MemoryUtil.memPutFloat(at + 4, Float.intBitsToFloat(d[o8 + 1]));
                    MemoryUtil.memPutFloat(at + 8, Float.intBitsToFloat(d[o8 + 2]));
                    int c = d[o8 + 3];   // bytes R, G, B, A
                    float f6 = (float) (c & 255), f7 = (float) (c >> 8 & 255), f8 = (float) (c >> 16 & 255);
                    int cr = (int) (f6 * 1.0F * r), cg = (int) (f7 * 1.0F * gr), cb = (int) (f8 * 1.0F * b);
                    int ca = (int) ((f * (float) (c >>> 24) / 255.0F) * 255);
                    if (ca != 255) orderFree = false;
                    MemoryUtil.memPutFloat(at + 12, Float.intBitsToFloat(d[o8 + 4]));
                    MemoryUtil.memPutFloat(at + 16, Float.intBitsToFloat(d[o8 + 5]));
                    int nrm = d[o8 + 7];
                    byte nx = (byte) nrm, ny = (byte) (nrm >> 8), nz = (byte) (nrm >> 16);
                    if (nx != 0 || ny != 0 || nz != 0) {
                        cx = nx;
                        cy = ny;
                        cz = nz;
                        carried = true;
                    }
                    MemoryUtil.memPutByte(at + 20, cx);
                    MemoryUtil.memPutByte(at + 21, cy);
                    MemoryUtil.memPutByte(at + 22, cz);
                    MemoryUtil.memPutByte(at + 23, (byte) (carried ? 16 : 0));   // 16: a baked normal, never normalised
                    MemoryUtil.memPutByte(at + 24, (byte) (cr & 255));
                    MemoryUtil.memPutByte(at + 25, (byte) (cg & 255));
                    MemoryUtil.memPutByte(at + 26, (byte) (cb & 255));
                    MemoryUtil.memPutByte(at + 27, (byte) (ca & 255));
                    int uv2 = d[o8 + 6];   // the quad's baked light (max with the record's on the GPU)
                    MemoryUtil.memPutShort(at + 28, (short) (uv2 & 0xFFFF));
                    MemoryUtil.memPutShort(at + 30, (short) (uv2 >>> 16));
                    v++;
                }
            }
        }
        int[] at = n == 0 ? new int[] {0, 0} : PartBatch.uploadItem(scratch, n);
        Geo g = new Geo(lists.clone(), tintIdx, tints, at[0], at[1], orderFree);
        Geo[] gs = GEOS.getOrDefault(model, NONE);
        gs = java.util.Arrays.copyOf(gs, gs.length + 1);
        gs[gs.length - 1] = g;
        GEOS.put(model, gs);
        Map<BakedModel, Geo[]> m = new IdentityHashMap<>(geoSnapshot);
        m.put(model, gs);
        geoSnapshot = m;
        built++;
        if (!orderFree) orderBound++;
        return g;
    }

    private static int indexOf(int[] a, int x) {
        for (int i = 0; i < a.length; i++) if (a[i] == x) return i;
        return -1;
    }

    /** per sprite: every texel's alpha 255 or <= 24, one frame */
    private static final Map<SpriteContents, Boolean> SPRITES = new IdentityHashMap<>();

    private static boolean spritesOrderFree(BakedQuad q) {
        SpriteContents sc = q.getSprite().contents();
        Boolean ok = SPRITES.get(sc);
        if (ok == null) {
            ok = false;
            try {
                NativeImage img = ((dev.lull.opt.SpriteImageAccessor) sc).sleep$image();
                if (sc.getUniqueFrames().count() <= 1 && img != null && img.format() == NativeImage.Format.RGBA) {
                    ok = true;
                    for (int y = 0; y < img.getHeight() && ok; y++)
                        for (int x = 0; x < img.getWidth(); x++) {
                            int a = img.getPixelRGBA(x, y) >>> 24;
                            if (a != 255 && a > 24) {
                                ok = false;
                                break;
                            }
                        }
                }
            } catch (RuntimeException e) {
                ok = false;
            }
            SPRITES.put(sc, ok);
        }
        return ok;
    }

    /** Resources reloaded: the models and sprites are new objects. */
    public static void reload() {
        GEOS.clear();
        geoSnapshot = new IdentityHashMap<>();
        SPRITES.clear();
    }

    /** a sorted type an order-free model may be drawn ahead of: translucent blending, LEQUAL, writing depth */
    static boolean sortedOk(RenderType t) {
        if (!t.sortOnUpload() || !(t instanceof dev.lull.opt.CompositeRenderTypeAccessor a)) return false;
        dev.lull.opt.CompositeStateAccessor s = (dev.lull.opt.CompositeStateAccessor) (Object) a.sleep$state();
        return s.sleep$transparency() == RenderStateShard.TRANSLUCENT_TRANSPARENCY
                && s.sleep$depthTest() == RenderStateShard.LEQUAL_DEPTH_TEST
                && s.sleep$writeMask() == RenderStateShard.COLOR_DEPTH_WRITE
                && s.sleep$colorLogic() == RenderStateShard.NO_COLOR_LOGIC
                && !t.isOutline();
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "item gpu: records %d, geometries %d (%d bound to order), refused at merge %d", records, built, orderBound, refused);
    }
}
