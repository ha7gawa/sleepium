package dev.lull.opt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * An item with a block-entity-style model (shield, chest, head, banner, trident...) is drawn by the BEWLR that the
 * loader's IClientItemExtensions.getCustomRenderer hands out, and its default is the game's own one
 * (Minecraft.getItemRenderer().getBlockEntityRenderer()) whatever ItemRenderer draws. A ParallelRender renderer set's
 * ItemRenderer on a worker therefore drew such items (in item frames, dropped) with the render thread's BEWLR and model
 * parts at the same time as the render thread: the parts' iterators and poses raced (item frames failed on workers,
 * 11-19). When the default comes back and this ItemRenderer is not the game's, its own BEWLR is used instead (a mod's own
 * custom renderer is left as it is).
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererSetBewlrMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/client/extensions/common/IClientItemExtensions;getCustomRenderer()Lnet/minecraft/client/renderer/BlockEntityWithoutLevelRenderer;"))
    private BlockEntityWithoutLevelRenderer sleep$setBewlr(net.minecraftforge.client.extensions.common.IClientItemExtensions ext) {
        BlockEntityWithoutLevelRenderer r = ext.getCustomRenderer();
        ItemRenderer self = (ItemRenderer) (Object) this, game = Minecraft.getInstance().getItemRenderer();
        return self != game && r == game.getBlockEntityRenderer() ? self.getBlockEntityRenderer() : r;
    }
}
