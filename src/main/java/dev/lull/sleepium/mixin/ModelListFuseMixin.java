package dev.lull.sleepium.mixin;

import java.util.List;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A model part's display list with all its faces in one draw (-Dsleep.opt.modelListFuse, default on): vanilla's
 * TexturedQuad.draw begins and draws every face on its own, so an armor stand in armour is ~240 draws inside its parts'
 * lists. Here the same vertices go out in the same order with the same normals (TexturedQuad.draw's arithmetic), as one
 * GL_QUADS draw - GL processes the primitives in order either way, so the picture is the same bit for bit. A box or
 * face of a mod's own class (it may draw differently) is drawn by its own code between the fused runs.
 */
@Mixin(ModelRenderer.class)
public abstract class ModelListFuseMixin {
    private static final boolean ON = !"false".equals(System.getProperty("sleep.opt.modelListFuse"));

    @Shadow private boolean compiled;
    @Shadow private int displayList;
    @Shadow public List<ModelBox> cubeList;

    @Inject(method = "compileDisplayList", at = @At("HEAD"), cancellable = true)
    private void sleep$fuse(float scale, CallbackInfo ci) {
        if (!ON) return;
        displayList = GLAllocation.generateDisplayLists(1);
        GlStateManager.glNewList(displayList, 4864);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        boolean open = false;
        for (int i = 0; i < cubeList.size(); ++i) {
            ModelBox box = cubeList.get(i);
            if (box.getClass() != ModelBox.class) {   // a mod's box: its own render (a draw of its own)
                if (open) t.draw();
                open = false;
                box.render(b, scale);

                continue;
            }
            for (TexturedQuad q : ((ModelBoxAccessor) box).sleep$quads()) {
                if (q.getClass() != TexturedQuad.class) {
                    if (open) t.draw();
                    open = false;
                    q.draw(b, scale);

                    continue;
                }
                if (!open) b.begin(7, DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL);
                open = true;
                emit(q, b, scale);

            }
        }
        if (open) t.draw();
        GlStateManager.glEndList();
        compiled = true;
        ci.cancel();
    }

    /** TexturedQuad.draw's vertices without its begin / draw: the same normal arithmetic, the same order */
    private static void emit(TexturedQuad q, BufferBuilder renderer, float scale) {
        PositionTextureVertex[] v = q.vertexPositions;
        Vec3d vec3d = v[1].vector3D.subtractReverse(v[0].vector3D);
        Vec3d vec3d1 = v[1].vector3D.subtractReverse(v[2].vector3D);
        Vec3d vec3d2 = vec3d1.crossProduct(vec3d).normalize();
        float f = (float) vec3d2.x;
        float f1 = (float) vec3d2.y;
        float f2 = (float) vec3d2.z;
        if (((TexturedQuadAccessor) q).sleep$inverted()) {
            f = -f;
            f1 = -f1;
            f2 = -f2;
        }
        for (int i = 0; i < 4; ++i) {
            PositionTextureVertex p = v[i];
            renderer.pos(p.vector3D.x * (double) scale, p.vector3D.y * (double) scale, p.vector3D.z * (double) scale).tex((double) p.texturePositionX, (double) p.texturePositionY).normal(f, f1, f2).endVertex();
        }
    }
}
