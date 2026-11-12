package dev.lull.opt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.lull.optsupport.ChunkMultiDraw;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * exp/chunk-multidraw (client): the opaque chunk layers through ChunkMultiDraw (one multi-draw per region). The body is
 * vanilla renderSectionLayer for non-translucent layers (state setup, uniforms, the stage event, teardown), with the
 * per-section loop replaced; a layer whose shader is not patched keeps the vanilla loop.
 */
@Mixin(LevelRenderer.class)
public abstract class ChunkMultiDrawMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow private int ticks;
    @Shadow public abstract net.minecraft.client.renderer.culling.Frustum getFrustum();

    @Shadow private double xTransparentOld;
    @Shadow private double yTransparentOld;
    @Shadow private double zTransparentOld;
    @Shadow @Final private SectionRenderDispatcher sectionRenderDispatcher;

    @Unique private static final boolean ACTIVE = ChunkMultiDraw.ACTIVE;

    /**
     * Vanilla's renderSectionLayer for the translucent layer, line for line (the resort of up to 15 sections when the
     * camera moved, the visible sections backwards), the per-section loop replaced by ChunkMultiDraw.drawTranslucent
     * when every visible section has its copy.
     */
    @Unique
    private void sleep$translucent(RenderType renderType, double x, double y, double z, Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        RenderSystem.assertOnRenderThread();
        renderType.setupRenderState();
        var profiler = this.minecraft.getProfiler();
        profiler.push("translucent_sort");
        double d0 = x - this.xTransparentOld;
        double d1 = y - this.yTransparentOld;
        double d2 = z - this.zTransparentOld;
        if (d0 * d0 + d1 * d1 + d2 * d2 > 1.0) {
            int i = net.minecraft.core.SectionPos.posToSectionCoord(x);
            int j = net.minecraft.core.SectionPos.posToSectionCoord(y);
            int k = net.minecraft.core.SectionPos.posToSectionCoord(z);
            boolean flag = i != net.minecraft.core.SectionPos.posToSectionCoord(this.xTransparentOld)
                    || k != net.minecraft.core.SectionPos.posToSectionCoord(this.zTransparentOld)
                    || j != net.minecraft.core.SectionPos.posToSectionCoord(this.yTransparentOld);
            this.xTransparentOld = x;
            this.yTransparentOld = y;
            this.zTransparentOld = z;
            int l = 0;
            for (SectionRenderDispatcher.RenderSection s : this.visibleSections) {
                if (l < 15 && (flag || s.isAxisAlignedWith(i, j, k)) && s.resortTransparency(renderType, this.sectionRenderDispatcher)) l++;
            }
        }
        profiler.pop();
        profiler.push("filterempty");
        profiler.popPush(() -> "render_" + renderType);
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, frustumMatrix, projectionMatrix, this.minecraft.getWindow());
        shader.apply();
        var uniform = shader.CHUNK_OFFSET;
        if (!ChunkMultiDraw.drawTranslucent(this.visibleSections, x, y, z, shader)) {
            for (int n = this.visibleSections.size() - 1; n >= 0; n--) {   // vanilla's loop: backwards
                SectionRenderDispatcher.RenderSection s = this.visibleSections.get(n);
                if (s.getCompiled().isEmpty(renderType)) continue;
                VertexBuffer vb = s.getBuffer(renderType);
                BlockPos o = s.getOrigin();
                if (uniform != null) {
                    uniform.set((float) ((double) o.getX() - x), (float) ((double) o.getY() - y), (float) ((double) o.getZ() - z));
                    uniform.upload();
                }
                vb.bind();
                // (what ShaderLightTranslucentMixin does in vanilla's loop, which this method replaces)
                if (dev.lull.optsupport.LightVolumes.ON) dev.lull.optsupport.LightVolumes.translucentDraw(s);
                vb.draw();
                if (dev.lull.optsupport.LightVolumes.ON) dev.lull.optsupport.LightVolumes.translucentDrawn();
            }
        }
        if (uniform != null) uniform.set(0.0F, 0.0F, 0.0F);
        shader.clear();
        VertexBuffer.unbind();
        profiler.pop();
        net.neoforged.neoforge.client.ClientHooks.dispatchRenderStage(renderType, (LevelRenderer) (Object) this, frustumMatrix, projectionMatrix, this.ticks,
                this.minecraft.gameRenderer.getMainCamera(), this.getFrustum());
        renderType.clearRenderState();
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    private void sleep$multi(RenderType renderType, double x, double y, double z, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        dev.lull.optsupport.SectionLists.currentLayer = renderType;
        if (renderType == RenderType.translucent() && ChunkMultiDraw.translucentReady()) {
            sleep$translucent(renderType, x, y, z, frustumMatrix, projectionMatrix);
            ci.cancel();
            return;
        }
        boolean region = ACTIVE && dev.lull.optsupport.RegionDraw.on();   // drivers without draw parameters: by region, vanilla's shader
        if (!ACTIVE || !ChunkMultiDraw.layerSupported(renderType) || ChunkMultiDraw.PATCHED.isEmpty() && !region) return;
        RenderSystem.assertOnRenderThread();
        renderType.setupRenderState();
        var profiler = this.minecraft.getProfiler();
        profiler.push("filterempty");
        profiler.popPush(() -> "render_" + renderType);
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, frustumMatrix, projectionMatrix, this.minecraft.getWindow());
        shader.apply();
        if (dev.lull.optsupport.OcclusionCull.ON) dev.lull.optsupport.OcclusionCull.matrices(frustumMatrix, projectionMatrix);
        var uniform = shader.CHUNK_OFFSET;
        if (!(region ? dev.lull.optsupport.RegionDraw.draw(renderType, this.visibleSections, x, y, z, shader)
                : ChunkMultiDraw.draw(renderType, this.visibleSections, x, y, z, shader))) {
            for (SectionRenderDispatcher.RenderSection s : this.visibleSections) {   // vanilla loop
                if (s.getCompiled().isEmpty(renderType)) continue;
                VertexBuffer vb = s.getBuffer(renderType);
                BlockPos o = s.getOrigin();
                if (uniform != null) {
                    uniform.set((float) ((double) o.getX() - x), (float) ((double) o.getY() - y), (float) ((double) o.getZ() - z));
                    uniform.upload();
                }
                vb.bind();
                vb.draw();
            }
        }
        if (uniform != null) uniform.set(0.0F, 0.0F, 0.0F);
        shader.clear();
        VertexBuffer.unbind();
        profiler.pop();
        net.neoforged.neoforge.client.ClientHooks.dispatchRenderStage(renderType, (LevelRenderer) (Object) this, frustumMatrix, projectionMatrix, this.ticks,
                this.minecraft.gameRenderer.getMainCamera(), this.getFrustum());
        renderType.clearRenderState();
        ci.cancel();
    }
}
