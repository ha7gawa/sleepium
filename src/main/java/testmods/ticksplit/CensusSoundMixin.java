package testmods.ticksplit;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import testmods.meter.EntityCensus;

/** Test only (-Dsleep.meter.tickSplit=true): sounds played, for EntityCensus. */
@Mixin(SoundManager.class)
public abstract class CensusSoundMixin {
    @Inject(method = "play", at = @At("HEAD"))
    private void sleep$sound(SoundInstance s, CallbackInfo ci) {
        EntityCensus.sounds++;
    }
}
