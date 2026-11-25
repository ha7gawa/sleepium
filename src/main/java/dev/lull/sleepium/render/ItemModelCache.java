package dev.lull.sleepium.render;

import dev.lull.sleepium.Sleepium;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.SimpleBakedModel;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.BakedItemModel;

/**
 * -Dsleep.opt.ItemModelCache (default on): an item model drawn by RenderItem.renderModel is built once into a GL
 * buffer per (model, colour) (ModelVbo) and drawn from it afterwards. Vanilla rebuilds the same quads into a BufferBuilder
 * and sends them from client memory every frame (glDrawArrays) - for a held sword, an item in a frame, every armour
 * stand's hand item.
 *
 * <p>Same output: only models whose quads cannot change between calls (vanilla's SimpleBakedModel, Forge's
 * BakedItemModel - exact classes, their quads are fixed lists) and whose quads take no colour from the stack (no tint
 * index: the colour is the call's own) and no per-quad lighting (ITEM format, diffuse lighting on - Forge's
 * renderLitItem then draws one segment and touches no state). For those the vertices are a function of (model,
 * colour); the buffer holds the bytes vanilla's single draw would send, drawn with the same pointers (ModelVbo,
 * which also repeats the draw's tracked-state side effect, GlStateManager.resetColor). Texture, lightmap,
 * matrices and the glint's texture matrix stay the caller's, as in vanilla.
 *
 * <p>The buffers are dropped when models are baked again (a resource reload makes new model objects).
 */
public final class ItemModelCache {
    private ItemModelCache() {}

    public static final boolean ON = !"false".equals(System.getProperty("sleep.opt.ItemModelCache"));
    private static final Map<IBakedModel, Object[]> LISTS = new IdentityHashMap<>();   // model -> [colour, ModelVbo, colour, ModelVbo, ...]
    /** 0: not cacheable; 1: no tinted quad; 2: tinted quads (cacheable for a call whose colour does not come from the stack) */
    private static final Map<IBakedModel, Integer> ELIGIBLE = new IdentityHashMap<>();
    public static long hits, compiled, skipped;

    /** the buffer for this model and colour; NONE when it is not cacheable; null: build it (caller, then put) */
    public static final ModelVbo NONE = null;
    public static boolean cacheable;

    public static ModelVbo find(IBakedModel model, int color, net.minecraft.item.ItemStack stack, net.minecraft.client.renderer.color.ItemColors colors) {
        Integer kind = ELIGIBLE.get(model);
        if (kind == null) {
            kind = eligible(model);
            ELIGIBLE.put(model, kind);
        }
        // renderQuads asks the stack for a tinted quad's colour only when the call's colour is -1 and the stack is not
        // empty; with no colour handler for the item the answer is -1 (-1 | 0xFF000000), the call's own colour
        if (kind == 0 || kind == 2 && color == -1 && !stack.isEmpty()
                && (net.minecraft.client.renderer.EntityRenderer.anaglyphEnable
                    || ((dev.lull.sleepium.mixin.ItemColorsAccessor) colors).sleep$colorMap().containsKey(stack.getItem().delegate))) {
            skipped++;
            cacheable = false;
            return null;
        }
        cacheable = true;
        Object[] l = LISTS.get(model);
        if (l != null)
            for (int i = 0; i < l.length; i += 2)
                if ((Integer) l[i] == color) {
                    hits++;
                    return (ModelVbo) l[i + 1];
                }
        return null;
    }

    public static void put(IBakedModel model, int color, ModelVbo vbo) {
        Object[] l = LISTS.get(model);
        Object[] n = l == null ? new Object[2] : java.util.Arrays.copyOf(l, l.length + 2);
        n[n.length - 2] = color;
        n[n.length - 1] = vbo;
        LISTS.put(model, n);
        compiled++;
    }

    /** why models were left out, by class (the report) */
    private static final Map<String, String> WHY = new java.util.TreeMap<>();

    private static int eligible(IBakedModel model) {
        Class<?> c = model.getClass();
        String why = null;
        tinted = false;
        if (c != SimpleBakedModel.class && c != BakedItemModel.class) why = "class";
        else {
            for (EnumFacing f : EnumFacing.VALUES)
                if (why == null) why = plain(model.getQuads(null, f, 0L));
            if (why == null) why = plain(model.getQuads(null, null, 0L));
        }
        if (why != null) WHY.merge(c.getName(), why, (a, b) -> a.contains(b) ? a : a + "," + b);
        return why != null ? 0 : tinted ? 2 : 1;
    }

    private static boolean tinted;

    private static String plain(List<BakedQuad> quads) {
        if (quads == null) return null;
        for (BakedQuad q : quads) {
            if (q.hasTintIndex()) tinted = true;
            if (q.getFormat() != DefaultVertexFormats.ITEM && q.getFormat().hasUvOffset(1)) return "lightmap";   // Forge's per-quad lighting
            if (!q.shouldApplyDiffuseLighting()) return "no diffuse";
        }
        return null;
    }

    /** models baked again: the old ones are gone */
    public static void clear() {
        for (Object[] l : LISTS.values())
            for (int i = 1; i < l.length; i += 2) ((ModelVbo) l[i]).delete();
        int n = LISTS.size();
        LISTS.clear();
        ELIGIBLE.clear();
        if (n > 0) Sleepium.LOGGER.info("[sleep] item model cache cleared ({} models)", n);
    }

    public static String report() {
        return "item model cache: " + hits + " calls, " + compiled + " buffers, " + skipped + " not cacheable, " + LISTS.size() + " models; left out " + WHY;
    }
}
