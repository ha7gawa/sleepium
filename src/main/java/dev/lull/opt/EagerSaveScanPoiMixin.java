package dev.lull.opt;

import dev.lull.optsupport.EagerSaveScan;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** EagerSaveScan: a POI section that gets dirty takes QUIET from its chunk's holder; the dirty set read for the QUIET check. */
@Mixin(SectionStorage.class)
public abstract class EagerSaveScanPoiMixin implements EagerSaveScan.DirtySections {
    @Shadow @Final private LongLinkedOpenHashSet dirty;
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;

    @Override
    public LongSet sleep$dirty() {
        return dirty;
    }

    @Inject(method = "setDirty", at = @At("HEAD"))
    private void sleep$dirtied(long sectionPos, CallbackInfo ci) {
        if ((Object) this instanceof PoiManager) EagerSaveScan.poiDirty(levelHeightAccessor, sectionPos);
    }
}
