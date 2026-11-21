package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.EagerSaveScan;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** EagerSaveScan (notes/88): processUnloads' eager-save walk over the holders whose call can do something. */
@Mixin(ChunkMap.class)
public abstract class EagerSaveScanMixin implements EagerSaveScan.Owner {
    @Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;
    @Shadow @Final private PoiManager poiManager;
    @Shadow @Final ServerLevel level;
    @Unique private final EagerSaveScan.Index sleep$index = new EagerSaveScan.Index();

    @Override
    public EagerSaveScan.Index sleep$eagerIndex() {
        return sleep$index;
    }

    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectCollection;iterator()Lit/unimi/dsi/fastutil/objects/ObjectIterator;"))
    private ObjectIterator<ChunkHolder> sleep$accessibleOnly(ObjectCollection<ChunkHolder> values, Operation<ObjectIterator<ChunkHolder>> original) {
        Long2ObjectLinkedOpenHashMap<ChunkHolder> m = visibleChunkMap;
        if (values != m.values()) return original.call(values);   // (not the visible map's walk)
        return EagerSaveScan.iterator(sleep$index, m, poiManager, level.getMinSection(), level.getMaxSection());
    }

    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;saveChunkIfNeeded(Lnet/minecraft/server/level/ChunkHolder;)Z"))
    private boolean sleep$visited(ChunkMap self, ChunkHolder holder, Operation<Boolean> original) {
        boolean r = original.call(self, holder);
        EagerSaveScan.visitedOne(sleep$index, holder, poiManager, level.getMinSection(), level.getMaxSection());
        return r;
    }
}
