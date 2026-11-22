package dev.lull.net;

import java.util.concurrent.atomic.LongAdder;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Decoupled server, exact part 1 (notes/92, lab: {@code -Dlab.opt.EarlyAnswers=true}): a player's block action is
 * answered when it was handled, not one tick later.
 * <p>
 * Vanilla handles the players' packets between ticks, but keeps a block action's acknowledgement
 * (ClientboundBlockChangedAck) for the connection's tick in the NEXT server tick - so it leaves after the block
 * updates that tick broadcasts (ChunkHolder.broadcastChanges in the chunk source's tick): the client, on the
 * acknowledgement, replaces its predicted blocks with the server states it has heard of, and an acknowledgement ahead
 * of the updates would flash the old block back. Measured (notes/92): at 100 ms ticks the answer comes 1.5 ticks after
 * the action (160 ms), at 20 tps ~15 ms.
 * <p>
 * Here, at the hand-over points between ticks (before the next tick starts, before the server thread sleeps), every
 * player with an acknowledgement pending gets the block changes of the chunks within one chunk of it (reach is under
 * 16 blocks: every block the client predicted is there) broadcast as the tick would, then the acknowledgement - same
 * packets, same order (updates, then the acknowledgement), one tick earlier. Nothing in the world changes: the
 * broadcast only reads and sends (the tick's own broadcast then finds those sections already sent); the clients see
 * the changes made since the last broadcast sooner.
 */
public final class EarlyAnswers {
    public static final boolean ON = Boolean.getBoolean("lab.opt.EarlyAnswers");
    private static final boolean ACK_ONLY = Boolean.getBoolean("lab.earlyAnswers.ackOnly");
    public static final LongAdder ANSWERED = new LongAdder(), BEFORE_TICK = new LongAdder(), NANOS = new LongAdder();
    /** the runtime switch of interleaved A/B windows (BotLoad: /sleep bots ab earlyAnswers on|off) */
    public static volatile boolean active = true;

    static {
        if (ON) {
            dev.lull.bots.BotLoad.AB.put("earlyAnswers", v -> active = v);
            dev.lull.bots.BotLoad.WINDOWS.add(new dev.lull.bots.BotLoad.Window() {
                public void start() {
                    report();
                }

                public String end(long ticks) {
                    long ns = NANOS.sum();
                    return report() + String.format(java.util.Locale.ROOT, ", %.3f ms a tick in them (%s)", ticks == 0 ? 0 : ns / 1e6 / ticks, active ? "on" : "off");
                }
            });
        }
    }
    private static MinecraftServer server;
    /** inside tickServer (server thread only): a waitForTasks there is not between ticks */
    private static boolean ticking;

    private EarlyAnswers() {}

    public static void beforeTick(MinecraftServer s) {
        server = s;
        BEFORE_TICK.add(answer(s));
        ticking = true;
    }

    public static void afterTick() {
        ticking = false;
    }

    public static void beforeSleep() {
        MinecraftServer s = server;
        if (!ticking && s != null && s.isSameThread()) answer(s);
    }

    private static int answer(MinecraftServer s) {
        if (!active) return 0;
        long t0 = System.nanoTime();
        // the answers of all players leave as one batch per connection (NetReactor: one netty task, one flush), as the
        // tick's own sends would - not a task and a flush per packet (100 bots placing blocks: 20 k block updates a second)
        boolean batch = NetReactor.ENABLED && !NetReactor.batching();
        if (batch) NetReactor.onTickStart();
        try {
            return answerAll(s, t0);
        } finally {
            if (batch) NetReactor.onServerTick();
        }
    }

    private static int answerAll(MinecraftServer s, long t0) {
        int n = 0;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            var ack = (dev.lull.opt.EarlyAnswersAckAccessor) p.connection;
            int seq = ack.sleep$ack();
            if (seq < 0) continue;
            var map = (dev.lull.opt.EarlyAnswersChunkMapAccessor) p.serverLevel().getChunkSource().chunkMap;
            ChunkPos c = p.chunkPosition();
            // (-Dlab.earlyAnswers.ackOnly=true: the test of the order - the acknowledgement alone, the updates left to the tick)
            if (!ACK_ONLY) for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    ChunkHolder h = map.sleep$visible(ChunkPos.asLong(c.x + dx, c.z + dz));
                    LevelChunk chunk = h == null ? null : h.getTickingChunk();
                    if (chunk != null) h.broadcastChanges(chunk);
                }
            p.connection.send(new ClientboundBlockChangedAckPacket(seq));
            ack.sleep$setAck(-1);
            n++;
        }
        ANSWERED.add(n);
        NANOS.add(System.nanoTime() - t0);
        return n;
    }

    public static String report() {
        NANOS.sumThenReset();
        return String.format(java.util.Locale.ROOT, "early answers: %d (%d before a tick)", ANSWERED.sumThenReset(), BEFORE_TICK.sumThenReset());
    }
}
