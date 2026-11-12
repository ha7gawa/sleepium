package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.ArmorMemo;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Forge's getArmorResource kept per (item, slot, type) for items that do not override getArmorTexture (ArmorMemo): no
 * String.format and no unlocked shared HashMap per armour piece per frame. The same ResourceLocation as Forge's.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class ArmorResourceMemoMixin {
    @WrapMethod(method = "getArmorResource", remap = false)
    private ResourceLocation sleep$memo(Entity entity, ItemStack stack, EquipmentSlot slot, String type, Operation<ResourceLocation> original) {
        ResourceLocation r = ArmorMemo.get(stack, slot, type);
        if (r != null) return r;
        r = original.call(entity, stack, slot, type);
        ArmorMemo.put(stack, slot, type, r);
        return r;
    }
}
