package dev.lull.opt;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** part of ServerParallelItems (1.20.1: an entity in a portal has no portalProcess object - its flag) */
@Mixin(Entity.class)
public interface ServerParallelItemsPortalAccessor {
    @Accessor("isInsidePortal")
    boolean sleep$insidePortal();
}
