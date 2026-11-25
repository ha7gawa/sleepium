package dev.lull.sleepium.mixin;

import java.util.Map;
import net.minecraft.client.renderer.color.IItemColor;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.item.Item;
import net.minecraftforge.registries.IRegistryDelegate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ItemModelCache: which items have a colour handler (their tinted quads take the stack's colour) */
@Mixin(ItemColors.class)
public interface ItemColorsAccessor {
    @Accessor("itemColorMap")
    Map<IRegistryDelegate<Item>, IItemColor> sleep$colorMap();
}
