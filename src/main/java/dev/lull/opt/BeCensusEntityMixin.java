package dev.lull.opt;

import dev.lull.optsupport.BeCensus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BeCensus (measuring): an entity made inside a block-entity tick (its id comes from the global counter). */
@Mixin(Entity.class)
public abstract class BeCensusEntityMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void sleep$made(EntityType<?> type, Level level, CallbackInfo ci) {
        BeCensus.mark(BeCensus.ENTITY);
    }
}
