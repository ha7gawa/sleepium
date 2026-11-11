package dev.lull.optsupport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;

/**
 * Copies of the opaque block programs for PartBatch's records only (QuadGpu's baked-quad runs into block types). The
 * block programs are the terrain's: patched for the records, every terrain vertex ran PartBatch's extra code - its
 * storage block, mode branches, the extra attribute - and on a GPU-bound N100 the terrain view lost ~5 % (82 -> 77 fps,
 * 11-11) with nothing drawn from VRAM there. Now the terrain keeps its program; a record draw into a block type binds
 * the copy, with the type's uniforms and textures, and puts the type's program back for the batch.
 * <p>
 * A copy is the same shader as the resource manager has it (a resource pack's too): its json read with the vertex
 * program renamed "sleepium_parts_<name>" (Program caches programs by name: the copy's own, patched in ShaderPatches),
 * the fragment program shared. Built on first use on the render thread; rebuilt when the original's program changes (a
 * resource reload makes new instances).
 */
public final class PartsPrograms {
    private PartsPrograms() {}

    public static final String PREFIX = "sleepium_parts_";

    private static final class Copy {
        int originalId;
        ShaderInstance shader;   // null: could not be built (logged once)
    }

    private static final Map<String, Copy> COPIES = new HashMap<>();
    public static long built, failed;

    /** the copy for this original program, built if needed; null when it cannot be */
    public static ShaderInstance copyOf(ShaderInstance original) {
        Copy c = COPIES.computeIfAbsent(original.getName(), k -> new Copy());
        if (c.originalId == original.getId()) return c.shader;   // built for this program (or failed: not again each frame)
        if (c.shader != null) c.shader.close();
        c.shader = null;
        c.originalId = original.getId();
        try {
            c.shader = new ShaderInstance(provider(Minecraft.getInstance().getResourceManager(), original.getName()), PREFIX + original.getName(), original.getVertexFormat());
            built++;
        } catch (Throwable t) {
            failed++;
            System.out.println("[sleep/opt] part batch: no record program for " + original.getName() + ": " + t);
        }
        return c.shader;
    }

    /** the name a copy's json is asked for -> the original's, with its vertex program renamed */
    private static ResourceProvider provider(ResourceProvider base, String name) {
        return loc -> {
            String p = loc.getPath();
            if (p.startsWith("shaders/core/" + PREFIX)) {
                String rest = p.substring(("shaders/core/" + PREFIX).length());
                ResourceLocation orig = ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), "shaders/core/" + rest);
                Optional<Resource> r = base.getResource(orig);
                if (r.isEmpty() || !rest.endsWith(".json")) return r;   // the vertex source: the original's file
                Resource res = r.get();
                try (InputStreamReader in = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                    JsonObject j = JsonParser.parseReader(in).getAsJsonObject();
                    String v = j.get("vertex").getAsString();
                    int colon = v.indexOf(':');
                    j.addProperty("vertex", colon < 0 ? PREFIX + v : v.substring(0, colon + 1) + PREFIX + v.substring(colon + 1));
                    byte[] bytes = j.toString().getBytes(StandardCharsets.UTF_8);
                    return Optional.of(new Resource(res.source(), () -> new ByteArrayInputStream(bytes)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return base.getResource(loc);
        };
    }

    /**
     * Render thread, right before the type's batch (its program bound, uniforms set): the copy bound with the same
     * uniforms and textures. Returns the copy, or null (then nothing is drawn).
     */
    public static ShaderInstance bind(ShaderInstance original, Matrix4f modelView, Matrix4f projection) {
        ShaderInstance c = copyOf(original);
        if (c == null) return null;
        for (int i = 0; i < 12; i++) c.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        c.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        if (c.CHUNK_OFFSET != null) c.CHUNK_OFFSET.set(0.0F, 0.0F, 0.0F);   // not a section's draw
        c.apply();
        return c;
    }

    /** after the records: the type's own program back for its batch */
    public static void unbind(ShaderInstance copy, ShaderInstance original) {
        copy.clear();
        original.apply();
    }

    public static String report() {
        return "record programs built " + built + (failed > 0 ? ", failed " + failed : "");
    }
}
