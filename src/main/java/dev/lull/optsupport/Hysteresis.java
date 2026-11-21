package dev.lull.optsupport;

import net.minecraft.world.level.ChunkPos;

/**
 * -Dlab.hysteresis=K (11-18): the view edge with hysteresis - a chunk comes in at the view distance R, goes out beyond
 * R + K. Vanilla's edge has one threshold: flying a 1.5-chunk circle at distance 32 resent 85 % of the chunks the client
 * received (forgotten and sent again within seconds: unload, save, load, packet, light, mesh again).
 *
 * <p>Each player gets an anchor chunk on the server: it moves to the player's chunk only when the player is more than K
 * chunks (euclidean) from it. The player's tracking view is the disc of R + K around the anchor (vanilla's shape and
 * edge rule), and the player loading tickets are centred on the anchors with R + K (DistanceManager's player ticket
 * tracker): a chunk leaving R by less than K is neither forgotten by the client nor demoted on the server (a demoted
 * chunk is sent again when it becomes ticking-ready). Spawning and simulation distance stay on the player. The client's
 * cache centre is the anchor (the centre packet carries the view's centre); vanilla's client keeps R + 3 around it, so
 * K <= 2 needs nothing on the client; a larger K widens our client's storage (ClientStorageHystMixin).
 *
 * <p>Not exact to vanilla on the server: chunks in the band stay loaded (not ticking: beyond simulation distance) longer
 * than vanilla keeps them, and up to K more ahead are loaded. What is drawn is the client's own view distance as before.
 */
public final class Hysteresis {
    private Hysteresis() {}

    public static final int K = Integer.getInteger("lab.hysteresis", 0);
    public static final boolean ON = K > 0;
    public static long reanchors, kept;

    /** DistanceManager (DistanceManagerHystMixin): player ticket sources at anchors */
    public interface Anchors {
        void sleep$anchorAdd(long chunk);

        void sleep$anchorRemove(long chunk);

        boolean sleep$hasAnchor(long chunk);
    }

    /** the anchor after the player moved to now (old: null for none) */
    public static ChunkPos anchor(ChunkPos old, ChunkPos now) {
        if (old == null) return now;
        int dx = now.x - old.x, dz = now.z - old.z;
        if (dx * dx + dz * dz > K * K) {
            reanchors++;
            return now;
        }
        kept++;
        return old;
    }

    public static String report() {
        return "hysteresis K=" + K + ": anchors moved " + reanchors + ", kept " + kept;
    }
}
