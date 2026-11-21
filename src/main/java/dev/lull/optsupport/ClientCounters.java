package dev.lull.optsupport;

import java.util.Locale;

/** State and counters of the client-side optimisations (render thread unless noted). */
public final class ClientCounters {
    /** other mods' counters in the client report */
    public static final java.util.List<java.util.function.Supplier<String>> EXTRA = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static String extra() {
        StringBuilder sb = new StringBuilder();
        for (java.util.function.Supplier<String> x : EXTRA) sb.append("; ").append(x.get());
        return sb.toString();
    }

    public static long uniformsSent, uniformsSkipped;

    public static long biomeSkips, samplerHits;
    /** frame-hash runs: animated textures stop (FrameHashFreezeMixin) */
    public static volatile boolean freezeTextures;
    /** changes whenever the set of loaded chunks or their biomes may have changed (AmbientChunksMixin) */
    public static long ambientVersion;

    /** Implemented by ClientChunkCache (AmbientChunksMixin). */
    public interface AmbientChunks {
        boolean sleep$anyAmbient();
    }

    private ClientCounters() {}

    public static String report() {
        // the counters walk render-side state (EntityBatch's maps ...) the frame thread changes while it draws: no frame
        // drawing while the report is made (FrameThread; nothing without it)
        FrameThread.holdForMain();
        try {
            return report0();
        } finally {
            FrameThread.releaseForMain();
        }
    }

    private static String report0() {
        return String.format(Locale.ROOT, "client opts: ambient biome lookups skipped %d, sampler locations from cache %d; collision memo hits %d, misses %d; " + CollisionMask.report() + "; " + CollideKernel.report() + "; " + ParticleStats.report() + "; " + ParallelParticles.report() + "; " + ParticleGpu.report() + "; " + ParticleLightMemo.report() + "; " + ParallelEntities.report() + "; %s",
                biomeSkips, samplerHits, BlockCollisionMemo.hits, BlockCollisionMemo.misses, EventSites.report())
                + (Boolean.getBoolean("lab.opt.ChunkMultiDrawMixin") ? "; " + (RegionDraw.on() ? RegionDraw.report() : ChunkMultiDraw.report()) : "")
                + (Boolean.getBoolean("lab.opt.VertexSortRadixMixin") ? "; " + QuadSort.report() : "")
                + (Boolean.getBoolean("lab.opt.EntityBatchMixin") ? "; " + EntityBatch.report() : "")
                + (Boolean.getBoolean("lab.opt.RenderCacheMixin") ? "; " + RenderCache.report() : "")
                + (Boolean.getBoolean("lab.opt.ParallelEntitiesMixin") ? "; " + ParallelRender.report() : "")
                + (EmfSplit.classes.get() > 0 ? "; " + EmfSplit.report() : "")
                + (Boolean.getBoolean("lab.opt.PartBatchMixin") ? "; " + PartBatch.report() + "; " + Materials.report() : "") + extra()
                + (Boolean.getBoolean("lab.opt.FrustumRegionMixin") ? "; " + FrustumRegions.report() : "")
                + (TickSlice.ON ? "; " + TickSlice.report() : "")
                + (GlInbox.ON ? "; " + GlInbox.report() : "")
                + (TaskBudget.ON ? "; " + TaskBudget.report() : "")
                + TickThread.report()
                + RenderThread.report()
                + FrameThread.report()
                + (ChunkChurn.ON ? "; " + ChunkChurn.report() + "; " + ChunkChurn.meshReport() : "") + (OcclusionFast.ON ? "; " + OcclusionFast.report() : "") + (LazySections.ON ? "; " + LazySections.report() : "") + (MeshPark.ON ? "; " + MeshPark.report() : "") + (TintBlend.ON ? "; " + TintBlend.report() : "") + (LazyChunkBuffers.made > 0 ? "; " + LazyChunkBuffers.report() : "") + (MeshMemo.ON ? "; " + MeshMemo.report() : "") + (LightSnapshot.SHARDS || LightSnapshot.VERIFY ? "; " + LightSnapshot.report() : "") + (Enclosed.ON ? "; " + Enclosed.report() : "") + (RegionStates.TIME ? "; mesh time: " + RegionStates.timeReport() : "") + (CompileWalk.ON ? "; " + CompileWalk.report() : "") + (Hysteresis.ON ? "; " + Hysteresis.report() : "") + (Boolean.getBoolean("sleep.clientAuto.holes") ? "; " + dev.lull.ClientAuto.holesReport() : "")
                + "; " + dev.lull.ClassBytes.passReport() + (FrameProfile.ON ? "; " + FrameProfile.report() : "")
                + (FrameProfile.SERVER_ON ? "; " + FrameProfile.serverReport() : "")
                + (StallProbe.ON ? "; " + StallProbe.report() : "")
                + (ClientTickProfile.ON ? "; " + ClientTickProfile.report() : "")
                + (UploadRing.ON || UploadRing.RECORDS || Integer.getInteger("lab.gpu.framesInFlight", 0) > 0 || Boolean.getBoolean("lab.gpu.framesInFlight.auto") ? "; " + UploadRing.report() : "")
                + (GpuAnim.checkReport().isEmpty() ? "" : "; " + GpuAnim.checkReport())
                + (Boolean.getBoolean("lab.opt.SectionListsMixin") ? "; " + SectionLists.report() : "")
                + (Boolean.getBoolean("lab.opt.BlockMeshMemoMixin") ? "; " + BlockMeshMemo.report() : "")
                + (Boolean.getBoolean("lab.opt.ShaderLightMixin") ? "; " + LightVolumes.report() : "")
                + (Boolean.getBoolean("lab.opt.ChunkTickMemoMixin") ? "; chunk tick memo: hits " + ChunkTickMemo.hits + ", misses " + ChunkTickMemo.misses + ", epoch " + ChunkTickMemo.epoch : "")
                + (Boolean.getBoolean("lab.opt.ClientLightAsyncMixin") ? "; " + ClientLightAsync.report() + "; " + FrameReactor.report() : "")
                + (Boolean.getBoolean("lab.opt.UniformDedupeMixin") ? "; uniform uploads sent " + uniformsSent + ", skipped (same value) " + uniformsSkipped : "")
                + (Boolean.getBoolean("lab.opt.ClientBiomeMemoMixin") && net.minecraft.client.Minecraft.getInstance().level instanceof BiomeMemo bm ? "; " + bm.sleep$biomeReport() : "");
    }
}
