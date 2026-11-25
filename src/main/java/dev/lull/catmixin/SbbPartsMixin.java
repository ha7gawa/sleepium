package dev.lull.catmixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lull.optsupport.PartBatch;
import net.createmod.catnip.render.ShadeSeparatingSuperByteBuffer;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.TemplateMesh;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create's SuperByteBuffer from VRAM: renderInto transforms every vertex of a cached template on the CPU each call; here
 * the template goes to VRAM once (PartBatch's geometry, keyed by the template) and the call becomes a part record
 * (PartBatch.captureSbb: matrices, colour, light, overlay, diffuse on the GPU) when it can be: no UV shift (scrolling
 * belts), no light from the level (contraptions), a plain template, a batched BLOCK-format quad buffer - else the CPU
 * path as before. reset() as renderInto ends.
 */
@Mixin(value = ShadeSeparatingSuperByteBuffer.class, remap = false)
public abstract class SbbPartsMixin implements PartBatch.GeometrySource, dev.lull.optsupport.SbbLocal {
    @Shadow @Final private TemplateMesh template;
    @Shadow @Final private int[] shadeSwapVertices;
    @Shadow @Final private PoseStack transforms;
    @Shadow private float r;
    @Shadow private float g;
    @Shadow private float b;
    @Shadow private float a;
    @Shadow private boolean disableDiffuse;
    @Shadow private SuperByteBuffer.SpriteShiftFunc spriteShiftFunc;
    @Shadow private boolean hasCustomOverlay;
    @Shadow private int overlay;
    @Shadow private boolean hasCustomLight;
    @Shadow private int packedLight;
    @Shadow private boolean useLevelLight;
    @Shadow private net.minecraft.world.level.BlockAndTintGetter levelWithLight;
    @Shadow private org.joml.Matrix4f lightTransform;
    @Shadow @Final private boolean invertFakeDiffuseNormal;

    @Shadow
    public abstract SuperByteBuffer reset();

    /** the arguments of the last shiftUVScrolling (null: no shift, or one of another kind) */
    @org.spongepowered.asm.mixin.Unique private Object sleep$shift;
    @org.spongepowered.asm.mixin.Unique private float sleep$shiftU, sleep$shiftV;

    @Inject(method = "shiftUVScrolling(Lnet/createmod/catnip/render/SpriteShiftEntry;FF)Lnet/createmod/catnip/render/SuperByteBuffer;", at = @At("HEAD"))
    private void sleep$scrolling(net.createmod.catnip.render.SpriteShiftEntry entry, float scrollU, float scrollV,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<SuperByteBuffer> cir) {
        sleep$shift = entry;
        sleep$shiftU = scrollU;
        sleep$shiftV = scrollV;
    }

    @Inject(method = {"shiftUV", "shiftUVtoSheet", "reset"}, at = @At("HEAD"))
    private void sleep$otherShift(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<SuperByteBuffer> cir) {
        sleep$shift = null;
    }

    @Override
    public PoseStack.Pose sleep$local() {
        return transforms.last();
    }

    private static boolean memoChecked;

    /** KineticMemo (Create's rotating parts without their renderer), opt-in, installed once Create is known to be there. */
    private static void sleep$memo() {
        memoChecked = true;
        var mods = net.neoforged.fml.loading.LoadingModList.get();
        if (!Boolean.getBoolean("lab.opt.KineticMemoMixin") || mods == null || mods.getModFileById("create") == null) return;
        try {
            dev.lull.Names.publicMethod(Class.forName("dev.lull.optsupport.KineticMemo"), "install").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            System.out.println("[sleep/opt] kinetic memo not installed: " + e);
        }
    }

    @Inject(method = "renderInto", at = @At("HEAD"), cancellable = true)
    private void sleep$parts(PoseStack input, VertexConsumer builder, CallbackInfo ci) {
        PartBatch.sbbCalls++;
        if (!memoChecked) sleep$memo();
        PartBatch.SbbRecorder rcd = PartBatch.sbbRecorder;
        if (template.isEmpty()) return;
        if (spriteShiftFunc != null) {
            PartBatch.sbbShift++;
            if (rcd == null || !rcd.recording()) return;
            // a scrolling shift while a memo records: drawn on the CPU path now, kept as a part with its shift
            if (sleep$shift == null || useLevelLight || dev.lull.optsupport.RenderCache.recording() || !PartBatch.sbbTarget(template, this, null, builder)) {
                rcd.cpu();
                return;
            }
            if (PartBatch.lightDirections == null) PartBatch.lightDirections = RenderSystemLightAccessor::sleep$lightDirections;
            boolean diffuse = !disableDiffuse && !dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse();
            int light = hasCustomLight ? packedLight : 0;
            int ov = hasCustomOverlay ? overlay : net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
            rcd.capturedShifted(this, input.last(), transforms.last(), r, g, b, a, diffuse, invertFakeDiffuseNormal ? -1.0F : 1.0F, light, ov,
                    PartBatch.lastSbbType, PartBatch.lastSbbGeo, sleep$shift, sleep$shiftU, sleep$shiftV, template.vertexCount());
            return;
        }
        if (useLevelLight) {
            PartBatch.sbbLevelLight++;
            if (rcd != null && rcd.recording()) {
                rcd.cpu();
                return;
            }
            // lit by the level (contraptions, actors): the light looked up on the GPU from a light box
            if (!PartBatch.LIT_ON || dev.lull.optsupport.RenderCache.recording()) return;
            if (PartBatch.lightDirections == null) PartBatch.lightDirections = RenderSystemLightAccessor::sleep$lightDirections;
            boolean diffuse = !disableDiffuse && !dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse();
            int light = hasCustomLight ? packedLight : 0;
            if (PartBatch.captureLit(template, this, input.last(), transforms.last(), lightTransform, levelWithLight, r, g, b, a, diffuse,
                    invertFakeDiffuseNormal ? -1.0F : 1.0F, light, 0, builder)) {
                reset();
                ci.cancel();
            }
            return;
        }
        if (dev.lull.optsupport.RenderCache.recording()) {   // the render cache records it: a part draws it cheaper - directly
            dev.lull.optsupport.RenderCache.partsWanted();
            return;
        }
        if (PartBatch.lightDirections == null) PartBatch.lightDirections = RenderSystemLightAccessor::sleep$lightDirections;
        boolean diffuse = !disableDiffuse && !dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse();
        int light = hasCustomLight ? packedLight : 0;
        int ov = hasCustomOverlay ? overlay : net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
        float fake = invertFakeDiffuseNormal ? -1.0F : 1.0F;
        if (PartBatch.captureSbb(template, this, null, input.last(), transforms.last(), r, g, b, a, diffuse, fake, light, ov, builder)) {
            if (rcd != null && rcd.recording())
                rcd.captured(this, input.last(), transforms.last(), r, g, b, a, diffuse, fake, light, ov, PartBatch.lastSbbType, PartBatch.lastSbbGeo);
            reset();
            ci.cancel();
        } else if (rcd != null) {
            rcd.cpu();
        }
    }

    /** The template in local space for PartBatch's geometry (with its per-vertex colour and own light). */
    @Override
    public boolean sleep$emit(Object unusedA, Object unusedB, VertexConsumer consumer) {
        int n = template.vertexCount();
        boolean shaded = true;
        int swap = 0, next = shadeSwapVertices.length > 0 ? shadeSwapVertices[0] : -1;
        for (int i = 0; i < n; i++) {
            if (i == next) {
                shaded = !shaded;
                swap++;
                next = swap < shadeSwapVertices.length ? shadeSwapVertices[swap] : -1;
            }
            if (consumer instanceof PartBatch.Flagged f) f.sleep$flags(shaded ? 0 : 8);
            int pn = template.normal(i);
            consumer.addVertex(template.x(i), template.y(i), template.z(i));
            if (consumer instanceof PartBatch.Flagged f) f.sleep$rawColor(template.color(i));   // bytes R, G, B, A as renderInto reads them
            int tl = template.light(i);   // its own light (catnip takes the max with the custom / level light)
            consumer.setUv(template.u(i), template.v(i)).setUv2(tl & 0xFFFF, tl >>> 16)
                    .setNormal((byte) (pn & 0xFF) / 127.0F, (byte) (pn >>> 8 & 0xFF) / 127.0F, (byte) (pn >>> 16 & 0xFF) / 127.0F);
        }
        return true;
    }
}
