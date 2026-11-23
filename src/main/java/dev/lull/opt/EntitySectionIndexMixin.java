package dev.lull.opt;

import dev.lull.optsupport.IndexedSection;
import dev.lull.optsupport.SectionIndex;
import java.util.IdentityHashMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * exp/section-index: exact spatial indices for dense entity sections (see SectionIndex): one over the section's
 * full list (untyped queries) and one per typed list (getEntitiesOfClass). This mixin stores the section key and the
 * indices, and marks them dirty on add/remove (list positions shift).
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionIndexMixin<T extends EntityAccess> implements IndexedSection, dev.lull.optsupport.FrozenGrid.Holder {
    @Unique private long sleep$key = Long.MIN_VALUE;
    @Unique private volatile Object sleep$frozen;

    @Override public Object sleep$frozen() { return sleep$frozen; }
    @Override public void sleep$setFrozen(Object g) { sleep$frozen = g; }
    @Unique private SectionIndex sleep$index;
    @Unique private IdentityHashMap<Class<?>, SectionIndex> sleep$typed;

    @Override public long sleep$key() { return sleep$key; }
    @Override public void sleep$setKey(long key) { sleep$key = key; }
    @Override public SectionIndex sleep$index() { return sleep$index; }
    @Override public void sleep$setIndex(SectionIndex index) { sleep$index = index; }

    @Override
    public SectionIndex sleep$typedIndex(Class<?> baseClass) {
        return sleep$typed == null ? null : sleep$typed.get(baseClass);
    }

    @Override
    public void sleep$putTypedIndex(Class<?> baseClass, SectionIndex index) {
        if (sleep$typed == null) sleep$typed = new IdentityHashMap<>(4);
        sleep$typed.put(baseClass, index);
    }

    @Override
    public void sleep$forEachTyped(Object entity, net.minecraft.world.phys.AABB bb) {
        if (sleep$typed == null) return;
        Thread t = Thread.currentThread();
        for (SectionIndex i : sleep$typed.values()) if (i.owner == t) i.update(entity, bb);
    }

    @Override
    public void sleep$markAllDirty() {
        sleep$dirty();
    }

    @Unique
    private void sleep$dirty() {
        if (sleep$index != null) sleep$index.markDirty();
        if (sleep$typed != null) for (SectionIndex i : sleep$typed.values()) i.markDirty();
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void sleep$added(T entity, CallbackInfo ci) {
        sleep$dirty();
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void sleep$removed(T entity, CallbackInfoReturnable<Boolean> cir) {
        sleep$dirty();
    }
}
