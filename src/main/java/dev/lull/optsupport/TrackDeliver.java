package dev.lull.optsupport;

import dev.lull.net.NetReactor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;

/**
 * The entity tracker's packets delivered per connection on the server pool ({@code -Dlab.opt.TrackDeliver=true},
 * opt/TrackDeliver*Mixin; needs NetReactor). ChunkMap.tick() (the tracker: sendChanges of every tracked entity, pairing
 * for players that moved) sends 31-35 k packets a tick at 100 players in a mob scene; each send is ~150 ns on the server
 * thread, mostly the connection's own work (Connection.send: flushQueue, NetReactor's append under the connection's
 * lock) on 100 different connection objects. Here, while the tracker runs, a server-thread send only records
 * (listener, packet, send listener, flush flag) in a log - after NeoForge's checkPacket, which stays where vanilla
 * runs it; a terminal packet empties the log first and goes as vanilla's. At the tracker's end the log is delivered:
 * every connection's entries in log order, by one thread per connection (worker w takes the connections whose slot
 * % W == w), through the same steps the server thread takes for a send inside the tick (Connection.send's
 * isConnected / flushQueue, sentPackets, NetReactor's batch append and its early flush at MAX_BATCH); the connections
 * that got a first packet are marked dirty on the server thread after the join. Few entries or one connection: the
 * server thread delivers them itself, in order.
 * <p>
 * Why the same bytes: a connection's byte stream is a function of the sequence of packets appended to its batch
 * (each packet is encoded and compressed on its own, the cipher runs over the stream in order). That sequence here is
 * the vanilla call order filtered to the connection - the log is that order, one thread delivers a connection's entries
 * in it. What moves is only the moment of the appends (the tracker's end instead of during it) and the order in which
 * different connections are marked dirty (which connection's batch the flush schedules first). A send from another
 * thread during the tracker (a netty thread answering) is not logged: it lands where its own timing puts it, as in
 * vanilla.
 * {@code -Dlab.opt.TrackDeliverVerify=true}: after each delivery every connection's batch must end with exactly its
 * logged packets in log order (a batch drained early by the 256-packet flush is counted as not checkable).
 */
public final class TrackDeliver {
    public static final boolean ON = Boolean.getBoolean("lab.opt.TrackDeliver") && NetReactor.ENABLED;
    public static final boolean VERIFY = Boolean.getBoolean("lab.opt.TrackDeliverVerify");
    private static final int HELPERS = Integer.getInteger("lab.opt.TrackDeliverHelpers", 4);
    private static final int MIN_PARALLEL = Integer.getInteger("lab.opt.TrackDeliverMin", 2048);
    /** /sleep bots ab trackdeliver on|off */
    public static volatile boolean active = !Boolean.getBoolean("lab.opt.TrackDeliverOff");   // (Off: registered for the A/B switches, capturing off)

    /** the listener's side (ServerCommonPacketListenerImpl mixin) */
    public interface Sender {
        /** this tick's slot (a dense index per connection that got a logged packet) */
        int sleep$slot(int gen);

        /** vanilla's send after checkPacket and the terminal test, with the flush flag the server thread computed; dirty: its first batched packet */
        boolean sleep$deliver(Packet<?> packet, PacketSendListener listener, boolean flush);

        /** its connection's batch side (NetReactor) */
        NetReactor.Batched sleep$batched();
    }

    /** server thread, inside ChunkMap.tick() */
    public static boolean capturing;
    public static Thread serverThread;
    private static int gen, n, slots;
    private static Sender[] lis = new Sender[4096];
    private static Packet<?>[] pk = new Packet<?>[4096];
    private static PacketSendListener[] sl = new PacketSendListener[4096];
    private static boolean[] fl = new boolean[4096];
    private static int[] slot = new int[4096];
    private static Sender[] bySlot = new Sender[256];
    private static final ArrayList<ArrayList<Sender>> DIRTY = new ArrayList<>();

    public static long ticks, logged, parallelRuns, serialRuns, deliverNs, checked, mismatches, uncheckable;

    private TrackDeliver() {}

    public static void register() {
        dev.lull.bots.BotLoad.AB.put("trackdeliver", v -> active = v);
        // NetReactor's early flush at a full batch (256 packets): off = 256, on = 4096 (measurement, 11-22)
        dev.lull.bots.BotLoad.AB.put("maxbatch", v -> NetReactor.MAX_BATCH = v ? 4096 : 256);
        dev.lull.bots.BotLoad.AB.put("flushlater", v -> NetReactor.flushLater = v);
        dev.lull.bots.BotLoad.WINDOWS.add(new dev.lull.bots.BotLoad.Window() {
            public void start() {
                ticks = logged = parallelRuns = serialRuns = deliverNs = 0;
            }

            public String end(long t) {
                return String.format(Locale.ROOT, "trackdeliver: %.0f packets logged a tracker tick, %d parallel / %d serial deliveries, deliver %.2f ms/tick%s",
                        ticks == 0 ? 0.0 : logged / (double) ticks, parallelRuns, serialRuns, t == 0 ? 0.0 : deliverNs / 1e6 / t,
                        VERIFY ? String.format(Locale.ROOT, "; verified %d connection-deliveries, mismatches %d, not checkable %d", checked, mismatches, uncheckable) : "");
            }
        });
    }

    /** ChunkMap.tick() HEAD */
    public static void start() {
        if (capturing) finish();   // a tracker that threw: deliver what it logged first
        if (!active) return;
        serverThread = Thread.currentThread();
        gen++;
        n = 0;
        slots = 0;
        capturing = true;
    }

    /** a logged send (server thread, capturing) */
    public static void capture(Sender s, Packet<?> packet, PacketSendListener listener, boolean flush) {
        if (n == pk.length) grow();
        int sl0 = s.sleep$slot(gen);
        lis[n] = s;
        pk[n] = packet;
        sl[n] = listener;
        fl[n] = flush;
        slot[n] = sl0;
        n++;
    }

    /** a new slot for a connection this tick */
    public static int newSlot(Sender s) {
        if (slots == bySlot.length) bySlot = Arrays.copyOf(bySlot, slots * 2);
        bySlot[slots] = s;
        return slots++;
    }

    private static void grow() {
        int m = pk.length * 2;
        lis = Arrays.copyOf(lis, m);
        pk = Arrays.copyOf(pk, m);
        sl = Arrays.copyOf(sl, m);
        fl = Arrays.copyOf(fl, m);
        slot = Arrays.copyOf(slot, m);
    }

    /** a terminal packet mid-tracker: what was logged goes first, in order (server thread) */
    public static void drainSerial() {
        boolean c = capturing;
        capturing = false;
        deliverAll(true);
        n = 0;
        capturing = c;
    }

    /** ChunkMap.tick() RETURN */
    public static void finish() {
        if (!capturing) return;
        capturing = false;
        long t0 = System.nanoTime();
        ticks++;
        logged += n;
        deliverAll(n < MIN_PARALLEL || slots < 2);
        deliverNs += System.nanoTime() - t0;
        Arrays.fill(lis, 0, n, null);
        Arrays.fill(pk, 0, n, null);
        Arrays.fill(sl, 0, n, null);
        Arrays.fill(bySlot, 0, slots, null);
        n = 0;
    }

    private static void deliverAll(boolean serial) {
        final int count = n;
        if (count == 0) return;
        int[] tailFrom = null;
        int[] perSlot = null;
        if (VERIFY) {
            perSlot = new int[slots];
            for (int i = 0; i < count; i++) perSlot[slot[i]]++;
        }
        if (serial) {
            serialRuns++;
            for (int i = 0; i < count; i++)
                if (lis[i].sleep$deliver(pk[i], sl[i], fl[i])) NetReactor.markDirty(lis[i].sleep$batched());
        } else {
            parallelRuns++;
            final int w = Math.min(HELPERS + 1, slots);
            while (DIRTY.size() < w) DIRTY.add(new ArrayList<>());
            final Sender[] l = lis;
            final Packet<?>[] p = pk;
            final PacketSendListener[] s = sl;
            final boolean[] f = fl;
            final int[] sn = slot;
            ServerReactor.pool().forRange(w, 1, w - 1, k -> {
                ArrayList<Sender> dirty = DIRTY.get(k);
                for (int i = 0; i < count; i++)
                    if (sn[i] % w == k && l[i].sleep$deliver(p[i], s[i], f[i])) dirty.add(l[i]);
            });
            for (int k = 0; k < w; k++) {
                ArrayList<Sender> dirty = DIRTY.get(k);
                for (int i = 0; i < dirty.size(); i++) NetReactor.markDirty(dirty.get(i).sleep$batched());
                dirty.clear();
            }
        }
        if (VERIFY) verify(count, perSlot);
    }

    private static void verify(int count, int[] perSlot) {
        for (int k = 0; k < slots; k++) {
            Object[] tail = bySlot[k].sleep$batched().sleep$batchTail(perSlot[k]);
            if (tail == null) {
                uncheckable++;
                continue;
            }
            checked++;
            int j = 0;
            for (int i = 0; i < count && j < tail.length; i++) {
                if (slot[i] != k) continue;
                if (tail[j++] != pk[i]) {
                    mismatches++;
                    break;
                }
            }
        }
    }
}
