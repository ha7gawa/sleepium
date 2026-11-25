package dev.lull.optsupport;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * exp/frame-garbage (client): per-object checks of every frame without their temporary objects, where the code that
 * would run is known to be the base implementation (asked once per class by reflection: an override anywhere below
 * falls back to vanilla's call).
 * <ul>
 *   <li>EntityRenderer.getPackedLightCoords: the light probe (Entity.getLightProbePosition = the eye position) and the
 *       two light levels (EntityRenderer.getBlockLightLevel / getSkyLightLevel) computed on primitives with one mutable
 *       position (getBrightness does not keep it) instead of a Vec3 and a BlockPos per entity;</li>
 *   <li>the block-entity frustum test: the default render box new AABB(pos) tested as coordinates.</li>
 * </ul>
 */
public final class NoAllocChecks {
    private NoAllocChecks() {}

    private static boolean declaredIn(Class<?> c, Class<?> base, String name, Class<?>... params) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                dev.lull.Names.methodOf(k, name, params);
                return k == base;
            } catch (NoSuchMethodException e) {
                // keep looking up
            }
        }
        return false;
    }

    private static final ClassValue<Boolean> PROBE_BASE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return declaredIn(c, Entity.class, "getLightProbePosition", float.class);
        }
    };

    private static final ClassValue<Boolean> LIGHT_BASE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return declaredIn(c, EntityRenderer.class, "getBlockLightLevel", Entity.class, BlockPos.class)
                    && declaredIn(c, EntityRenderer.class, "getSkyLightLevel", Entity.class, BlockPos.class);
        }
    };

    private static final ClassValue<Boolean> BOX_DEFAULT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            // the default lives in the interface: no class of the chain declares getRenderBoundingBox
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
                for (var m : k.getDeclaredMethods()) if (dev.lull.Names.officialMember(m.getName()).equals("getRenderBoundingBox") && !m.isBridge()) return false;
            return true;
        }
    };

    private static final ThreadLocal<BlockPos.MutableBlockPos> POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final BlockPos.MutableBlockPos RENDER_POS = new BlockPos.MutableBlockPos();
    public static long lightFast, lightVanilla, boxFast, boxVanilla;

    /** -1 if vanilla's path must run (an override somewhere), else the packed light. */
    public static int packedLight(EntityRenderer<?> renderer, Entity e, float pt) {
        if (!PROBE_BASE.get(e.getClass()) || !LIGHT_BASE.get(renderer.getClass())) {
            lightVanilla++;
            return -1;
        }
        double x = Mth.lerp((double) pt, e.xo, e.getX());
        double y = Mth.lerp((double) pt, e.yo, e.getY()) + (double) e.getEyeHeight();
        double z = Mth.lerp((double) pt, e.zo, e.getZ());
        BlockPos.MutableBlockPos p = RenderSystem.isOnRenderThread() ? RENDER_POS : POS.get();
        p.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        int block = e.isOnFire() ? 15 : e.level().getBrightness(LightLayer.BLOCK, p);
        int sky = e.level().getBrightness(LightLayer.SKY, p);
        lightFast++;
        return LightTexture.pack(block, sky);
    }

    /** Block entities whose draw lives in VRAM (KineticResident): asked first in renderLevel's section loop. */
    public interface Resident {
        /** true: be's parts are queued for this frame (the dispatcher is skipped) */
        boolean drawn(BlockEntity be);
    }

    public static volatile Resident RESIDENT;

    /** ClientHooks.isBlockEntityRendererVisible, the default box as coordinates. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean blockEntityVisible(BlockEntityRenderDispatcher dispatcher, BlockEntity be, Frustum frustum) {
        Resident res = RESIDENT;
        if (res != null && res.drawn(be)) return false;   // drawn from VRAM already: not through the dispatcher
        BlockEntityRenderer renderer = dispatcher.getRenderer(be);
        if (renderer == null) return false;
        if (!BOX_DEFAULT.get(renderer.getClass())) {
            boxVanilla++;
            return frustum.isVisible(renderer.getRenderBoundingBox(be));
        }
        BlockPos pos = be.getBlockPos();
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        boxFast++;
        // new AABB(pos) = (x, y, z, x + 1, y + 1, z + 1): finite, so isVisible is the cube test
        return ((dev.lull.opt.FrustumInvoker) frustum).sleep$cubeInFrustum(x, y, z, x + 1.0, y + 1.0, z + 1.0);
    }
}
