package dev.lull.net;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Decoupled server, exact part 2 (notes/92, lab: {@code -Dlab.opt.StallHeartbeat=true}): players stay connected through
 * a long stall of the server thread.
 * <p>
 * Everything a vanilla server sends comes from its thread, so a tick that takes 30 s (a huge /fill, a mod's hitch, a
 * save of a big world on a slow disk) sends nothing for 30 s, and every client's read timeout (Connection's
 * ReadTimeoutHandler, 30 s) drops it: "Timed out". Here a daemon timer looks every {@link #CHECK_S} s; when the current
 * tick has lasted more than {@link #STALL_S} s, every player's connection gets a ping (ClientboundPingPacket - the
 * client answers with a pong, which the server ignores) from outside the server thread. The ping is not part of the
 * game: no world state reads or writes it; written from the network side it also flushes what the stalled tick had
 * already queued (vanilla holds a tick's packets unflushed until the tick's end).
 */
public final class StallHeartbeat {
    public static final boolean ON = Boolean.getBoolean("lab.opt.StallHeartbeat");
    static final int CHECK_S = 5, STALL_S = Integer.getInteger("lab.stallHeartbeat.afterS", 10);
    /** ping ids of ours: far from the ids other code uses (the configuration phase's, mods' transactions count up from 0) */
    private static final int ID = 0x51EE9000;
    public static final LongAdder PINGS = new LongAdder();
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static volatile long tickStart;   // 0: between ticks
    private static volatile MinecraftServer server;
    private static ScheduledExecutorService timer;

    private StallHeartbeat() {}

    /** server thread, tickServer HEAD */
    public static void tickStart(MinecraftServer s) {
        if (server == null) start(s);
        tickStart = System.nanoTime();
    }

    /** server thread, tickServer RETURN */
    public static void tickEnd() {
        tickStart = 0;
    }

    private static synchronized void start(MinecraftServer s) {
        if (timer != null) return;
        server = s;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Sleepium stall heartbeat");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(StallHeartbeat::check, CHECK_S, CHECK_S, TimeUnit.SECONDS);
    }

    private static void check() {
        long t = tickStart;
        MinecraftServer s = server;
        if (t == 0 || s == null || System.nanoTime() - t < STALL_S * 1_000_000_000L || s.getConnection() == null) return;
        try {
            var list = s.getConnection().getConnections();
            Connection[] cs;
            synchronized (list) {
                cs = list.toArray(new Connection[0]);
            }
            for (Connection c : cs) {
                if (c.isConnected() && !c.isMemoryConnection() && c.getPacketListener() instanceof ServerGamePacketListenerImpl) {
                    c.send(new ClientboundPingPacket(ID + (int) (PINGS.sum() & 0xfff)));
                    PINGS.increment();
                }
            }
            LOG.info("sleep: stall heartbeat - the tick has lasted " + (System.nanoTime() - t) / 1_000_000_000L + " s, players pinged");
        } catch (RuntimeException e) {
            LOG.info("sleep: stall heartbeat failed: " + e);
        }
    }
}
