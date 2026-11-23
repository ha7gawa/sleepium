package dev.lull.optsupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * lab (notes/99, -Dlab.opt.TrackParMixin=true): the entity tracker's per-entity work (ChunkMap.tick's first loop) with
 * the sendChanges of every entity on the server pool. Vanilla, per tracked entity in the map's order: A) when the
 * entity changed section, updatePlayers (pairings: spawn packets, NeoForge's start-tracking event), remember players
 * that moved; B) when in ticking range (or moved), its ServerEntity.sendChanges (position, rotation, motion, synched
 * data, attributes, passengers - the entity's own state, broadcast to who sees it).
 * <p>Here: A for all entities on the server thread in the map's order, then B in parallel (item frames on the server
 * thread: map data is shared), every packet a send makes during A or B kept in the entity's own buffer; then the
 * buffers sent in the map's order, A's packets before B's per entity - each connection gets vanilla's sequence of
 * packets (an entity's A and B only read and write that entity's state and its ServerEntity; the order of packets
 * between entities is the map's order, as vanilla's loop gives it). The players that moved go to vanilla's second loop.
 */
public final class TrackPar {
    private TrackPar() {}

    public static final boolean ON = Boolean.getBoolean("lab.opt.TrackParMixin");
    static final int MIN = Integer.getInteger("lab.trackpar.min", 512);

    /** sends are being captured (some thread may be in a capture) */
    public static volatile boolean capturing;
    private static final ThreadLocal<int[]> CUR = ThreadLocal.withInitial(() -> new int[] {-1});
    private static Object[] tracked = new Object[4096];
    private static boolean[] send = new boolean[4096], serial = new boolean[4096];
    /** per entity: captured sends (listener, packet, send listener) */
    private static Object[][] cap = new Object[4096][];
    private static int[] capN = new int[4096];
    private static final List<ServerPlayer> moved = new ArrayList<>();
    static long ticks, entities, parallelSends, packets, nsA, nsB, nsEmit, unseenSkips;

    /**
     * ChunkMap.tick's first loop over entityMap.values(): done here when worth it (true: vanilla's loop gets nothing to
     * walk), else false (vanilla's loop as it is).
     */
    public static boolean run(Collection<?> values, ServerLevel level, net.minecraft.server.level.DistanceManager dm0) {
        final net.minecraft.server.level.DistanceManager dm = dm0;
        int n = values.size();
        moved.clear();
        if (!ON || n < MIN) return false;
        if (tracked.length < n) {
            int len = Math.max(n, tracked.length * 2);
            tracked = new Object[len];
            send = new boolean[len];
            serial = new boolean[len];
            cap = java.util.Arrays.copyOf(cap, len);
            capN = java.util.Arrays.copyOf(capN, len);
        }
        int k = 0;
        for (Object t : values) tracked[k++] = t;
        ticks++;
        entities += n;
        List<ServerPlayer> players = level.players();
        int[] cur = CUR.get();
        long t0 = System.nanoTime();
        capturing = true;
        try {
            // A: which entities changed section (in parallel: reads only), then their pairings in the map's order
            var pool0 = ServerReactor.pool();
            pool0.forRange(n, 256, pool0.workerCount(), i -> {
                dev.lull.opt.TrackParEntityAccessor t = (dev.lull.opt.TrackParEntityAccessor) tracked[i];
                send[i] = !java.util.Objects.equals(t.sleep$lastSection(), SectionPos.of(t.sleep$entity()));
                serial[i] = t.sleep$entity() instanceof net.minecraft.world.entity.decoration.ItemFrame;
            });
            for (int i = 0; i < n; i++) {
                if (!send[i]) continue;
                dev.lull.opt.TrackParEntityAccessor t = (dev.lull.opt.TrackParEntityAccessor) tracked[i];
                SectionPos now = SectionPos.of(t.sleep$entity());
                cur[0] = i;
                try {
                    t.sleep$updatePlayers(players);
                } finally {
                    cur[0] = -1;
                }
                if (t.sleep$entity() instanceof ServerPlayer p) moved.add(p);
                t.sleep$setLastSection(now);
            }
            long t1 = System.nanoTime();
            nsA += t1 - t0;
            // B: sendChanges, in parallel (item frames on the server thread, in order, after)
            var pool = ServerReactor.pool();
            pool.forRange(n, 64, pool.workerCount(), i -> {
                if (!send[i]) {
                    dev.lull.opt.TrackParEntityAccessor t = (dev.lull.opt.TrackParEntityAccessor) tracked[i];
                    send[i] = dm.inEntityTickingRange(t.sleep$lastSection().chunk().toLong());   // (lastSection = its section now)
                }
                if (!send[i] || serial[i]) return;
                // lab.fast: nobody sees it - no sendChanges (its packets would go to no one; a new viewer's pairing sends
                // the whole entity, and the next sendChanges moves it from the last base it was sent from)
                if (SpecTicks.FAST && ((dev.lull.opt.TrackParEntityAccessor) tracked[i]).sleep$seenBy().isEmpty()) {
                    unseenSkips++;
                    return;
                }
                int[] c = CUR.get();
                c[0] = i;
                try {
                    ((dev.lull.opt.TrackParEntityAccessor) tracked[i]).sleep$serverEntity().sendChanges();
                } finally {
                    c[0] = -1;
                }
            });
            for (int i = 0; i < n; i++) {
                if (!send[i] || !serial[i]) continue;
                cur[0] = i;
                try {
                    ((dev.lull.opt.TrackParEntityAccessor) tracked[i]).sleep$serverEntity().sendChanges();
                } finally {
                    cur[0] = -1;
                }
            }
            long t2 = System.nanoTime();
            nsB += t2 - t1;
            parallelSends += n;
        } finally {
            capturing = false;
        }
        // the packets, in the map's order
        long t2 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            int m = capN[i];
            if (m == 0) {
                tracked[i] = null;
                continue;
            }
            Object[] b = cap[i];
            for (int q = 0; q < m; q += 3) {
                packets++;
                ((net.minecraft.server.network.ServerCommonPacketListenerImpl) b[q]).send((Packet<?>) b[q + 1], (PacketSendListener) b[q + 2]);
                b[q] = b[q + 1] = b[q + 2] = null;
            }
            capN[i] = 0;
            tracked[i] = null;
        }
        nsEmit += System.nanoTime() - t2;
        if (ticks % 100 == 0) report();
        return true;
    }

    /** the players whose entity changed section in run (for vanilla's second loop) */
    public static List<ServerPlayer> moved() {
        return moved;
    }

    /** ServerCommonPacketListenerImpl.send during a capture on this thread: kept for later (true = taken) */
    public static boolean capture(Object listener, Packet<?> packet, PacketSendListener sl) {
        int i = CUR.get()[0];
        if (i < 0) return false;
        Object[] b = cap[i];
        int m = capN[i];
        if (b == null) cap[i] = b = new Object[12];
        else if (m + 3 > b.length) cap[i] = b = java.util.Arrays.copyOf(b, b.length * 2);
        b[m] = listener;
        b[m + 1] = packet;
        b[m + 2] = sl;
        capN[i] = m + 3;
        return true;
    }

    static void report() {
        System.out.println(String.format(java.util.Locale.ROOT, "[sleep/trackpar] ticks %d, entities %.0f a tick; ms a tick: pairings %.2f, sendChanges %.2f, packets out %.2f; packets %d; unseen skipped %.0f a tick",
                ticks, entities / (double) ticks, nsA / 1e6 / ticks, nsB / 1e6 / ticks, nsEmit / 1e6 / ticks, packets, unseenSkips / (double) ticks));
    }
}
