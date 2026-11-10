package dev.lull.opt;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * exp/particle-gpu (client): ClientLevel's own copy of BlockAndTintGetter.getBrightness - while ParticleEngine.render
 * runs (ParticleLightMemo's window, the render thread) a position's brightness per light layer is read once; thousands
 * of particles in the same few blocks asked the light engine each, every frame. Any particle class that gets its light
 * through the level (LevelRenderer.getLightColor, level.getBrightness) is served; the level does not change during it.
 */
@Mixin(ClientLevel.class)
public abstract class ParticleLightMemoMixin implements BlockAndTintGetter {
    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        if (dev.lull.optsupport.ParticleLightMemo.open()) return dev.lull.optsupport.ParticleLightMemo.get((ClientLevel) (Object) this, layer, pos);
        return this.getLightEngine().getLayerListener(layer).getLightValue(pos);   // BlockAndTintGetter's default
    }
}
