package dev.lull.opt;

import dev.lull.optsupport.EagerSaveScan;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EagerSaveScan: the holder's place in its map's walk list; the writes of wasAccessibleSinceLastSave and of the ticket
 * level (a holder outside the list that becomes accessible makes the list stale; a listed one loses QUIET).
 */
@Mixin(ChunkHolder.class)
public abstract class EagerSaveScanHolderMixin implements EagerSaveScan.Listed {
    @Shadow private boolean wasAccessibleSinceLastSave;
    @Unique private boolean sleep$listed;
    @Unique private EagerSaveScan.Index sleep$index;
    @Unique private int sleep$idx = -1;

    @Override
    public boolean sleep$listed() {
        return sleep$listed;
    }

    @Override
    public void sleep$setListed(boolean listed, EagerSaveScan.Index index, int idx) {
        sleep$listed = listed;
        sleep$index = index;
        sleep$idx = idx;
    }

    @Override
    public EagerSaveScan.Index sleep$index() {
        return sleep$index;
    }

    @Override
    public int sleep$idx() {
        return sleep$idx;
    }

    @Inject(method = "updateFutures", at = @At("TAIL"))
    private void sleep$futures(ChunkMap chunkMap, Executor executor, CallbackInfo ci) {
        EagerSaveScan.touched((ChunkHolder) (Object) this, wasAccessibleSinceLastSave);
    }

    @Inject(method = "refreshAccessibility", at = @At("TAIL"))
    private void sleep$refresh(CallbackInfo ci) {
        EagerSaveScan.touched((ChunkHolder) (Object) this, wasAccessibleSinceLastSave);
    }

    @Inject(method = "setTicketLevel", at = @At("TAIL"))
    private void sleep$level(int level, CallbackInfo ci) {
        EagerSaveScan.touched((ChunkHolder) (Object) this, wasAccessibleSinceLastSave);
    }
}
