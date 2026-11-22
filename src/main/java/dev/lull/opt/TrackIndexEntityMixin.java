package dev.lull.opt;

import dev.lull.optsupport.TrackIndex;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** lab.opt.TrackIndex: an entity whose position the grid holds marks it stale when it moves (any position change). */
@Mixin(Entity.class)
public abstract class TrackIndexEntityMixin implements TrackIndex.Indexed {
    @Unique private boolean sleep$indexed;

    @Override
    public void sleep$setIndexed(boolean on) {
        this.sleep$indexed = on;
    }

    @Inject(method = "setPosRaw", at = @At("TAIL"))
    private void sleep$moved(double x, double y, double z, CallbackInfo ci) {
        if (this.sleep$indexed) TrackIndex.stale = true;
    }
}
