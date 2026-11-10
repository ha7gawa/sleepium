package dev.lull.opt;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates every experimental optimisation mixin behind a JVM flag, so one jar can be A/B tested by
 * interleaving runs with and without the flag:
 * <pre>  -Dlab.opt.GoalSelectorMixin=true   or   -Dlab.opt.all=true</pre>
 * Everything is OFF by default: the probe must not change the game unless asked to.
 */
public final class OptMixinPlugin implements IMixinConfigPlugin {
    static {
        dev.lull.optsupport.GameDefaults.ensure();   // a built jar's flags before any is read
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return sleep$decide(targetClassName, mixinClassName) && !dev.lull.optsupport.MixinYield.yields(targetClassName, mixinClassName)
                && !dev.lull.optsupport.MixinYield.needsYielded(mixinClassName);
    }

    private boolean sleep$decide(String targetClassName, String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        boolean on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt." + simple);
        // helpers that belong to a feature flag
        if (simple.equals("EntitySectionIterMixin") || simple.equals("ClassInstanceMultiMapAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.EntitySectionScanMixin")
                    || Boolean.getBoolean("lab.opt.EntitySectionIndexMixin");
        if (simple.equals("EntitySectionKeyMixin") || simple.equals("EntityCallbackAccessor") || simple.equals("EntityBoxIndexMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.EntitySectionIndexMixin");
        if (simple.equals("NeighborReactStateMixin") || simple.equals("NeighborReactMultiMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.NeighborReactMixin");
        if (simple.startsWith("AllocTrim"))   // one feature: temporary objects of hot paths reused (AllocTrim)
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.AllocTrim");
        if (simple.startsWith("LightSnapshot"))   // one feature: light snapshots as base + delta (LightSnapshot)
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.LightSnapshotMixin");
        if (simple.startsWith("ChunkIo"))   // one feature: encode on the pool, batched region writes (notes/38)
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ChunkIo");
        if (simple.startsWith("JobSite"))   // one feature: brains' job sites counted, PoiCompetitorScan's walk skipped when alone
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.JobSiteIndex");
        if (simple.equals("WriteBarrierMixin"))   // the write barrier of mob parts (WriteBarrier, WB)
            on = Boolean.getBoolean("lab.opt.WriteBarrier");
        if (simple.equals("WriteBarrierSampleMixin"))   // its sampling window (1.21.1: NeoForge's EntityTickEvent Pre / Post)
            on = Boolean.getBoolean("lab.opt.WriteBarrier") && Integer.getInteger("lab.wb.sample", 0) > 0;
        if (simple.startsWith("OffThreadEntity"))   // one feature: entities added from another thread go where they belong
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.OffThreadEntity");
        if (simple.startsWith("GpuAnim"))   // one feature: animated sprites' frames copied on the GPU, interpolated ones through a mapped ring
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.GpuAnim");
        if (simple.startsWith("MoveMemo"))   // one feature: an unchanged move of a living entity skipped
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.MoveMemo");
        // mob sleep's runtime (notes/47): draws recorded, goal selectors' and attribute maps' modification counters
        boolean mobSleep = Boolean.getBoolean("lab.opt.EntitySleep") && Boolean.getBoolean("lab.sleep.components") && Boolean.getBoolean("lab.sleep.mobRuntime");
        if (simple.startsWith("MobSleep")) on = mobSleep;
        if (simple.equals("MoveMemoAttributesMixin")) on |= mobSleep;
        if (simple.equals("ChunkCacheClearMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ChunkCacheMixin");
        if (simple.equals("SectionStorageIndexMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.PoiColumnMixin");
        if (simple.equals("BlockBehaviourInvoker") || simple.equals("ShapeCacheMixin"))   // the memo needs the static-shape flags
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ShapeCacheMixin") || Boolean.getBoolean("lab.opt.BlockCollisionMemoMixin");
        if (simple.equals("SectionModCountMixin") || simple.equals("EntityCollisionMemoMixin") || simple.equals("EntityCollideMemoMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.BlockCollisionMemoMixin");
        if (simple.equals("BlockCollisionsFastMixin"))   // the masks live in SectionModCountMixin, built from ShapeCacheMixin's shapes
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.BlockCollisionsFastMixin") && Boolean.getBoolean("lab.opt.BlockCollisionMemoMixin");
        if (simple.equals("EntitySectionCollidableMixin") || simple.equals("EntityStorageCollidableMixin") || simple.equals("LevelEntityGetterAdapterAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.EntityCollisionSkipMixin");
        if (simple.equals("VoxelShapeAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.CubeShapeMixin");
        if (simple.equals("SignalProofStateMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.SignalPathMixin");
        // (1.20.1: ChunkCompiledMixin sees new compiles - 1.21.1's setCompiled hook; ChunkInfoAccessor reads RenderChunkInfo)
        if (simple.equals("ChunkSectionMixin") || simple.equals("ChunkUploadMixin") || simple.equals("ChunkCompiledMixin") || simple.equals("ChunkInfoAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ChunkMultiDrawMixin");
        if (simple.equals("ChunkShaderPatchMixin"))   // the shader patch hook of both chunk multi-draw and part batching
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ChunkMultiDrawMixin") || Boolean.getBoolean("lab.opt.PartBatchMixin")
                    || Boolean.getBoolean("lab.opt.ParticleGpuMixin");
        if (simple.equals("PartDrawMixin") || simple.equals("PartTypeMixin") || simple.equals("PartShardMixin") || simple.equals("PartFrameMixin") || simple.equals("ShaderStateShardAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.PartBatchMixin") || Boolean.getBoolean("lab.opt.ItemGpuMixin");
        if (simple.equals("ParticleGpuAccessor") || simple.equals("ParticleUvInvoker") || simple.equals("ParticleLightMemoMixin"))   // part of the particle records
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ParticleGpuMixin");
        if (simple.equals("ParticleEngineAccessor"))   // the particle shape memo's measurement, parallel particles' invokers, particle records' lists
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ParticleShapeMemoMixin") || Boolean.getBoolean("lab.opt.ParallelParticlesMixin")
                    || Boolean.getBoolean("lab.opt.ParticleGpuMixin");
        else if (simple.equals("EntityShapeMemoMixin"))   // part of the tick shape memo (the entity tick)
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ParticleShapeMemoMixin");
        if (simple.equals("NeighbourWakeMixin"))   // part of ObservedModSleep (no cost without it)
            on = Boolean.getBoolean("lab.sleep.ObservedModSleep");
        if (simple.equals("QuadGpuMixin"))   // draws through PartBatch's item queues, shader patch and draw hooks
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.QuadGpuMixin") && Boolean.getBoolean("lab.opt.PartBatchMixin");
        if (simple.equals("ItemGpuMixin"))   // draws through PartBatch's queues, shader patch and draw hooks
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ItemGpuMixin") && Boolean.getBoolean("lab.opt.PartBatchMixin");
        if (simple.equals("CompositeRenderTypeAccessor") || simple.equals("CompositeStateAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.EntityBatchMixin");
        if (simple.equals("RenderCacheBlockEntityMixin") || simple.equals("RenderCacheClockMixin") || simple.equals("BufferBuilderAccessor") || simple.equals("BufferSourceAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.RenderCacheMixin");
        if (simple.equals("ChunkMultiDrawCompatMixin"))   // part of chunk multi-draw
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ChunkMultiDrawMixin");
        if (simple.equals("ShaderSamplerNamesMixin"))   // part of the sampler cache flag
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ShaderSamplerCacheMixin");
        if (simple.equals("ParallelReloadMixin") || simple.equals("RenderCallJournalMixin") || simple.equals("TextureIdWorkerMixin") || simple.equals("TextureRegisterWorkerMixin") || simple.equals("EntityRenderDispatcherInvoker") || simple.equals("EntityRendererAccessor") || simple.equals("LevelRendererInvoker"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ParallelEntitiesMixin");
        if (simple.equals("BufferBuilderAccessor") || simple.equals("BufferSourceAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.RenderCacheMixin") || Boolean.getBoolean("lab.opt.ParallelEntitiesMixin")
                    || Boolean.getBoolean("lab.opt.ParticleGpuMixin")
                    || Boolean.getBoolean("lab.opt.PartBatchMixin") || Boolean.getBoolean("lab.opt.BlockMeshMemoMixin") || Boolean.getBoolean("lab.opt.ShaderLightMixin");
        if (simple.equals("VertexSortDirectMixin") || simple.equals("MeshDataAccessor"))   // part of the radix sort flag
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.VertexSortRadixMixin");
        if (simple.equals("SectionListsMixin") || simple.equals("LevelRendererVisibleAccessor"))   // needs the multi-draw entry slots
            on = (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.SectionListsMixin")) && Boolean.getBoolean("lab.opt.ChunkMultiDrawMixin");
        if (simple.equals("ClientChunkCacheBiomeMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ClientBiomeMemoMixin");
        if (simple.equals("SamplerUnitDedupeMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.UniformDedupeMixin");
        if (simple.equals("ModelPartIterMixin"))   // part of the model-part allocation flag
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ModelPartQuatMixin");
        if (simple.equals("RenderPriorityServerMixin"))   // part of the render priority flag
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.RenderPriorityMixin");
        // exp/frame-garbage: one flag for the per-frame garbage fixes
        if (simple.equals("Vec3FromRgbMixin") || simple.equals("OverworldFogMixin") || simple.equals("EntityCullNoAllocMixin") || simple.equals("FrustumInvoker")
                || simple.equals("ItemFrameOffsetMixin") || simple.equals("EntityLightNoAllocMixin") || simple.equals("BlockEntityCullNoAllocMixin")
                || simple.equals("UtilMemoizeMixin") || simple.equals("NameTagSkipMixin") || simple.equals("VertexNoAllocMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.FrameGarbageMixin");
        if (simple.equals("ClientLightCallbackMixin") || simple.equals("ClientLevelLightQueueAccessor") || simple.equals("ClientLightJoinMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ClientLightAsyncMixin");
        if (simple.equals("ShaderLightCompileMixin") || simple.equals("ShaderLightMeshMixin") || simple.equals("ShaderLightChunkCacheMixin") || simple.equals("ShaderLightFrameMixin") || simple.equals("ShaderLightFluidMixin") || simple.equals("ShaderLightTranslucentMixin")
                || simple.equals("LightBuilderAccessor"))   // (1.20.1: the builder's vertices for the recipes)
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ShaderLightMixin");
        if (simple.startsWith("WorldgenAlloc") || simple.startsWith("Xoroshiro"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.WorldgenAlloc");
        if (simple.startsWith("WorldgenSkip") || simple.equals("FluidStatusAccessor") || simple.equals("NoiseChunkStateInvoker"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.WorldgenSkip");
        if (simple.equals("NoiseInterpolatorAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.WorldgenSkip") || Boolean.getBoolean("lab.opt.WorldgenShare");
        if (simple.startsWith("WorldgenShare"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.WorldgenShare");
        if (simple.equals("WorldgenAllocPlacementMixin") || simple.equals("WorldgenAllocJointMixin"))   // FEATURES: not yet verified
            on = Boolean.getBoolean("lab.opt.WorldgenFeatureAlloc");
        if (simple.equals("WorldgenReactorMixin") || simple.startsWith("Reactor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.WorldgenReactor");
        if (simple.startsWith("MainReactor") || simple.equals("EventLoopQueueAccessor") || simple.equals("ChunkCacheExecutorAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.MainReactor");
        if (simple.startsWith("MainReactorScout")) on = Boolean.getBoolean("lab.reactor.scout");   // measurement only
        if (simple.equals("BulkDataArraysBlocksMixin"))   // 1.20.1: the block half of BulkDataArraysMixin (SRG names differ)
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.BulkDataArraysMixin");
        if (simple.startsWith("NetReactor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.NetReactor");
        // (1.21.1 turned NetReactorWaitMixin off with MainReactor, whose waitForTasks overwrite flushed; 1.20.1's MainReactor
        // only replaces BlockableEventLoop's park, so the flush before the wait stays this mixin's)
        if (simple.equals("StructurePlaceLockMixin") || simple.equals("StructurePaletteCacheMixin"))   // parallel generation's guards
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ParallelWorldgenMixin");
        if (simple.equals("LightRecipeVerifyMixin")) on = Boolean.getBoolean("lab.lightrecipe.verify");   // measurement only
        if (simple.startsWith("LightScout")) on = Boolean.getBoolean("lab.lightscout");   // measurement only
        if (simple.startsWith("GenScout")) on = Boolean.getBoolean("lab.genscout");   // measurement only
        if (simple.equals("LightTimingMixin")) on = Boolean.getBoolean("lab.lighttime");   // measurement only
        if (simple.startsWith("Scout") && simple.endsWith("Mixin"))   // measurement only, never with lab.opt.all
            on = Boolean.getBoolean("lab.scout");
        if (simple.equals("AsyncChunkSaveEncodeMixin") || simple.equals("AsyncChunkSaveStorageMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.AsyncChunkSaveMixin");
        if (simple.equals("ChunkTickMemoChunkMixin") || simple.equals("ChunkTickMemoEpochMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ChunkTickMemoMixin");
        if (simple.equals("BlockMeshDirtyMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.BlockMeshMemoMixin");
        if (simple.equals("PoseAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.PoseStackPoolMixin");
        if (simple.equals("AmbientChunksMixin"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.AmbientParticleSkipMixin");
        if (simple.equals("LevelNeighborUpdaterAccessor"))
            on = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.NeighborNotifySkipMixin");
        // both replace Util's pools: LabPools wins when both are requested
        if (on && simple.equals("PersistentPoolsMixin")
                && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.LabPoolsMixin"))) on = false;
        // A/B of the client-side ones with the server-side ones on: -Dlab.opt.noClient=true
        // an accessor / invoker mixin only opens fields and methods, it changes nothing: on whenever any part is,
        // since support code of several parts reaches it (EntityBatch.commutes from PartBatch and the render cache:
        // with EntityBatch off, or given way, loading its accessors crashed the first entity drawn)
        if (!on && sleep$accessorOnly(mixinClassName) && sleep$anyOn()) on = true;
        if (on && Boolean.getBoolean("lab.opt.noClient") && (simple.startsWith("Section") && simple.contains("Layer") || simple.startsWith("Ambient") || simple.startsWith("Shader") || simple.startsWith("ClientEventSite") || simple.startsWith("Pose") || simple.startsWith("BulkData") || simple.startsWith("VertexSort") || simple.startsWith("EntityBatch") || simple.startsWith("RenderCache") || simple.startsWith("Parallel") || simple.startsWith("Part") || simple.startsWith("ShaderStateShard") || simple.startsWith("MeshData") || simple.startsWith("SectionLists") || simple.startsWith("ClientBiome") || simple.startsWith("UniformDedupe") || simple.startsWith("SamplerUnit") || simple.startsWith("ModelPartQuat") || simple.startsWith("ModelPartIter") || simple.startsWith("CubicSampler") || simple.startsWith("OverworldFog") || simple.startsWith("EntityCullNoAlloc") || simple.startsWith("FrustumInvoker") || simple.startsWith("ItemFrameOffset") || simple.startsWith("ClientLight") || simple.startsWith("BlockMesh") || simple.startsWith("ClientLevelLightQueue") || simple.startsWith("EntityLightNoAlloc") || simple.startsWith("BlockEntityCullNoAlloc") || simple.startsWith("ClientChunkCacheBiome") || simple.startsWith("LevelRendererVisible") || simple.startsWith("EntityRender") || simple.startsWith("LevelRendererInvoker") || simple.startsWith("BufferBuilder") || simple.startsWith("BufferSource") || simple.startsWith("Composite") || simple.startsWith("Chunk") && !simple.startsWith("ChunkCache"))) on = false;
        if (on && simple.equals("CondWakeMixin")) {
            // tell() skips the wake-up unless the thread said it is parking: every wait for tasks must say so, also the
            // ones that override BlockableEventLoop.waitForTasks (ModernFix's MinecraftServer one). They are reached
            // only from the late extension; without it a skipped wake-up could cost a whole tick: CondWake stays off
            if (!dev.lull.optsupport.LateEventSites.register()) {
                System.out.println("[sleep/opt] CondWakeMixin off: overrides of waitForTasks cannot be hooked (Mixin's extensions out of reach)");
                return false;
            }
            if (!dev.lull.optsupport.OverrideHooks.any())
                dev.lull.optsupport.OverrideHooks.add("CondWake", "net/minecraft/util/thread/BlockableEventLoop", Set.of("waitForTasks", "m_5667_"), "()V",
                        "dev/lull/optsupport/WakeFlags.arm", "dev/lull/optsupport/WakeFlags.disarm");
        }
        if (on) {
            System.out.println("[sleep/opt] applying " + simple + " -> " + targetClassName);
        }
        return on;
    }

    private static Boolean anyOn;
    private static final java.util.Map<String, Boolean> ACCESSOR_ONLY = new java.util.concurrent.ConcurrentHashMap<>();

    /** some lab.opt flag is on (the engine is not in its vanilla mode) */
    private static boolean sleep$anyOn() {
        if (anyOn == null) {
            boolean any = false;
            for (String k : System.getProperties().stringPropertyNames())
                if (k.startsWith("lab.opt.") && "true".equals(System.getProperty(k)) && !k.equals("lab.opt.defaults")) any = true;
            anyOn = any;
        }
        return anyOn;
    }

    /** an interface mixin whose every method is an @Accessor or @Invoker */
    private static boolean sleep$accessorOnly(String mixinClassName) {
        return ACCESSOR_ONLY.computeIfAbsent(mixinClassName, n -> {
            try {
                ClassNode cn = org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(n);
                if ((cn.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) == 0 || cn.methods.isEmpty()) return false;
                for (org.objectweb.asm.tree.MethodNode m : cn.methods) {
                    boolean ok = false;
                    for (List<org.objectweb.asm.tree.AnnotationNode> l : java.util.Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations))
                        if (l != null) for (org.objectweb.asm.tree.AnnotationNode a : l)
                            if (a.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;") || a.desc.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")) ok = true;
                    if (!ok) return false;
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        });
    }

    /**
     * {@code -Dlab.opt.universal=true}: every universal, exactness-checked optimisation at once (not the sleep engine,
     * not the experimental worldgen/pool ones). Expanded into the individual flags before any of them is read.
     */
    static final String[] UNIVERSAL = {"TickMemoMixin", "ChunkCacheMixin", "EntitySectionScanMixin", "EntitySectionIndexMixin",
            "NeighborNotifySkipMixin", "ForgeBusMixin", "NeighborReactMixin", "TagCacheMixin", "BrainFlatMixin", "PoiColumnMixin", "EntityCollisionSkipMixin", "BlockCollisionMemoMixin", "EventSiteMixin", "NeighborQueueMixin", "SignalPathMixin", "CubeShapeMixin", "FluidSpreadMixin", "TickSchedulerMixin",
            // client side (applied on the physical client only): event sites, measured -22 % client tick time with the
            // client-thread collision memo (OwnerThread)
            "ClientEventSiteMixin",
            // sampler locations from updateLocations + sampler names made once: entity scene 64 -> 127 fps
            "ShaderSamplerCacheMixin",
            // translucent quad sort: same floats, stable radix sort (0 mismatches over 63 M quads): entity scene +6 %
            "VertexSortRadixMixin",
            // immediate draws batched per commuting render type (497 -> 12 draws per frame on the entity scene, frame
            // hash identical): +4 % in two pairs; the render types are judged by their state, so mod types too
            "EntityBatchMixin"};
    // client side, exact but no measurable fps effect (3 steady A/B pairs): opt-in only
    //   -Dlab.opt.SectionLayerMaskMixin / AmbientParticleSkipMixin

    @Override
    public void onLoad(String mixinPackage) {
        dev.lull.optsupport.LateEventSites.register();
        if (Boolean.getBoolean("lab.opt.universal")) {
            // -Dlab.opt.except=A,B: universal without those (A/B of one part)
            java.util.Set<String> except = new java.util.HashSet<>(java.util.Arrays.asList(System.getProperty("lab.opt.except", "").split(",")));
            // a flag set off (a launch flag, the settings screen's config/sleepium.properties) stays off
            for (String f : UNIVERSAL) if (!except.contains(f) && !"false".equals(System.getProperty("lab.opt." + f))) System.setProperty("lab.opt." + f, "true");
            System.out.println("[sleep/opt] lab.opt.universal: " + String.join(", ", UNIVERSAL));
        }
        // the worldgen reactor removes the worldgen actor; FEATURES of neighbouring chunks are then kept apart only by
        // ParallelGen's 3x3 claims (ParallelWorldgenMixin): without them two chunks' features write one section at once
        if (Boolean.getBoolean("lab.opt.WorldgenReactor") && !Boolean.getBoolean("lab.opt.ParallelWorldgenMixin") && !Boolean.getBoolean("lab.opt.all")) {
            System.setProperty("lab.opt.ParallelWorldgenMixin", "true");
            System.out.println("[sleep/opt] lab.opt.WorldgenReactor needs ParallelWorldgenMixin (its FEATURES claims): turned on");
        }
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if ((mixinClassName.endsWith(".EventSiteMixin") || mixinClassName.endsWith(".ClientEventSiteMixin"))
                && !dev.lull.optsupport.LateEventSites.mark(targetClass.name))   // (at the end of the class' transformation)
            dev.lull.optsupport.EventSiteTransformer.transform(targetClass, false, null);
        if (mixinClassName.endsWith(".WriteBarrierMixin"))
            dev.lull.optsupport.sleep.WriteBarrier.transform(targetClass);
        if (mixinClassName.endsWith(".WorldgenAllocAp2Mixin"))
            dev.lull.optsupport.ScratchArrays.transform(targetClass);
        if (mixinClassName.endsWith(".AllocTrimPackMixin"))
            dev.lull.optsupport.AllocTrim.transformPack(targetClass);
        if (mixinClassName.endsWith(".VertexSortRadixMixin"))
            dev.lull.optsupport.QuadSort.transform(targetClass);
    }
}
