package dev.lull.optsupport;

import net.minecraft.client.particle.ParticleEngine;

/** The particle tick measured (ParticleShapeMemoMixin): its time per tick and the particles alive, for the client report. */
public final class ParticleStats {
    private static long t0, ticks, nanos, maxNanos, particles, maxParticles, r0, renders, renderNanos;

    private ParticleStats() {}

    public static void start() {
        t0 = System.nanoTime();
    }

    /** the particle ticks' time since the frame log last took it (ClientMeter's frame log) */
    public static long frameNanos;

    public static void end(ParticleEngine engine) {
        long d = System.nanoTime() - t0;
        frameNanos += d;
        ticks++;
        nanos += d;
        if (d > maxNanos) maxNanos = d;
        int n = 0;
        for (var q : ((dev.lull.opt.ParticleEngineAccessor) engine).sleep$particles().values()) n += q.size();
        particles += n;
        if (n > maxParticles) maxParticles = n;
    }

    public static void renderStart() {
        r0 = System.nanoTime();
    }

    public static void renderEnd() {
        renders++;
        renderNanos += System.nanoTime() - r0;
    }

    public static String report() {
        return ticks == 0 ? "particle tick: none" : String.format(java.util.Locale.ROOT, "particle tick: %d ticks, %.2f ms average, %.2f ms max, particles %d average, %d max; particle render: %d calls, %.3f ms average",
                ticks, nanos / 1e6 / ticks, maxNanos / 1e6, particles / ticks, maxParticles, renders, renders == 0 ? 0 : renderNanos / 1e6 / renders);
    }
}
