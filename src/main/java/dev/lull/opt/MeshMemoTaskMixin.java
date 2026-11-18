package dev.lull.opt;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dlab.meshMemo: a rebuild whose inputs are its section's current mesh's is done at once - the mesh, its arena
 * entries and the section lists stay as they are (MeshMemo). Otherwise the inputs' hash goes with the compile to
 * setCompiled, if the inputs held still while vanilla meshed.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public abstract class MeshMemoTaskMixin implements dev.lull.optsupport.MeshMemo.Task {
    @Shadow protected RenderChunkRegion region;
    @Shadow @Final private List<?> additionalRenderers;
    @Unique private SectionRenderDispatcher.RenderSection sleep$section;
    @Unique private RenderChunkRegion sleep$region;
    @Unique private long sleep$hash, sleep$tintEpoch, sleep$lightEpoch;

    @Override
    public void sleep$setSection(SectionRenderDispatcher.RenderSection s) {
        sleep$section = s;
    }

    @Inject(method = "doTask", at = @At("HEAD"), cancellable = true)
    private void sleep$memo(SectionBufferBuilderPack pack, @SuppressWarnings("rawtypes") CallbackInfoReturnable cir) {
        sleep$hash = 0;
        sleep$region = null;
        sleep$tintEpoch = dev.lull.optsupport.MeshMemo.TINT_EPOCH.get();
        sleep$lightEpoch = dev.lull.optsupport.MeshMemo.LIGHT_EPOCH.get();
        SectionRenderDispatcher.RenderSection s = sleep$section;
        RenderChunkRegion r = region;
        var level = Minecraft.getInstance().level;
        if (s == null) return;
        var hd = (dev.lull.optsupport.MeshMemo.Holder) s;
        hd.sleep$setIncoming(0);   // (first: an empty compile must not take an older compile's hash)
        if (r == null || level == null) return;
        if (dev.lull.optsupport.MeshMemo.translucent(s)) {
            dev.lull.optsupport.MeshMemo.NOT_MEMO.incrementAndGet();
            return;
        }
        long h = dev.lull.optsupport.MeshMemo.inputs(s, r, level, !additionalRenderers.isEmpty());
        if (h == 0) {
            dev.lull.optsupport.MeshMemo.NOT_MEMO.incrementAndGet();
            return;
        }
        if (h == hd.sleep$memo()) {
            if (!dev.lull.optsupport.MeshMemo.VERIFY) {
                region = null;
                dev.lull.optsupport.MeshMemo.SKIPPED.incrementAndGet();
                cir.setReturnValue(CompletableFuture.completedFuture(dev.lull.optsupport.MeshMemo.successful()));
                return;
            }
            hd.sleep$setExpectSame(true);
        }
        sleep$hash = h;
        sleep$region = r;
    }

    /** vanilla meshed: the inputs hashed again - moved meanwhile, no memo for this mesh */
    @Inject(method = "doTask", at = @At("RETURN"))
    private void sleep$memoAfter(SectionBufferBuilderPack pack, @SuppressWarnings("rawtypes") CallbackInfoReturnable cir) {
        RenderChunkRegion r = sleep$region;
        sleep$region = null;
        if (r == null || sleep$hash == 0 || sleep$section == null) return;
        var level = Minecraft.getInstance().level;
        long h2 = level == null ? 0 : dev.lull.optsupport.MeshMemo.inputs(sleep$section, r, level, false);
        var hd = (dev.lull.optsupport.MeshMemo.Holder) sleep$section;
        if (h2 != sleep$hash) {
            dev.lull.optsupport.MeshMemo.MOVED.incrementAndGet();
            hd.sleep$setIncoming(0);
            return;
        }
        if (dev.lull.optsupport.MeshMemo.LIGHT_GUARD && (dev.lull.optsupport.MeshMemo.LIGHT_EPOCH.get() != sleep$lightEpoch || (sleep$lightEpoch & 1) != 0)) {   // (odd: a batch was running)
            dev.lull.optsupport.MeshMemo.LIGHT_MOVED.incrementAndGet();
            hd.sleep$setIncoming(0);
            return;
        }
        if (dev.lull.optsupport.MeshMemo.TINT_EPOCH.get() != sleep$tintEpoch || (sleep$tintEpoch & 1) != 0) {   // colours may be from before a nearby chunk loaded
            dev.lull.optsupport.MeshMemo.TINT_MOVED.incrementAndGet();
            hd.sleep$setIncoming(0);
            return;
        }
        hd.sleep$setIncoming(sleep$hash);
    }
}
