package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.BlockModelListCache;
import dev.lull.sleepium.render.ModelVbo;
import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3i;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BlockModelListCache: renderModelBrightnessColor(state, model, brightness, r, g, b) from a GL buffer. */
@Mixin(BlockModelRenderer.class)
public abstract class BlockModelListMixin {
    @Inject(method = "renderModelBrightnessColor(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/client/renderer/block/model/IBakedModel;FFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void sleep$cached(IBlockState state, IBakedModel model, float b, float r, float g, float bl, CallbackInfo ci) {
        if (!BlockModelListCache.ON) return;
        ModelVbo vbo = BlockModelListCache.find(model, state, b, r, g, bl);
        if (!BlockModelListCache.cacheable) return;
        if (vbo == null) {
            vbo = ModelVbo.build(GL11.GL_QUADS, DefaultVertexFormats.ITEM, buf -> {
                for (EnumFacing f : EnumFacing.values()) quads(buf, model.getQuads(state, f, 0L), b, r, g, bl);
                quads(buf, model.getQuads(state, null, 0L), b, r, g, bl);
            });
            BlockModelListCache.put(model, b, r, g, bl, vbo);
        }
        vbo.draw();
        ci.cancel();
    }

    /** renderModelBrightnessColorQuads' vertices, every quad into one buffer instead of a draw each */
    private static void quads(BufferBuilder buf, List<BakedQuad> list, float b, float r, float g, float bl) {
        for (int i = 0, n = list.size(); i < n; i++) {
            BakedQuad q = list.get(i);
            buf.addVertexData(q.getVertexData());
            if (q.hasTintIndex()) buf.putColorRGB_F4(r * b, g * b, bl * b);
            else buf.putColorRGB_F4(b, b, b);
            Vec3i d = q.getFace().getDirectionVec();
            buf.putNormal((float) d.getX(), (float) d.getY(), (float) d.getZ());
        }
    }
}
