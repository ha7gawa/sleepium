package dev.lull.net;

import java.util.ArrayList;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;

/**
 * Network reactor, stage A: outbound batching. Vanilla sends every packet from the server thread as its own netty task
 * with its own flush — a task (a lambda, a queue node, often a selector wake-up) and a socket write per packet. Here a
 * server-thread send in the PLAY protocol made inside the server tick is appended to its connection's batch instead, and
 * the batch goes out as ONE netty task that writes every packet and flushes once, at the reactor's flush points:
 * <ul>
 *   <li>the end of the server tick and the connection's own tick,</li>
 *   <li>before the server thread sleeps (waitForTasks: idle phase or a blocking wait) — the answers to the packets it
 *       just handled leave before it waits for more,</li>
 *   <li>a batch of {@link #MAX_BATCH} packets (bounds memory and latency in bursts),</li>
 *   <li>before a protocol switch, a disconnect or an explicit flush.</li>
 * </ul>
 * Same packets, same order per connection, same bytes; only the moment of the socket write moves, to the end of the
 * server thread's current burst of work. A send from another thread goes into the batch too while one is pending (so
 * it cannot overtake it), and straight to netty as in vanilla otherwise.
 * <p>
 * Only sends inside MinecraftServer.tickServer are batched: its end is the one flush point that always comes. Outside
 * it nothing is guaranteed - a paused singleplayer server runs no tickServer (IntegratedServer.tickPaused), and a mod
 * may override the server's waitForTasks (ModernFix fix_loop_spin_waiting) so the sleep flush never runs; a batch made
 * there waited until the game unpaused (ALI's loot data held through JEI's start, 11-07). So a server-thread send
 * between ticks goes out as from any other thread.
 */
public final class NetReactor {
    public static final boolean ENABLED = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.NetReactor");
    public static final int MAX_BATCH = Integer.getInteger("lab.net.maxBatch", 256);

    /**
     * -Dlab.opt.NetFlushLater=true (lab; 1.21.1 lab-pipe2 68df7d52, notes/91): inside the server tick a batch is not handed
     * to netty at 256 packets nor at its connection's tick - all of them at the tick's end - and the hand-over itself (one
     * netty task per connection: an event loop wake-up) runs on a server pool thread, not the server thread. Same packets
     * in the same order per connection; only the moment the drains are scheduled moves. NB 1.20.1's vanilla flushes every
     * packet as it is sent (no suspended flushing in 1.20.1): here the tick's packets leave at its end, later than
     * vanilla's - a latency question measured before any default (1.21.1: vanilla's own flush point is the tick's end).
     */
    public static volatile boolean flushLater = Boolean.getBoolean("lab.opt.NetFlushLater");
    public static final LongAdder HANDOFFS = new LongAdder();

    /** /sleep bots ab flushlater on|off (lab A/B) */
    public static void registerAb() {
        dev.lull.bots.BotLoad.AB.put("flushlater", v -> flushLater = v);
    }
    public static final LongAdder BATCHED = new LongAdder(), OTHER_THREAD = new LongAdder(), DRAINS = new LongAdder(), EARLY = new LongAdder();

    /** a batched packet with a send listener (rare: most sends have none) */
    public record WithListener(Packet<?> packet, PacketSendListener listener) {}

    /** a connection that batches (the Connection mixin) */
    public interface Batched {
        /** schedule the pending batch, if any (any thread) */
        void sleep$flush();
    }

    private static volatile Thread serverThread;
    /** inside MinecraftServer.tickServer (server thread only) */
    private static boolean ticking;
    /** connections with a pending batch not yet scheduled (server thread only) */
    private static final ArrayList<Batched> DIRTY = new ArrayList<>();

    private NetReactor() {}

    public static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }

    /** a send here may wait in its connection's batch: the server thread inside tickServer, whose end flushes */
    public static boolean batching() {
        return ticking && Thread.currentThread() == serverThread;
    }

    /** the start of a server tick (MinecraftServer.tickServer) */
    public static void onTickStart() {
        serverThread = Thread.currentThread();
        ticking = true;
    }

    /** server thread: its connection got a first packet since the last flush */
    public static void markDirty(Batched c) {
        DIRTY.add(c);
    }

    /** server thread, a flush point: every pending batch goes to netty */
    public static void flushAll() {
        if (flushLater && DIRTY.size() > 1) {   // the wake-ups on a pool thread (dev.lull.optsupport.ServerReactor)
            Batched[] all = DIRTY.toArray(new Batched[0]);
            DIRTY.clear();
            HANDOFFS.increment();
            // the TICK lane: ahead of the pool's queued chunk-save encodes (CHUNK lane) - an autosave's backlog must not
            // hold a tick's packets back
            dev.lull.optsupport.ServerReactor.pool().execute(() -> {
                for (Batched b : all) b.sleep$flush();
            }, dev.lab.runtime.LabRuntime.Lane.TICK);
            return;
        }
        for (int i = 0; i < DIRTY.size(); i++) DIRTY.get(i).sleep$flush();
        DIRTY.clear();
    }

    /**
     * server thread, the end of MinecraftServer.tickChildren (levels, connections, players, tickables), before the
     * autosave and Forge's post-tick event (mods' end-of-tick work): with NetFlushLater the tick's batches leave here,
     * not after those (sends made after it still go at the tick's end)
     */
    public static void onChildrenEnd() {
        if (flushLater && batching()) flushAll();
    }

    /** the end of a server tick (MinecraftServer.tickServer) */
    public static void onServerTick() {
        serverThread = Thread.currentThread();
        flushAll();
        ticking = false;
    }

    /** before the server thread sleeps */
    public static void beforeSleep() {
        if (ENABLED && Thread.currentThread() == serverThread) flushAll();
    }

    public static String report() {
        long b = BATCHED.sumThenReset(), d = DRAINS.sumThenReset();
        return String.format(java.util.Locale.ROOT, "net reactor: %d packets batched into %d netty tasks (%.1f per task, %d early), %d from other threads",
                b, d, d == 0 ? 0 : (double) b / d, EARLY.sumThenReset(), OTHER_THREAD.sumThenReset());
    }
}
