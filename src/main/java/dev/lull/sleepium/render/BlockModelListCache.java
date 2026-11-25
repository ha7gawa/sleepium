package dev.lull.sleepium.render;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.SimpleBakedModel;

/**
 * -Dsleep.opt.BlockModelListCache (default on): BlockModelRenderer.renderModelBrightnessColor - a block model drawn
 * outside the chunk meshes (an item frame's frame, a map's frame, blocks carried or riding) - from a GL buffer
 * (ModelVbo) per (model, brightness, red, green, blue). Vanilla sends every quad as its own draw (begin, the quad, draw), dozens per
 * item frame per frame.
 *
 * <p>Same output: only SimpleBakedModel (its quads are fixed lists, the state is not asked); the vertices are the quads'
 * data with a colour and a normal computed from the four numbers and the quad's face - the same bytes, built by the same
 * BufferBuilder calls, the quads in the same order in one draw instead of one draw each. Dropped with the item buffers
 * when models are baked again.
 */
public final class BlockModelListCache {
    private BlockModelListCache() {}

    public static final boolean ON = !"false".equals(System.getProperty("sleep.opt.BlockModelListCache"));
    public static boolean cacheable;

    private static final class Entry {
        final float b, r, g, bl;
        final ModelVbo list;

        Entry(float b, float r, float g, float bl, ModelVbo list) {
            this.b = b;
            this.r = r;
            this.g = g;
            this.bl = bl;
            this.list = list;
        }
    }

    private static final Map<IBakedModel, List<Entry>> LISTS = new IdentityHashMap<>();
    public static long hits, compiled, skipped;
    private static final java.util.Set<String> CLASSES = new java.util.TreeSet<>();

    /** the buffer; null with cacheable false: not cacheable, null with cacheable true: build it (caller, then put) */
    /**
     * Forge's wrapper of a vanilla JSON model (ModelLoader.VanillaModelWrapper's anonymous PerspectiveMapWrapper): its
     * quads are the wrapped SimpleBakedModel's for any state but an extended one carrying an animation state
     */
    private static final String FORGE_VANILLA_WRAPPER = "net.minecraftforge.client.model.ModelLoader$VanillaModelWrapper$1";

    static boolean fixedQuads(IBakedModel model, Object state) {
        Class<?> c = model.getClass();
        if (c == SimpleBakedModel.class) return true;
        return c.getSuperclass() == net.minecraftforge.client.model.PerspectiveMapWrapper.class && c.getName().equals(FORGE_VANILLA_WRAPPER)
                && !(state instanceof net.minecraftforge.common.property.IExtendedBlockState);
    }

    public static ModelVbo find(IBakedModel model, Object state, float b, float r, float g, float bl) {
        cacheable = false;
        if (!fixedQuads(model, state)) {
            CLASSES.add(model.getClass().getName());
            skipped++;
            return null;
        }
        List<Entry> l = LISTS.get(model);
        if (l != null)
            for (int i = 0, n = l.size(); i < n; i++) {
                Entry e = l.get(i);
                if (Float.floatToRawIntBits(e.b) == Float.floatToRawIntBits(b) && Float.floatToRawIntBits(e.r) == Float.floatToRawIntBits(r)
                        && Float.floatToRawIntBits(e.g) == Float.floatToRawIntBits(g) && Float.floatToRawIntBits(e.bl) == Float.floatToRawIntBits(bl)) {
                    hits++;
                    cacheable = true;
                    return e.list;
                }
            }
        if (l != null && l.size() >= 64) {   // a model drawn with ever new colours (a fading effect): not worth buffers
            skipped++;
            return null;
        }
        cacheable = true;
        return null;
    }

    public static void put(IBakedModel model, float b, float r, float g, float bl, ModelVbo list) {
        LISTS.computeIfAbsent(model, k -> new ArrayList<>(2)).add(new Entry(b, r, g, bl, list));
        compiled++;
    }

    public static void clear() {
        for (List<Entry> l : LISTS.values())
            for (Entry e : l) e.list.delete();
        LISTS.clear();
    }

    public static String report() {
        return "block model buffers: " + hits + " calls, " + compiled + " buffers, " + skipped + " not cacheable " + CLASSES;
    }
}
