package dev.lull.opt;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** TrackFast's view of the field vanilla's passenger stream walks (part of lab.opt.TrackFast) */
@Mixin(Entity.class)
public interface TrackFastEntityAccessor {
    @Accessor("passengers")
    ImmutableList<Entity> sleep$passengers();
}
