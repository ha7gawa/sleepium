package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.ItemModelCache;
import dev.lull.sleepium.render.ModelVbo;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ItemModelCache: renderModel(model, colour, stack) from a GL buffer when the model is cacheable. */
@Mixin(RenderItem.class)
public abstract class RenderItemModelMixin {
    @Shadow
    @Final
    private ItemColors itemColors;

    @Inject(method = "renderModel(Lnet/minecraft/client/renderer/block/model/IBakedModel;ILnet/minecraft/item/ItemStack;)V", at = @At("HEAD"), cancellable = true)
    private void sleep$cached(IBakedModel model, int color, ItemStack stack, CallbackInfo ci) {
        if (!ItemModelCache.ON) return;
        ModelVbo vbo = ItemModelCache.find(model, color, stack, itemColors);
        if (!ItemModelCache.cacheable) return;
        if (vbo == null) {
            RenderItem self = (RenderItem) (Object) this;
            // vanilla's single draw for such a model: one GL_QUADS / ITEM buffer, the quads by face then the general ones
            vbo = ModelVbo.build(GL11.GL_QUADS, DefaultVertexFormats.ITEM, b -> {
                for (EnumFacing f : EnumFacing.VALUES) self.renderQuads(b, model.getQuads(null, f, 0L), color, stack);
                self.renderQuads(b, model.getQuads(null, null, 0L), color, stack);
            });
            ItemModelCache.put(model, color, vbo);
        }
        vbo.draw();
        ci.cancel();
    }
}
