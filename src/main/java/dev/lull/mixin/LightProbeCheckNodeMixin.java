package dev.lull.mixin;

import dev.lull.LightProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only (-Dlab.light.probe, notes/84): a block-light check of a light source that vanilla's checkNode will not
 * light - its section not stored, or light off in its column (getEmission then says 0). The flowing lava left dark in
 * LightDirect's N100 warm-ups (11-21) is such a source; counted and the first ones logged, in every variant.
 */
@Mixin(BlockLightEngine.class)
public abstract class LightProbeCheckNodeMixin {
    @Inject(method = "checkNode", at = @At("HEAD"))
    private void sleep$check(long packedPos, CallbackInfo ci) {
        LightProbeEngineAccessor self = (LightProbeEngineAccessor) (Object) this;
        LightProbeStorageInvoker st = (LightProbeStorageInvoker) self.sleep$storage();
        long sec = SectionPos.blockToSection(packedPos);
        boolean storing = st.sleep$storing(sec), on = st.sleep$lightOn(sec);
        BlockPos p = BlockPos.of(packedPos);
        if (dev.lull.LightTrace.in(p)) {
            LightChunk tc = self.sleep$chunks().getChunkForLighting(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
            dev.lull.LightTrace.log("checkNode " + p.toShortString() + " state " + (tc == null ? "no chunk" : tc.getBlockState(p)) + " stored " + storing + " lightOn " + on
                    + " level " + (storing ? st.sleep$level(packedPos) : -1));
        }
        if (storing && on) return;
        LightChunk c = self.sleep$chunks().getChunkForLighting(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
        if (c == null) return;
        BlockState s = c.getBlockState(p);
        if (s.getLightEmission() > 0) LightProbe.unlitSource(p, s, storing, on, c);
    }
}
