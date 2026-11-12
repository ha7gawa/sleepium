package dev.lull.optsupport;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * ArmorResourceMemoMixin: Forge 1.20.1's HumanoidArmorLayer.getArmorResource formats the texture path with
 * String.format for every armour piece of every mob in every frame (client_entities: 355 zombies x 4 pieces, the render
 * workers' second-largest cost after the item quads' MemoryStack), then looks it up in a static HashMap the workers share
 * unlocked. The path depends on the item's material name, the slot (inner model or not) and the type (null / "overlay")
 * - and on the item's getArmorTexture, which may read the stack or the entity: items whose class overrides it are left
 * to Forge every time. Else the first answer is kept per (item, slot, type), the same ResourceLocation object.
 */
public final class ArmorMemo {
    private ArmorMemo() {}

    private static final ConcurrentHashMap<Item, ResourceLocation[]> MEMO = new ConcurrentHashMap<>();
    public static long hits, misses, notPlain;

    /** the item's class (below Item) declares getArmorTexture: its answer may depend on the stack or the entity */
    private static final ClassValue<Boolean> OVERRIDES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> k = type; k != null && k != Item.class; k = k.getSuperclass()) {
                try {
                    k.getDeclaredMethod("getArmorTexture", ItemStack.class, Entity.class, EquipmentSlot.class, String.class);
                    return true;
                } catch (NoSuchMethodException e) {
                    // not here
                } catch (Throwable t) {
                    return true;   // unreadable: as if it did
                }
            }
            return false;
        }
    };

    /** the slot of type (null 0, "overlay" 1) - another type: -1 (not kept) */
    private static int index(EquipmentSlot slot, String type) {
        int t = type == null ? 0 : "overlay".equals(type) ? 1 : -1;
        return t < 0 ? -1 : slot.ordinal() * 2 + t;
    }

    /** the kept answer, or null (then the caller asks Forge and offers the answer to keep) */
    public static ResourceLocation get(ItemStack stack, EquipmentSlot slot, String type) {
        Item item = stack.getItem();
        ResourceLocation[] a = MEMO.get(item);
        int i = index(slot, type);
        if (a == null || i < 0) return null;
        ResourceLocation r = a[i];
        if (r != null) hits++;
        return r;
    }

    public static void put(ItemStack stack, EquipmentSlot slot, String type, ResourceLocation r) {
        Item item = stack.getItem();
        int i = index(slot, type);
        misses++;
        if (i < 0 || r == null || !(item instanceof ArmorItem)) return;
        if (OVERRIDES.get(item.getClass())) {
            notPlain++;
            return;
        }
        MEMO.computeIfAbsent(item, k -> new ResourceLocation[EquipmentSlot.values().length * 2])[i] = r;
    }

    /** resources reloaded: forgotten (a resource pack's material names do not change, but nothing is assumed) */
    public static void clear() {
        MEMO.clear();
    }
}
