package dev.lull.opt;

import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dlab.meshMemo: the section keeps its mesh's inputs hash (MeshMemo); its compile tasks know their section. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class MeshMemoSectionMixin implements dev.lull.optsupport.MeshMemo.Holder {
    @Unique private volatile long sleep$memo, sleep$incoming;
    @Unique private volatile boolean sleep$expectSame;
    @Unique private final long[] sleep$crcs = new long[56];

    @Override
    public long sleep$memo() {
        return sleep$memo;
    }

    @Override
    public void sleep$setMemo(long h) {
        sleep$memo = h;
    }

    @Override
    public long sleep$incoming() {
        return sleep$incoming;
    }

    @Override
    public void sleep$setIncoming(long h) {
        sleep$incoming = h;
    }

    @Override
    public boolean sleep$expectSame() {
        return sleep$expectSame;
    }

    @Override
    public void sleep$setExpectSame(boolean b) {
        sleep$expectSame = b;
    }

    @Override
    public long[] sleep$crcs() {
        return sleep$crcs;
    }

    /** the mesh set: its inputs' hash is what the compile handed over (0 from anything else - the empty compile) */
    @Inject(method = "setCompiled", at = @At("HEAD"))
    private void sleep$memoSet(SectionRenderDispatcher.CompiledSection compiled, CallbackInfo ci) {
        if (dev.lull.optsupport.MeshMemo.VERIFY) dev.lull.optsupport.MeshMemo.committed((SectionRenderDispatcher.RenderSection) (Object) this, compiled, sleep$expectSame);
        sleep$memo = sleep$incoming;
        sleep$incoming = 0;
        sleep$expectSame = false;
    }

    /** a new origin or a release: no mesh, no memo */
    @Inject(method = "reset", at = @At("HEAD"))
    private void sleep$memoReset(CallbackInfo ci) {
        sleep$memo = 0;
        sleep$incoming = 0;
        java.util.Arrays.fill(sleep$crcs, 0);
    }

    @Inject(method = "createCompileTask", at = @At("RETURN"))
    private void sleep$taskSection(RenderRegionCache cache, @SuppressWarnings("rawtypes") CallbackInfoReturnable cir) {
        if (cir.getReturnValue() instanceof dev.lull.optsupport.MeshMemo.Task t) t.sleep$setSection((SectionRenderDispatcher.RenderSection) (Object) this);
    }
}
