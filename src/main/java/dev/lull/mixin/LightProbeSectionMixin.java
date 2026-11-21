package dev.lull.mixin;

import dev.lull.LightProbe;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only (-Dlab.light.probe, notes/84): a block-light section created next to a section that already holds
 * block light. A new block-light section starts dark and vanilla never pulls its neighbours' light into it, so light
 * propagated before the section existed stays outside (below the from-scratch light) unless a later change re-lights
 * the spot - the hazard behind the 11-21 ngd3 fixcheck difference. Counted per run: how often the task order lets it
 * happen (base vs LightDirect FIFO vs LightDirect with vanilla's priority levels).
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LightProbeSectionMixin {
    @Shadow @Final protected DataLayerStorageMap<?> updatingSectionData;
    @Shadow @Final protected LongSet toRemove;

    @org.spongepowered.asm.mixin.Shadow protected abstract int getStoredLevel(long levelPos);

    @Inject(method = "setStoredLevel", at = @At("HEAD"))
    private void sleep$write(long pos, int level, CallbackInfo ci) {
        if (dev.lull.LightHistory.ON && (Object) this instanceof BlockLightSectionStorage) dev.lull.LightHistory.rec(dev.lull.LightHistory.WRITE, pos, level, getStoredLevel(pos), 0);
    }

    @Inject(method = "removeSection", at = @At("HEAD"))
    private void sleep$removed(long sectionPos, CallbackInfo ci) {
        if (dev.lull.LightHistory.ON && (Object) this instanceof BlockLightSectionStorage) dev.lull.LightHistory.rec(dev.lull.LightHistory.SEC_RM, sectionPos, 0, 0, 0);
    }

    @Inject(method = "updateSectionStatus", at = @At("HEAD"))
    private void sleep$statusRun(long sectionPos, boolean isEmpty, CallbackInfo ci) {
        if ((Object) this instanceof BlockLightSectionStorage && dev.lull.LightTrace.inSection(sectionPos))
            dev.lull.LightTrace.log("updateSectionStatus run " + SectionPos.of(sectionPos) + " empty " + isEmpty);
    }

    @Inject(method = "setLightEnabled", at = @At("HEAD"))
    private void sleep$enabledRun(long sectionPos, boolean on, CallbackInfo ci) {
        if (dev.lull.LightHistory.ON && (Object) this instanceof BlockLightSectionStorage) dev.lull.LightHistory.rec(dev.lull.LightHistory.ENABLE, sectionPos, on ? 1 : 0, 0, 0);
        if ((Object) this instanceof BlockLightSectionStorage && dev.lull.LightTrace.inChunk(SectionPos.x(sectionPos), SectionPos.z(sectionPos)))
            dev.lull.LightTrace.log("setLightEnabled run " + SectionPos.x(sectionPos) + "," + SectionPos.z(sectionPos) + " " + on);
    }

    @Inject(method = "initializeSection", at = @At("HEAD"))
    private void sleep$born(long sectionPos, CallbackInfo ci) {
        if (!((Object) this instanceof BlockLightSectionStorage) || toRemove.contains(sectionPos)) return;
        if (dev.lull.LightHistory.ON) dev.lull.LightHistory.rec(dev.lull.LightHistory.SEC_ADD, sectionPos, 0, 0, 0);
        LightProbe.sectionBorn();
        for (int d = 0; d < 6; d++) {
            long n = SectionPos.offset(sectionPos, d == 0 ? -1 : d == 1 ? 1 : 0, d == 2 ? -1 : d == 3 ? 1 : 0, d == 4 ? -1 : d == 5 ? 1 : 0);
            DataLayer l = updatingSectionData.getLayer(n);
            if (l != null && !l.isDefinitelyFilledWith(0)) {
                LightProbe.sectionBornNextToLight();
                return;
            }
        }
    }
}
