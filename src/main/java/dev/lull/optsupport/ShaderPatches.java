package dev.lull.optsupport;

import com.mojang.blaze3d.shaders.Program;

/**
 * The engine's changes to vanilla shader sources (raw source, before #moj_import is expanded): chunk multi-draw, part
 * records, materials. Applied wherever a vanilla shader is compiled - vanilla's Program.compileShaderInternal
 * (ChunkShaderPatchMixin) and Veil's recompile of every vanilla shader (VeilShaderPatchMixin), so a program Veil swaps
 * in keeps them.
 */
public final class ShaderPatches {
    private ShaderPatches() {}

    public static String apply(Program.Type type, String name, String src) {
        if (type == Program.Type.VERTEX) {
            if ("rendertype_translucent".equals(name)) {
                // light volumes (LightVolumes.patchTranslucent) and the translucent multi-draw (it was never sent here before 11-03:
                // the layer kept baked light, and its sections re-meshed on every light change)
                if (Boolean.getBoolean("lab.opt.ChunkMultiDrawMixin") && ChunkMultiDraw.capable()) src = ChunkMultiDraw.patch(name, src);
            } else if (ChunkMultiDraw.SUPPORTED.contains(name)) {
                if (Boolean.getBoolean("lab.opt.ChunkMultiDrawMixin") && ChunkMultiDraw.capable()) src = ChunkMultiDraw.patch(name, src);
                // SuperByteBuffer parts (SbbPartsMixin) and baked-quad runs (QuadGpuMixin) draw into the opaque block types too:
                // their shaders take part records as well
                if ((Boolean.getBoolean("lab.opt.SbbPartsMixin") || Boolean.getBoolean("lab.opt.QuadGpuMixin")) && Boolean.getBoolean("lab.opt.PartBatchMixin")
                        && !"rendertype_translucent".equals(name)
                        && PartBatch.capable()) src = PartBatch.patch(name, src);
            } else if (Boolean.getBoolean("lab.opt.PartBatchMixin") && (name.startsWith("rendertype_entity") || name.startsWith("rendertype_armor")
                    || name.startsWith("rendertype_item_entity"))
                    && PartBatch.capable()) {
                src = PartBatch.patch(name, src);
                src = Materials.patchVertex(name, src);
            }
        } else if (type == Program.Type.FRAGMENT && Boolean.getBoolean("lab.opt.PartBatchMixin")) {
            src = Materials.patchFragment(name, src);
        }
        return src;
    }
}
