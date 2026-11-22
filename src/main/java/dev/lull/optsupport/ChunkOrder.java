package dev.lull.optsupport;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Chunk loading order for moving players (notes/92-93): where the players are and where they are heading, for the
 * queues that pick which chunk's work comes next.
 * <ul>
 *   <li>{@code -Dlab.opt.GenNearFirst}: GenReactor (GenGraph's generation nodes) - within a ticket level, the node of
 *       the chunk nearest a player's position {@code lab.chunkOrder.aheadS} (1 s) ahead along its travel first, not the
 *       oldest;</li>
 *   <li>{@code -Dlab.opt.TicketNearFirst}: DistanceManager's player-ticket throttler - within its first level (the
 *       player-distance ring), the chunk nearest that point first, not the oldest;</li>
 *   <li>{@code -Dlab.opt.TicketReleaseAtFull}: a throttler slot is freed when its chunk is FULL, not entity-ticking.</li>
 * </ul>
 * Exact class: which chunk's work runs first among those queued at the same level is arrival order in vanilla - timing,
 * never a world input; every step still runs with exactly its dependencies done (GenGraph), every ticket is the same.
 * <p>
 * The snapshot is written once a tick on the server thread (ServerTickEvent.Post) and read by any thread: an array
 * replaced whole, never changed in place.
 */
public final class ChunkOrder {
    public static final boolean GEN = Boolean.getBoolean("lab.opt.GenNearFirst"), TICKET = Boolean.getBoolean("lab.opt.TicketNearFirst");
    public static final boolean ON = GEN || TICKET;
    private static final double AHEAD_S = Double.parseDouble(System.getProperty("lab.chunkOrder.aheadS", "1.0"));
    /** per player: target x, z (blocks) - the position aheadS along its smoothed velocity */
    private static volatile double[] targets = new double[0];
    private static double[] lastX = new double[0], lastZ = new double[0], vx = new double[0], vz = new double[0];
    private static java.util.UUID[] ids = new java.util.UUID[0];

    private ChunkOrder() {}

    /** server thread, end of a tick */
    public static void update(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        int n = players.size();
        if (ids.length != n) {
            ids = new java.util.UUID[n];
            lastX = new double[n];
            lastZ = new double[n];
            vx = new double[n];
            vz = new double[n];
        }
        long now = System.nanoTime();
        double[] t = new double[n * 2];
        for (int i = 0; i < n; i++) {
            ServerPlayer p = players.get(i);
            double x = p.getX(), z = p.getZ();
            if (!p.getUUID().equals(ids[i])) {   // a new player in this slot: no velocity yet
                ids[i] = p.getUUID();
                lastX[i] = x;
                lastZ[i] = z;
                vx[i] = vz[i] = 0;
            }
            double dt = lastTickNanos == 0 ? 0.05 : Math.max(0.001, (now - lastTickNanos) / 1e9);
            double ix = (x - lastX[i]) / dt, iz = (z - lastZ[i]) / dt;
            if (Math.abs(ix) + Math.abs(iz) > 400) ix = iz = 0;   // a teleport, not travel
            vx[i] += 0.2 * (ix - vx[i]);   // smoothed over ~5 ticks
            vz[i] += 0.2 * (iz - vz[i]);
            lastX[i] = x;
            lastZ[i] = z;
            t[2 * i] = x + vx[i] * AHEAD_S;
            t[2 * i + 1] = z + vz[i] * AHEAD_S;
        }
        lastTickNanos = now;
        targets = t;
    }

    private static long lastTickNanos;

    /** how far a chunk is from the nearest player's target point (squared blocks; lower comes first) */
    public static double score(long chunkPos) {
        double[] t = targets;
        if (t.length == 0) return 0;
        double cx = ((int) chunkPos << 4) + 8, cz = ((int) (chunkPos >>> 32) << 4) + 8, best = Double.MAX_VALUE;
        for (int i = 0; i < t.length; i += 2) {
            double dx = cx - t[i], dz = cz - t[i + 1], d = dx * dx + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }
}
