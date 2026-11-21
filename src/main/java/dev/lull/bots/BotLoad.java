package dev.lull.bots;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.util.Crypt;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;

/**
 * Load tool, measurement only: N bot players on a dedicated server, server side, with a connection that pays what a real
 * player's costs on the server - vanilla's own pipeline (packet encoder, compression at the server's threshold, AES
 * encryption as in online mode, the length prefix) on the server's own netty threads ({@link SinkChannel}), the bytes
 * counted and dropped. The bot "client" answers what a client must answer (keep-alive, ping, teleport confirmation,
 * chunk batch acknowledgement) and sends one movement packet a tick along a scripted path, injected at the head of the
 * channel's pipeline as decoded packets: they reach the server thread the way a real client's packets do (a task run
 * between ticks: vanilla's movement checks included). Joins through PlayerList.placeNewPlayer (no login /
 * configuration phase: one-time costs).
 * <p>
 * Enabled in dev runs or with {@code -Dsleep.bots=true}. Commands (under /sleep in dev, /sleepium always):
 * <pre>
 *   bots add &lt;n&gt; [x z [spread]]         n bots around (x, z) (default the world spawn), flying 4 blocks above it
 *   bots move &lt;mode&gt; [a [b]]            idle | circle speed radius | orbit speed radius | line speed | tp periodS radius | mix
 *   bots remove                            all bots leave
 *   bots stats                             one log line: bots, bytes, chunks, teleports
 *   bots measure &lt;secs&gt; &lt;label&gt;          a window: MSPT, bytes/s, packets/s, chunks/s, CPU by thread group
 * </pre>
 * Flags: {@code -Dsleep.bots.encrypt=false} (offline-mode server: no AES), {@code -Dsleep.bots.modded=true} (bots
 * negotiate every mod channel, as a client with the same mods; default a vanilla client), {@code
 * -Dsleep.bots.chunkRate=25} (the chunks a tick the bot asks for in its batch acknowledgement; a real client measures
 * its own: 7 ms / its time a chunk), {@code -Dsleep.bots.view=32} (requested view distance, the server clamps it).
 */
@EventBusSubscriber(modid = "sleepium")
public final class BotLoad {
    public static final boolean ENABLED = !net.neoforged.fml.loading.FMLLoader.isProduction() || Boolean.getBoolean("sleep.bots");
    private static final boolean ENCRYPT = !"false".equals(System.getProperty("sleep.bots.encrypt"));
    private static final boolean MODDED = Boolean.getBoolean("sleep.bots.modded");
    private static final float CHUNK_RATE = Float.parseFloat(System.getProperty("sleep.bots.chunkRate", "25"));
    private static final int VIEW = Integer.getInteger("sleep.bots.view", 32);
    private static final Logger LOGGER = LogUtils.getLogger();

    static final LongAdder CHUNKS = new LongAdder(), BATCHES = new LongAdder(), TELEPORTS = new LongAdder(), MOVES = new LongAdder(),
            PACKETS = new LongAdder();
    private static final List<Bot> BOTS = new ArrayList<>();
    private static int named;
    private static double cx, cy, cz;

    enum Mode { IDLE, CIRCLE, ORBIT, LINE, TP }

    static final class Bot {
        final int index;
        final String name;
        final Connection conn;
        final SinkChannel ch;
        ServerPlayer player;
        Mode mode = Mode.IDLE;
        double speed, radius, period, phase;
        double hx, hy, hz;   // home: where circle and line start, where tp landed
        long t;   // ticks in the current mode
        float yaw;

        Bot(int index, String name, Connection conn, SinkChannel ch) {
            this.index = index;
            this.name = name;
            this.conn = conn;
            this.ch = ch;
        }
    }

    private BotLoad() {}

    // ---------------------------------------------------------------- the tap: what the bot's client receives and answers

    /** sees the server's packets as objects, before the encoder; answers on the channel's event loop like a client would */
    private static final class Tap extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Packet<?> answer = null;
            if (msg instanceof ClientboundKeepAlivePacket k) answer = new ServerboundKeepAlivePacket(k.getId());
            else if (msg instanceof ClientboundPingPacket p) answer = new ServerboundPongPacket(p.getId());
            else if (msg instanceof ClientboundPlayerPositionPacket p) {
                answer = new ServerboundAcceptTeleportationPacket(p.getId());
                TELEPORTS.increment();
            } else if (msg instanceof ClientboundChunkBatchFinishedPacket) {
                answer = new ServerboundChunkBatchReceivedPacket(CHUNK_RATE);
                BATCHES.increment();
            }
            if (msg instanceof ClientboundBundlePacket b) for (var sub : b.subPackets()) count(sub);
            else if (msg instanceof Packet<?> p) count(p);
            ctx.write(msg, promise);
            if (answer != null) {
                Packet<?> a = answer;
                Channel c = ctx.channel();
                c.eventLoop().execute(() -> {
                    if (c.isOpen()) c.pipeline().fireChannelRead(a);
                });
            }
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, LongAdder> KINDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static void count(Packet<?> p) {
        PACKETS.increment();
        if (p instanceof ClientboundLevelChunkWithLightPacket) CHUNKS.increment();
        KINDS.computeIfAbsent(p.getClass(), k -> new LongAdder()).increment();
    }

    private static String kinds() {
        StringBuilder sb = new StringBuilder();
        KINDS.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum())).limit(12)
                .forEach(e -> sb.append(' ').append(e.getKey().getSimpleName()).append(' ').append(e.getValue().sum()).append(';'));
        return sb.toString();
    }

    // ---------------------------------------------------------------- real players, counted the same way (validation)

    /** -Dsleep.bots.tapReal=true: real players' connections get counting handlers (packets by kind, bytes, their chunk rate) */
    private static final boolean TAP_REAL = Boolean.getBoolean("sleep.bots.tapReal");
    static final LongAdder R_PACKETS = new LongAdder(), R_CHUNKS = new LongAdder(), R_BYTES = new LongAdder(), R_FRAMES = new LongAdder(),
            R_ACKS = new LongAdder();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, LongAdder> R_KINDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile float rRate;

    private static void countReal(Packet<?> p) {
        R_PACKETS.increment();
        if (p instanceof ClientboundLevelChunkWithLightPacket) R_CHUNKS.increment();
        R_KINDS.computeIfAbsent(p.getClass(), k -> new LongAdder()).increment();
    }

    private static final class RealTap extends io.netty.channel.ChannelDuplexHandler {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ClientboundBundlePacket b) for (var sub : b.subPackets()) countReal(sub);
            else if (msg instanceof Packet<?> p) countReal(p);
            ctx.write(msg, promise);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ServerboundChunkBatchReceivedPacket r) {
                rRate = r.desiredChunksPerTick();
                R_ACKS.increment();
            }
            ctx.fireChannelRead(msg);
        }
    }

    private static final class RealBytes extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof io.netty.buffer.ByteBuf b) {
                R_BYTES.add(b.readableBytes());
                R_FRAMES.increment();
            }
            ctx.write(msg, promise);
        }
    }

    @SubscribeEvent
    static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
        if (!TAP_REAL || !(e.getEntity() instanceof ServerPlayer sp) || sp.connection == null) return;
        Channel ch = sp.connection.getConnection().channel();
        if (ch == null || ch instanceof SinkChannel) return;
        ch.eventLoop().execute(() -> {
            var pipe = ch.pipeline();
            if (pipe.get("hackfix") != null) pipe.addBefore("hackfix", "bot_count", new RealTap());
            pipe.addFirst("bot_bytes", new RealBytes());
            LOGGER.info("sleep: bots: counting {}'s connection: {}", sp.getScoreboardName(), pipe.names());
        });
    }

    private static String realKinds() {
        StringBuilder sb = new StringBuilder();
        R_KINDS.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum())).limit(14)
                .forEach(e -> sb.append(' ').append(e.getKey().getSimpleName()).append(' ').append(e.getValue().sum()).append(';'));
        return sb.toString();
    }

    private static void inject(Bot b, Packet<?> p) {
        Channel c = b.ch;
        c.eventLoop().execute(() -> {
            if (c.isOpen()) c.pipeline().fireChannelRead(p);
        });
    }

    // ---------------------------------------------------------------- joining and leaving

    private static Bot join(MinecraftServer server, int index) throws Exception {
        String name = "bot" + index;
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        Connection conn = new Connection(PacketFlow.SERVERBOUND);
        SinkChannel ch = new SinkChannel(new InetSocketAddress("127.0.0.1", 25565),
                new InetSocketAddress("127.1." + (index >> 8 & 255) + "." + (index & 255), 40000 + (index & 8191)));
        Connection.configureSerialization(ch.pipeline(), PacketFlow.SERVERBOUND, false, null);
        // as after a real handshake (its terminal packet unconfigures the decoder): placeNewPlayer's setupInboundProtocol
        // puts the PLAY decoder here
        ch.pipeline().replace("decoder", "inbound_config", new UnconfiguredPipelineHandler.Inbound());
        ch.pipeline().addLast("bot_tap", new Tap());
        conn.configurePacketHandler(ch.pipeline());
        ServerConnectionListener.SERVER_EVENT_GROUP.get().register(ch).syncUninterruptibly();   // channelActive: conn.channel set
        for (int i = 0; i < 1000 && conn.isConnecting(); i++) Thread.sleep(1);
        if (conn.isConnecting()) throw new IllegalStateException("the bot's channel did not become active");
        ConnectionType type = MODDED ? ConnectionType.NEOFORGE : ConnectionType.OTHER;
        if (MODDED) NetworkRegistry.configureMockConnection(conn);
        else {   // a vanilla client: no mod channels (NeoForge's initializeOtherConnection)
            ChannelAttributes.setPayloadSetup(conn, NetworkPayloadSetup.empty());
            ChannelAttributes.setConnectionType(conn, type);
        }
        conn.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), type)));
        int threshold = server.getCompressionThreshold();
        ch.eventLoop().submit(() -> {
            if (ENCRYPT) {
                byte[] key = new byte[16];
                new SecureRandom().nextBytes(key);
                SecretKeySpec k = new SecretKeySpec(key, "AES");
                try {
                    conn.setEncryptionKey(Crypt.getCipher(Cipher.DECRYPT_MODE, k), Crypt.getCipher(Cipher.ENCRYPT_MODE, k));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            if (threshold >= 0) conn.setupCompression(threshold, false);
        }).syncUninterruptibly();
        ClientInformation info = new ClientInformation("en_us", VIEW, ChatVisiblity.FULL, true, 0, Player.DEFAULT_MAIN_HAND, false, true);
        ServerPlayer player = server.getPlayerList().getPlayerForLogin(profile, info);
        server.getConnection().getConnections().add(conn);
        server.getPlayerList().placeNewPlayer(conn, player, new CommonListenerCookie(profile, 0, info, false, type));
        Bot b = new Bot(index, name, conn, ch);
        b.player = player;
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        return b;
    }

    private static int add(CommandContext<CommandSourceStack> ctx, int n, Double x, Double z, double spread) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerLevel level = server.overworld();
        if (BOTS.isEmpty() || x != null) {
            var spawn = level.getSharedSpawnPos();
            cx = x != null ? x : spawn.getX() + 0.5;
            cz = z != null ? z : spawn.getZ() + 0.5;
            cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(cx), (int) Math.floor(cz)) + 4;
        }
        long t0 = System.nanoTime();
        int ok = 0;
        for (int i = 0; i < n; i++) {
            int index = named++;
            try {
                Bot b = join(server, index);
                java.util.Random r = new java.util.Random(0x5EEDL * 31 + index);
                double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * spread;
                b.hx = cx + Math.cos(a) * d;
                b.hz = cz + Math.sin(a) * d;
                b.hy = cy;
                b.phase = r.nextDouble() * Math.PI * 2;
                b.player.connection.teleport(b.hx, b.hy, b.hz, 0, 0);
                BOTS.add(b);
                ok++;
            } catch (Exception e) {
                LOGGER.error("sleep: bots: bot{} could not join", index, e);
            }
        }
        say(ctx, String.format(Locale.ROOT, "sleep: bots: %d joined in %.0f ms (%d bots around %.0f %.0f %.0f)", ok, (System.nanoTime() - t0) / 1e6,
                BOTS.size(), cx, cy, cz));
        return ok;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        int n = BOTS.size();
        for (Bot b : BOTS) if (b.conn.isConnected()) b.conn.disconnect(Component.literal("bots removed"));
        BOTS.clear();
        say(ctx, "sleep: bots: " + n + " removed");
        return n;
    }

    // ---------------------------------------------------------------- moving

    private static void setMode(Bot b, Mode m, double a, double r) {
        if (b.player != null) {   // the new path starts where the bot is
            b.hx = b.player.getX();
            b.hy = b.player.getY();
            b.hz = b.player.getZ();
        }
        b.mode = m;
        b.t = 0;
        switch (m) {
            case CIRCLE, ORBIT -> { b.speed = a; b.radius = r; }
            case LINE -> b.speed = a;
            case TP -> { b.period = a; b.radius = r; }
            default -> {}
        }
        if (m == Mode.CIRCLE) {   // the circle passes through where the bot is: centre one radius away
            b.hx -= Math.cos(b.phase) * r;
            b.hz -= Math.sin(b.phase) * r;
        }
    }

    private static int move(CommandContext<CommandSourceStack> ctx, String mode, double a, double r) {
        int n = 0;
        for (Bot b : BOTS) {
            Mode m;
            double aa = a, rr = r;
            if (mode.equals("mix")) {   // a server's day: most walk around, some fly far, a few teleport into new terrain
                int k = b.index % 20;
                if (k < 12) { m = Mode.CIRCLE; aa = 4.3; rr = 24; }
                else if (k < 17) { m = Mode.ORBIT; aa = 10; rr = 160; }
                else if (k < 19) { m = Mode.LINE; aa = 15; }
                else { m = Mode.TP; aa = 15; rr = 2000; }
            } else m = Mode.valueOf(mode.toUpperCase(Locale.ROOT));
            setMode(b, m, aa, rr);
            n++;
        }
        say(ctx, "sleep: bots: " + n + " bots -> " + mode + (mode.equals("mix") ? "" : " " + a + " " + r));
        return n;
    }

    private static void step(MinecraftServer server, Bot b) {
        ServerPlayer p = b.player;
        if (p == null || p.isRemoved() || !b.conn.isConnected()) return;
        long t = b.t++;
        double x, z;
        switch (b.mode) {
            case CIRCLE -> {
                double ang = b.phase + b.speed / Math.max(1, b.radius) * t / 20.0;
                x = b.hx + Math.cos(ang) * b.radius;
                z = b.hz + Math.sin(ang) * b.radius;
                b.yaw = (float) Math.toDegrees(ang) + 180f;
            }
            case ORBIT -> {
                double ang = b.phase + b.speed / Math.max(1, b.radius) * t / 20.0;
                x = cx + Math.cos(ang) * b.radius;
                z = cz + Math.sin(ang) * b.radius;
                if (t == 0 && Math.abs(x - p.getX()) + Math.abs(z - p.getZ()) > 8) {   // reach the orbit by a teleport first
                    p.connection.teleport(x, b.hy, z, b.yaw, 0);
                    return;
                }
                b.yaw = (float) Math.toDegrees(ang) + 180f;
            }
            case LINE -> {
                double d = b.speed * t / 20.0;
                x = b.hx + Math.cos(b.phase) * d;
                z = b.hz + Math.sin(b.phase) * d;
                b.yaw = (float) Math.toDegrees(b.phase) - 90f;
            }
            case TP -> {
                long per = Math.max(20, (long) (b.period * 20));
                if ((t + b.index * 7L) % per == 0) {
                    java.util.Random r = new java.util.Random(b.index * 1_000_003L + t);
                    double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * b.radius;
                    b.hx = cx + Math.cos(a) * d;
                    b.hz = cz + Math.sin(a) * d;
                    p.connection.teleport(b.hx, b.hy, b.hz, b.yaw, 0);
                    return;
                }
                if (t % 20 != 0) return;
                x = b.hx;
                z = b.hz;
            }
            default -> {
                if (t % 20 != 0) return;   // a still client reminds the server of its position once a second
                x = p.getX();
                z = p.getZ();
            }
        }
        MOVES.increment();
        inject(b, new ServerboundMovePlayerPacket.PosRot(x, b.hy, z, b.yaw, 0f, false));
    }

    // ---------------------------------------------------------------- measuring

    /** other measurements reported with the window (e.g. a lab scout): started with it, a text at its end for its ticks */
    public interface Window {
        void start();

        String end(long ticks);
    }

    public static final List<Window> WINDOWS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static long mStart = -1, mEnd, tickStart, mBytes0, mFrames0, mChunks0, mPackets0, mProc0, mRBytes0, mRFrames0, mRPackets0, mRChunks0;
    private static String mLabel;
    private static long[] mTicks = new long[4096];
    private static int mN;
    private static Map<Long, Long> mThreads0;
    private static final Map<Long, String> mNames = new HashMap<>();

    private static long bytes() {
        long s = 0;
        for (Bot b : BOTS) s += b.ch.bytes;
        return s;
    }

    private static long frames() {
        long s = 0;
        for (Bot b : BOTS) s += b.ch.frames;
        return s;
    }

    private static Map<Long, Long> threadCpu() {
        var mx = ManagementFactory.getThreadMXBean();
        Map<Long, Long> m = new HashMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            long c = mx.getThreadCpuTime(t.threadId());
            if (c >= 0) {
                m.put(t.threadId(), c);
                mNames.putIfAbsent(t.threadId(), t.getName().replaceAll("\\d+", "N"));
            }
        }
        return m;
    }

    private static long processCpu() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuTime() : -1;
    }

    private static void startMeasure(int secs, String label) {
        mLabel = label;
        mNames.clear();
        mThreads0 = threadCpu();
        mProc0 = processCpu();
        mBytes0 = bytes();
        mFrames0 = frames();
        mChunks0 = CHUNKS.sum();
        mPackets0 = PACKETS.sum();
        mRBytes0 = R_BYTES.sum();
        mRFrames0 = R_FRAMES.sum();
        mRPackets0 = R_PACKETS.sum();
        mRChunks0 = R_CHUNKS.sum();
        mN = 0;
        tickStart = 0;
        for (Window w : WINDOWS) w.start();
        mStart = System.nanoTime();
        mEnd = mStart + secs * 1_000_000_000L;
    }

    private static void endMeasure(MinecraftServer server) {
        double secs = (System.nanoTime() - mStart) / 1e9;
        mStart = -1;
        long[] t = Arrays.copyOf(mTicks, Math.min(mN, mTicks.length));
        Arrays.sort(t);
        double mean = 0;
        for (long v : t) mean += v;
        mean = t.length == 0 ? 0 : mean / t.length / 1e6;
        Map<Long, Long> now = threadCpu();
        Map<String, Long> by = new TreeMap<>();
        for (var e : now.entrySet()) by.merge(mNames.getOrDefault(e.getKey(), "?"), e.getValue() - mThreads0.getOrDefault(e.getKey(), 0L), Long::sum);
        StringBuilder cpu = new StringBuilder();
        by.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(8)
                .forEach(e -> cpu.append(String.format(Locale.ROOT, " %s %.2f;", e.getKey(), e.getValue() / 1e9 / secs)));
        String line = String.format(Locale.ROOT,
                "bots measure '%s': %d bots, %d players, %.1f s, %d ticks (%.1f tps), tick mean %.2f p50 %.2f p95 %.2f max %.2f ms; "
                        + "bots out %.2f MB/s %.0f frames/s %.0f packets/s %.1f chunks/s; cpu process %.2f cores, by group:%s",
                mLabel, BOTS.size(), server.getPlayerList().getPlayerCount(), secs, t.length, t.length / secs, mean,
                pct(t, 50), pct(t, 95), t.length == 0 ? 0 : t[t.length - 1] / 1e6,
                (bytes() - mBytes0) / 1e6 / secs, (frames() - mFrames0) / secs, (PACKETS.sum() - mPackets0) / secs, (CHUNKS.sum() - mChunks0) / secs,
                (processCpu() - mProc0) / 1e9 / secs, cpu);
        if (TAP_REAL) line += String.format(Locale.ROOT, " | real players out %.3f MB/s %.0f frames/s %.0f packets/s %.1f chunks/s, chunk rate asked %.1f (%d acks)",
                (R_BYTES.sum() - mRBytes0) / 1e6 / secs, (R_FRAMES.sum() - mRFrames0) / secs, (R_PACKETS.sum() - mRPackets0) / secs,
                (R_CHUNKS.sum() - mRChunks0) / secs, rRate, R_ACKS.sum());
        for (Window w : WINDOWS) {
            String x = w.end(t.length);
            if (x != null && !x.isEmpty()) line += " | " + x;
        }
        LOGGER.info("sleep: {}", line);
        server.getPlayerList().broadcastSystemMessage(Component.literal("sleep: bots measure '" + mLabel + "' done"), false);
    }

    private static double pct(long[] sorted, int p) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, sorted.length * p / 100)] / 1e6;
    }

    // ---------------------------------------------------------------- events and commands

    @SubscribeEvent
    static void tickStart(ServerTickEvent.Pre e) {
        tickStart = mStart >= 0 ? System.nanoTime() : 0;
    }

    @SubscribeEvent
    static void tickEnd(ServerTickEvent.Post e) {
        if (!BOTS.isEmpty()) {
            MinecraftServer server = e.getServer();
            for (int i = 0; i < BOTS.size(); i++) step(server, BOTS.get(i));
            BOTS.removeIf(b -> !b.conn.isConnected() && b.player != null && b.player.hasDisconnected());
        }
        if (mStart >= 0) {
            if (tickStart != 0 && mN < mTicks.length) mTicks[mN++] = System.nanoTime() - tickStart;
            if (System.nanoTime() >= mEnd) endMeasure(e.getServer());
        }
    }

    @SubscribeEvent
    static void stopping(ServerStoppingEvent e) {
        BOTS.clear();
        mStart = -1;
    }

    private static void say(CommandContext<CommandSourceStack> ctx, String s) {
        LOGGER.info(s);
        ctx.getSource().sendSuccess(() -> Component.literal(s), false);
    }

    @SubscribeEvent
    static void commands(RegisterCommandsEvent event) {
        if (!ENABLED) return;
        for (String root : !net.neoforged.fml.loading.FMLLoader.isProduction() ? new String[] {"sleepium", "sleep"} : new String[] {"sleepium"})
            event.getDispatcher().register(Commands.literal(root).requires(s -> s.hasPermission(2)).then(tree()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("bots")
                .then(Commands.literal("add").then(Commands.argument("n", IntegerArgumentType.integer(1, 1000))
                        .executes(c -> add(c, IntegerArgumentType.getInteger(c, "n"), null, null, 32))
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg()).then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                .executes(c -> add(c, IntegerArgumentType.getInteger(c, "n"), DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "z"), 32))
                                .then(Commands.argument("spread", DoubleArgumentType.doubleArg(0)).executes(c -> add(c, IntegerArgumentType.getInteger(c, "n"),
                                        DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "z"), DoubleArgumentType.getDouble(c, "spread"))))))))
                .then(Commands.literal("move").then(Commands.argument("mode", StringArgumentType.word())
                        .executes(c -> move(c, StringArgumentType.getString(c, "mode"), 4.3, 24))
                        .then(Commands.argument("a", DoubleArgumentType.doubleArg(0))
                                .executes(c -> move(c, StringArgumentType.getString(c, "mode"), DoubleArgumentType.getDouble(c, "a"), 24))
                                .then(Commands.argument("b", DoubleArgumentType.doubleArg(0))
                                        .executes(c -> move(c, StringArgumentType.getString(c, "mode"), DoubleArgumentType.getDouble(c, "a"), DoubleArgumentType.getDouble(c, "b")))))))
                .then(Commands.literal("remove").executes(BotLoad::remove))
                .then(Commands.literal("reset").executes(c -> {   // counters to zero (stats compare windows)
                    for (LongAdder l : new LongAdder[] {CHUNKS, BATCHES, TELEPORTS, MOVES, PACKETS, R_PACKETS, R_CHUNKS, R_BYTES, R_FRAMES, R_ACKS}) l.reset();
                    KINDS.clear();
                    R_KINDS.clear();
                    for (Bot b : BOTS) b.ch.bytes = b.ch.frames = 0;
                    say(c, "sleep: bots: counters reset");
                    return 1;
                }))
                .then(Commands.literal("stats").executes(c -> {
                    say(c, String.format(Locale.ROOT, "sleep: bots stats: %d bots; out %.1f MB in %d frames, %d packets; %d chunks in %d batches; %d teleports; %d moves; kinds:%s%s",
                            BOTS.size(), bytes() / 1e6, frames(), PACKETS.sum(), CHUNKS.sum(), BATCHES.sum(), TELEPORTS.sum(), MOVES.sum(), kinds(),
                            TAP_REAL ? String.format(Locale.ROOT, " | real: out %.1f MB in %d frames, %d packets, %d chunks, rate %.1f; kinds:%s",
                                    R_BYTES.sum() / 1e6, R_FRAMES.sum(), R_PACKETS.sum(), R_CHUNKS.sum(), rRate, realKinds()) : ""));
                    return 1;
                }))
                .then(Commands.literal("measure").then(Commands.argument("secs", IntegerArgumentType.integer(1, 600))
                        .then(Commands.argument("label", StringArgumentType.word()).executes(c -> {
                            startMeasure(IntegerArgumentType.getInteger(c, "secs"), StringArgumentType.getString(c, "label"));
                            say(c, "sleep: bots measure started");
                            return 1;
                        }))));
    }
}
