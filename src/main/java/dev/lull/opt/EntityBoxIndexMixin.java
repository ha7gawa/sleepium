package dev.lull.opt;

import dev.lull.optsupport.IndexedSection;
import dev.lull.optsupport.SectionIndex;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entity bounding boxes change only through setBoundingBox: keep the section index of the entity in sync. */
@Mixin(Entity.class)
public abstract class EntityBoxIndexMixin {
    @Shadow private EntityInLevelCallback levelCallback;

    @Inject(method = "setBoundingBox", at = @At("RETURN"))
    private void sleep$box(AABB bb, CallbackInfo ci) {
        if (levelCallback instanceof EntityCallbackAccessor cb) {
            EntitySection<?> s = cb.sleep$section();
            if (s != null) {
                IndexedSection is = (IndexedSection) s;
                if (dev.lull.optsupport.SpecTicks.running()) {   // (a parallel run: rebuilt at the next query - a box may also be undone)
                    is.sleep$markAllDirty();
                    return;
                }
                SectionIndex idx = is.sleep$index();
                if (idx != null && idx.owner == Thread.currentThread()) idx.update(this, bb);
                is.sleep$forEachTyped(this, bb);
            }
        }
    }
}
