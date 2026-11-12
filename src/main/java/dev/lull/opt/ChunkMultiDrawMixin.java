package dev.lull.opt;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.lull.optsupport.ChunkMultiDraw;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
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
 * vanilla renderChunkLayer for non-translucent layers (state setup, uniforms, the stage event, teardown), with the
 * per-section loop replaced; a layer whose shader is not patched keeps the vanilla loop. 1.20.1: renderChunkLayer
 * (1.21.1: renderSectionLayer) sets the samplers and uniforms itself (no setDefaultUniforms: no ScreenSize, no line
 * width), takes the model-view matrix from the PoseStack, and walks renderChunksInFrustum (RenderChunkInfo).
 */
@Mixin(LevelRenderer.class)
@SuppressWarnings("rawtypes")
public abstract class ChunkMultiDrawMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ObjectArrayList renderChunksInFrustum;
    @Shadow private int ticks;
    @Shadow public abstract net.minecraft.client.renderer.culling.Frustum getFrustum();

    @Shadow private double xTransparentOld;
    @Shadow private double yTransparentOld;
    @Shadow private double zTransparentOld;
    @Shadow private ChunkRenderDispatcher chunkRenderDispatcher;

    @Unique private static final boolean ACTIVE = ChunkMultiDraw.ACTIVE;
    /** the sampler names vanilla builds with "Sampler" + i for every layer (the same strings, made once) */
    @Unique private static final String[] SLEEP$SAMPLERS = {"Sampler0", "Sampler1", "Sampler2", "Sampler3", "Sampler4", "Sampler5", "Sampler6",
            "Sampler7", "Sampler8", "Sampler9", "Sampler10", "Sampler11"};

    /** renderChunkLayer's sampler and uniform setup, line for line (before shader.apply()) */
    @Unique
    private static void sleep$uniforms(ShaderInstance shader, PoseStack poseStack, Matrix4f projectionMatrix) {
        for (int i = 0; i < 12; ++i) {
            int j = RenderSystem.getShaderTexture(i);
            shader.setSampler(SLEEP$SAMPLERS[i], j);
        }
        if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(poseStack.last().pose());
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projectionMatrix);
        if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        if (shader.GLINT_ALPHA != null) shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
        if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if (shader.FOG_SHAPE != null) shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        RenderSystem.setupShaderLights(shader);
    }

    /**
     * Vanilla's renderChunkLayer for the translucent layer, line for line (the resort of up to 15 sections when the
     * camera moved, the visible sections backwards), the per-section loop replaced by ChunkMultiDraw.drawTranslucent
     * when every visible section has its copy.
     */
    @Unique
    private void sleep$translucent(RenderType renderType, PoseStack poseStack, double x, double y, double z, Matrix4f projectionMatrix) {
        RenderSystem.assertOnRenderThread();
        renderType.setupRenderState();
        var profiler = this.minecraft.getProfiler();
        profiler.push("translucent_sort");
        double d0 = x - this.xTransparentOld;
        double d1 = y - this.yTransparentOld;
        double d2 = z - this.zTransparentOld;
        if (d0 * d0 + d1 * d1 + d2 * d2 > 1.0D) {
            int j = net.minecraft.core.SectionPos.posToSectionCoord(x);
            int k = net.minecraft.core.SectionPos.posToSectionCoord(y);
            int l = net.minecraft.core.SectionPos.posToSectionCoord(z);
            boolean flag = j != net.minecraft.core.SectionPos.posToSectionCoord(this.xTransparentOld)
                    || l != net.minecraft.core.SectionPos.posToSectionCoord(this.zTransparentOld)
                    || k != net.minecraft.core.SectionPos.posToSectionCoord(this.yTransparentOld);
            this.xTransparentOld = x;
            this.yTransparentOld = y;
            this.zTransparentOld = z;
            int i1 = 0;
            for (Object info : this.renderChunksInFrustum) {
                if (i1 < 15 && (flag || ((ChunkInfoAccessor) info).sleep$axisAligned(j, k, l))
                        && ((ChunkInfoAccessor) info).sleep$chunk().resortTransparency(renderType, this.chunkRenderDispatcher)) i1++;
            }
        }
        profiler.pop();
        profiler.push("filterempty");
        profiler.popPush(() -> "render_" + renderType);
        ShaderInstance shader = RenderSystem.getShader();
        sleep$uniforms(shader, poseStack, projectionMatrix);
        shader.apply();
        Uniform uniform = shader.CHUNK_OFFSET;
        if (!ChunkMultiDraw.drawTranslucent(this.renderChunksInFrustum, x, y, z, shader)) {
            for (int n = this.renderChunksInFrustum.size() - 1; n >= 0; n--) {   // vanilla's loop: backwards
                ChunkRenderDispatcher.RenderChunk s = ((ChunkInfoAccessor) this.renderChunksInFrustum.get(n)).sleep$chunk();
                if (s.getCompiledChunk().isEmpty(renderType)) continue;
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
        net.minecraftforge.client.ForgeHooksClient.dispatchRenderStage(renderType, (LevelRenderer) (Object) this, poseStack, projectionMatrix, this.ticks,
                this.minecraft.gameRenderer.getMainCamera(), this.getFrustum());
        renderType.clearRenderState();
    }

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void sleep$multi(RenderType renderType, PoseStack poseStack, double x, double y, double z, Matrix4f projectionMatrix, CallbackInfo ci) {
        dev.lull.optsupport.SectionLists.currentLayer = renderType;
        if (renderType == RenderType.translucent() && ChunkMultiDraw.translucentReady()) {
            sleep$translucent(renderType, poseStack, x, y, z, projectionMatrix);
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
        sleep$uniforms(shader, poseStack, projectionMatrix);
        shader.apply();
        if (dev.lull.optsupport.OcclusionCull.ON) dev.lull.optsupport.OcclusionCull.matrices(poseStack.last().pose(), projectionMatrix);
        Uniform uniform = shader.CHUNK_OFFSET;
        if (!(region ? dev.lull.optsupport.RegionDraw.draw(renderType, this.renderChunksInFrustum, x, y, z, shader)
                : ChunkMultiDraw.draw(renderType, this.renderChunksInFrustum, x, y, z, shader))) {
            for (Object info : this.renderChunksInFrustum) {   // vanilla loop
                ChunkRenderDispatcher.RenderChunk s = ((ChunkInfoAccessor) info).sleep$chunk();
                if (s.getCompiledChunk().isEmpty(renderType)) continue;
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
        net.minecraftforge.client.ForgeHooksClient.dispatchRenderStage(renderType, (LevelRenderer) (Object) this, poseStack, projectionMatrix, this.ticks,
                this.minecraft.gameRenderer.getMainCamera(), this.getFrustum());
        renderType.clearRenderState();
        ci.cancel();
    }
}
