package dev.lull.optsupport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;

/** ParticleCullNoAllocMixin: which particle classes keep SingleQuadParticle's render box, and the box's test without it. */
public final class ParticleCull {
    private ParticleCull() {}

    /** markers handed from the box call to the isVisible call (never tested as boxes) */
    public static final AABB SEEN = new AABB(0, 0, 0, 0, 0, 0), UNSEEN = new AABB(0, 0, 0, 0, 0, 0);
    private static final Map<Class<?>, Boolean> PLAIN = new ConcurrentHashMap<>();
    private static final boolean TOUCHED = MixinTouch.touched("net/minecraft/client/particle/SingleQuadParticle", "getRenderBoundingBox");

    /** the particle's class resolves getRenderBoundingBox(F) to SingleQuadParticle's, which no other mod's mixin changes */
    public static boolean plain(Particle p) {
        if (TOUCHED || !(p instanceof SingleQuadParticle)) return false;
        return PLAIN.computeIfAbsent(p.getClass(), c -> {
            try {
                return c.getMethod("getRenderBoundingBox", float.class).getDeclaringClass() == SingleQuadParticle.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        });
    }

    /** SingleQuadParticle.getRenderBoundingBox's box (new AABB(x - s, ..., x + s, ...)) through Frustum.isVisible, without the box */
    public static boolean visible(SingleQuadParticle p, float partialTicks, Frustum frustum) {
        double s = p.getQuadSize(partialTicks);
        ParticleAccess a = (ParticleAccess) (Object) p;
        double x = a.sleep$x(), y = a.sleep$y(), z = a.sleep$z();
        double x1 = x - s, y1 = y - s, z1 = z - s, x2 = x + s, y2 = y + s, z2 = z + s;
        double minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
        double maxX = Math.max(x1, x2), maxY = Math.max(y1, y2), maxZ = Math.max(z1, z2);
        if (Double.isInfinite(minX) && Double.isInfinite(minY) && Double.isInfinite(minZ) && Double.isInfinite(maxX) && Double.isInfinite(maxY)
                && Double.isInfinite(maxZ)) return true;   // isVisible's early exit for infinite bounds
        return ((dev.lull.opt.FrustumInvoker) frustum).sleep$cubeInFrustum(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** the particle's position (Particle.x / y / z are protected) */
    public interface ParticleAccess {
        double sleep$x();

        double sleep$y();

        double sleep$z();
    }
}
