package dev.lull.optsupport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The tick's "send chunks" phase with the chunk packets built in parallel ({@code -Dlab.opt.ChunkSendPar=true},
 * opt/ChunkSendParMixin + ChunkSendParServerMixin). Vanilla's phase: for every player in order, PlayerChunkSender picks
 * the chunks of this tick's batch (its quota, the nearest pending ones), builds a ClientboundLevelChunkWithLightPacket for
 * each (the sections' palettes written to bytes, heightmaps, block entities' update tags, the light layers) and sends
 * them between a batch start and a batch end, firing NeoForge's ChunkWatchEvent.Sent after each.
 * <p>
 * Here the phase has three steps:
 * <ol>
 *   <li>plan (server thread, players in vanilla's order): every sender's vanilla decision - quota, collect (which removes
 *       the chunks from its pending set) - exactly as its sendNextChunks would make it at this point; nothing between
 *       two players' decisions reads or writes another player's sender;</li>
 *   <li>build (the server pool, the server thread helping): one packet per distinct chunk of all plans - a chunk sent
 *       to several players this tick is built once (vanilla builds it per player from the same, unchanged state);</li>
 *   <li>send (server thread, vanilla's order): each player's batch start, the packets (NeoForge's aux light added per
 *       player as vanilla does), the debug POI packets, the event, the batch end, the quota.</li>
 * </ol>
 * Why the same bytes: the phase writes nothing the builds read - the world does not change during it (the server
 * thread only sends; the chunks' containers are written by the server thread only), so a chunk's state at the build is
 * its state at vanilla's build later in the same phase. Two exceptions are guarded: a block entity type of a mod in
 * the chunk (its update tag is the mod's code - that chunk is built on the server thread at its vanilla moment), and
 * the light layers, which the light thread publishes concurrently in vanilla too (vanilla's own result depends on that
 * timing; verify counts light differences apart from chunk data differences). A listener of ChunkWatchEvent.Sent that
 * changes blocks would make later packets of this tick differ - none in the engine; verify mode catches it.
 * {@code -Dlab.opt.ChunkSendParVerify=true}: every prebuilt packet is compared byte for byte with one built on the
 * server thread at vanilla's moment (chunk data and light separately).
 */
public final class ChunkSendPar {
    public static final boolean VERIFY = Boolean.getBoolean("lab.opt.ChunkSendParVerify");
    private static final int HELPERS = Integer.getInteger("lab.opt.ChunkSendParHelpers", 4);
    /** /sleep bots ab chunksend on|off */
    public static volatile boolean active = true;

    /** what a PlayerChunkSender exposes to the plan step (the mixin) */
    public interface Planner {
        /** vanilla's decision for this tick; the chunks it sends, or null. The next sendNextChunks sends exactly them */
        List<LevelChunk> sleep$plan(ServerPlayer player);

        /** drop a plan sendNextChunks did not use (a player gone in between) */
        void sleep$dropPlan();
    }

    public static final LongAdder PLANNED = new LongAdder(), BUILT_PAR = new LongAdder(), SHARED = new LongAdder(), MAIN = new LongAdder(),
            CHECKED = new LongAdder(), DATA_DIFF = new LongAdder(), LIGHT_DIFF = new LongAdder(), PREP_NS = new LongAdder();

    private static final IdentityHashMap<LevelChunk, ClientboundLevelChunkWithLightPacket> BUILT = new IdentityHashMap<>();
    private static final List<Planner> PLANNERS = new ArrayList<>();
    private static final List<LevelChunk> TODO = new ArrayList<>();
    private static ClientboundLevelChunkWithLightPacket[] results = new ClientboundLevelChunkWithLightPacket[64];
    private static MinecraftServer server;

    private ChunkSendPar() {}

    public static void register() {
        dev.lull.bots.BotLoad.AB.put("chunksend", v -> active = v);
        dev.lull.bots.BotLoad.WINDOWS.add(new dev.lull.bots.BotLoad.Window() {
            private long p0, b0, s0, m0, t0;

            public void start() {
                p0 = PLANNED.sum();
                b0 = BUILT_PAR.sum();
                s0 = SHARED.sum();
                m0 = MAIN.sum();
                t0 = PREP_NS.sum();
            }

            public String end(long ticks) {
                return String.format(Locale.ROOT, "chunksend: %d chunks planned, %d built in parallel (%d shared by players), %d on the main thread;"
                                + " plan+build %.2f ms/tick%s", PLANNED.sum() - p0, BUILT_PAR.sum() - b0, SHARED.sum() - s0, MAIN.sum() - m0,
                        ticks == 0 ? 0 : (PREP_NS.sum() - t0) / 1e6 / ticks,
                        VERIFY ? "; verified " + CHECKED.sum() + ", chunk data differences " + DATA_DIFF.sum() + ", light differences " + LIGHT_DIFF.sum() : "");
            }
        });
    }

    private static boolean vanillaBlockEntities(LevelChunk chunk) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            if (key == null || !"minecraft".equals(key.getNamespace())) return false;
        }
        return true;
    }

    /** server thread, the start of "send chunks": plan every player, build the packets */
    public static void prepare(MinecraftServer s) {
        if (!active) return;
        long t0 = System.nanoTime();
        server = s;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            Planner pl = (Planner) p.connection.chunkSender;
            List<LevelChunk> list = pl.sleep$plan(p);
            PLANNERS.add(pl);
            if (list == null) continue;
            for (LevelChunk c : list) {
                PLANNED.increment();
                if (BUILT.containsKey(c)) {
                    SHARED.increment();
                    continue;
                }
                BUILT.put(c, null);
                if (vanillaBlockEntities(c)) TODO.add(c);
            }
        }
        int n = TODO.size();
        if (n > 0) {
            if (results.length < n) results = new ClientboundLevelChunkWithLightPacket[Math.max(n, results.length * 2)];
            ClientboundLevelChunkWithLightPacket[] out = results;
            List<LevelChunk> todo = TODO;
            if (n == 1) out[0] = build(todo.get(0));
            else ServerReactor.pool().forRange(n, 1, HELPERS, i -> out[i] = build(todo.get(i)));
            for (int i = 0; i < n; i++) {
                BUILT.put(todo.get(i), out[i]);
                out[i] = null;
            }
            BUILT_PAR.add(n);
            TODO.clear();
        }
        PREP_NS.add(System.nanoTime() - t0);
    }

    private static ClientboundLevelChunkWithLightPacket build(LevelChunk c) {
        return new ClientboundLevelChunkWithLightPacket(c, ((ServerLevel) c.getLevel()).getLightEngine(), null, null);
    }

    /** server thread, the end of the tick's children: plans and packets of this tick dropped */
    public static void end() {
        for (int i = 0; i < PLANNERS.size(); i++) PLANNERS.get(i).sleep$dropPlan();
        PLANNERS.clear();
        BUILT.clear();
    }

    /** vanilla's PlayerChunkSender.sendChunk with the packet built in step 2 (or here, at vanilla's moment) */
    public static void sendChunk(ServerGamePacketListenerImpl listener, ServerLevel level, LevelChunk chunk) {
        ClientboundLevelChunkWithLightPacket pkt = BUILT.get(chunk);
        if (pkt == null) {
            pkt = build(chunk);
            MAIN.increment();
        } else if (VERIFY) {
            verify(pkt, build(chunk));
        }
        listener.send(chunk.getAuxLightManager(chunk.getPos()).sendLightDataTo(pkt));
        DebugPackets.sendPoiPacketsForChunk(level, chunk.getPos());
        net.neoforged.neoforge.event.EventHooks.fireChunkSent(listener.player, chunk, level);
    }

    private static void verify(ClientboundLevelChunkWithLightPacket a, ClientboundLevelChunkWithLightPacket b) {
        CHECKED.increment();
        var reg = server.registryAccess();
        RegistryFriendlyByteBuf x = new RegistryFriendlyByteBuf(Unpooled.buffer(), reg), y = new RegistryFriendlyByteBuf(Unpooled.buffer(), reg);
        a.getChunkData().write(x);
        b.getChunkData().write(y);
        if (!ByteBufUtilEq.eq(x, y)) DATA_DIFF.increment();
        x.clear();
        y.clear();
        a.getLightData().write(x);
        b.getLightData().write(y);
        if (!ByteBufUtilEq.eq(x, y)) LIGHT_DIFF.increment();
        x.release();
        y.release();
    }

    private static final class ByteBufUtilEq {
        static boolean eq(ByteBuf a, ByteBuf b) {
            return io.netty.buffer.ByteBufUtil.equals(a, b);
        }
    }
}
