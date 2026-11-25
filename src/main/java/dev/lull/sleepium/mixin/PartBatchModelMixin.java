package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.EntityCache;
import dev.lull.sleepium.render.PartBatch;
import net.minecraft.client.model.ModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PartBatch: a model part (and its children) as vertices in the batch, while an object is drawn. */
@Mixin(ModelRenderer.class)
public abstract class PartBatchModelMixin implements PartBatch.Holder {
    @Unique private PartBatch.PartData sleep$data;

    @Override
    public PartBatch.PartData sleep$partData() {
        return sleep$data;
    }

    @Override
    public void sleep$partData(PartBatch.PartData d) {
        sleep$data = d;
    }

    @Inject(method = "render(F)V", at = @At("HEAD"), cancellable = true)
    private void sleep$render(float scale, CallbackInfo ci) {
        if (PartBatch.active() && PartBatch.render((ModelRenderer) (Object) this, scale)) ci.cancel();
        else EntityCache.part((ModelRenderer) (Object) this, scale);
    }

    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void sleep$rendered(float scale, CallbackInfo ci) {
        EntityCache.partDone();
    }

    @Inject(method = "renderWithRotation(F)V", at = @At("HEAD"), cancellable = true)
    private void sleep$renderRot(float scale, CallbackInfo ci) {
        if (PartBatch.active() && PartBatch.renderWithRotation((ModelRenderer) (Object) this, scale)) ci.cancel();
        else if (EntityCache.capturing() && !EntityCache.inPart()) EntityCache.unsupported("renderWithRotation");
    }
}
