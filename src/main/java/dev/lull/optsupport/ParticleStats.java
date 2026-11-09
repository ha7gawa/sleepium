package dev.lull.optsupport;

import net.minecraft.client.particle.ParticleEngine;

/** The particle tick measured (ParticleShapeMemoMixin): its time per tick and the particles alive, for the client report. */
public final class ParticleStats {
    private static long t0, ticks, nanos, maxNanos, particles, maxParticles;

    private ParticleStats() {}

    public static void start() {
        t0 = System.nanoTime();
    }

    public static void end(ParticleEngine engine) {
        long d = System.nanoTime() - t0;
        ticks++;
        nanos += d;
        if (d > maxNanos) maxNanos = d;
        int n = 0;
        for (var q : ((dev.lull.opt.ParticleEngineAccessor) engine).sleep$particles().values()) n += q.size();
        particles += n;
        if (n > maxParticles) maxParticles = n;
    }

    public static String report() {
        return ticks == 0 ? "particle tick: none" : String.format(java.util.Locale.ROOT, "particle tick: %d ticks, %.2f ms average, %.2f ms max, particles %d average, %d max",
                ticks, nanos / 1e6 / ticks, maxNanos / 1e6, particles / ticks, maxParticles);
    }
}
