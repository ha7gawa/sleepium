package dev.lull;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What a player feels of the server (notes/92): a ClientAuto client joined to a server ({@code -Dsleep.clientAuto.server}),
 * {@code -Dsleep.serveFeel=true}. Through the measured window it acts as a player would and times the answers on the
 * network thread (ServeFeelClientMixin):
 * <ul>
 *   <li>block feedback: twice a second a block placed / broken next to the player (creative, stone in hand) - the
 *       predicted change's send to the server's acknowledgement (ClientboundBlockChangedAck);</li>
 *   <li>chat: once a second a message - sent to its echo (the server's broadcast);</li>
 *   <li>a command that does not touch the world ({@code /time query gametime}) - sent to its answer;</li>
 *   <li>chunks received a second, corrections of the player's position (server teleports - rubber-banding), and the
 *       world's update cadence as the client sees it: the gaps between bursts of entity movement packets.</li>
 * </ul>
 * {@code -Dsleep.serveFeel.actions=false}: only the passive counters (a flight).
 */
public final class ServeFeel {
    public static final boolean ON = Boolean.getBoolean("sleep.serveFeel");
    private static final boolean ACTIONS = !"false".equals(System.getProperty("sleep.serveFeel.actions"));
    /**
     * -Dsleep.serveFeel.items=create:shaft,mekanism:basic_energy_cube: the blocks placed in turn (hotbar slots 0..n-1;
     * default stone) - mods' blocks with block entities, whose update packets come with the block's
     */
    private static final String[] ITEMS = System.getProperty("sleep.serveFeel.items", "minecraft:stone").split(",");
    private static int wrong, checked;
    private static volatile boolean active;
    private static int t, chatN;
    private static long startNanos;
    private static BlockPos at;
    private static final Map<Integer, long[]> SENT_SEQ = new HashMap<>();   // sequence -> {nanos, kind 0 place / 1 break}
    private static final Map<Integer, Long> SENT_CHAT = new HashMap<>();
    private static final ArrayDeque<Long> SENT_CMD = new ArrayDeque<>();
    private static final long[][] SAMPLES = new long[5][4096];   // place, break, chat, command, entity bursts' gaps
    private static final int[] N = new int[5];
    private static int chunks, corrections, entityPackets, rollbacks;
    private static long lastEntity;

    private ServeFeel() {}

    public static boolean network() {
        return active && !Minecraft.getInstance().isSameThread();
    }

    public static boolean mainCall() {
        return active && Minecraft.getInstance().isSameThread();
    }

    private static void sample(int k, long nanos) {
        synchronized (SAMPLES) {
            if (N[k] < SAMPLES[k].length) SAMPLES[k][N[k]] = nanos;
            N[k]++;
        }
    }

    public static void start(Minecraft mc) {
        synchronized (SAMPLES) {
            Arrays.fill(N, 0);
            SENT_SEQ.clear();
            SENT_CHAT.clear();
            SENT_CMD.clear();
            chunks = corrections = entityPackets = rollbacks = 0;
            lastEntity = 0;
        }
        t = 0;
        startNanos = System.nanoTime();
        at = mc.player.blockPosition().offset(2, 1, 0);
        if (ACTIONS) for (int i = 0; i < ITEMS.length; i++) mc.getConnection().sendCommand("item replace entity @s hotbar." + i + " with " + ITEMS[i]);
        wrong = checked = 0;
        active = true;
        ClientAuto.log("serveFeel: started, blocks at " + at.toShortString());
    }

    /** client tick, in the measured window */
    public static void tick(Minecraft mc) {
        if (!active || mc.player == null || mc.gameMode == null || mc.getConnection() == null) return;
        t++;
        if (!ACTIONS || t < 40) return;
        if (t % 10 == 0) {
            boolean place = t % 20 == 0;
            // the state the client settled on after the last action (10 ticks ago): placed -> a block, broken -> air
            if (t >= 50) {
                checked++;
                if (checked <= 8) ClientAuto.log("serveFeel: settled " + mc.level.getBlockState(at) + (place ? " after a break" : " after a place"));
                if (mc.level.getBlockState(at).isAir() != place) {   // place now: the last was a break (air expected)
                    wrong++;
                    ClientAuto.log("serveFeel: wrong settled state at " + at.toShortString() + ": " + mc.level.getBlockState(at) + " after a " + (place ? "break" : "place"));
                }
            }
            if (place) mc.player.getInventory().selected = (t / 20 % ITEMS.length) < 9 ? (int) (t / 20 % ITEMS.length) : 0;
            if (place) mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
            else mc.gameMode.startDestroyBlock(at, Direction.UP);
            int seq = ((dev.lull.mixin.ServeFeelLevelAccessor) mc.level).sleep$predictions().currentSequence();
            synchronized (SAMPLES) {
                SENT_SEQ.put(seq, new long[] {System.nanoTime(), place ? 0 : 1});
            }
        }
        if (t % 20 == 5) {
            int n = ++chatN;
            synchronized (SAMPLES) {
                SENT_CHAT.put(n, System.nanoTime());
            }
            mc.getConnection().sendChat("feel" + n);
        }
        if (t % 20 == 15) {
            synchronized (SAMPLES) {
                SENT_CMD.add(System.nanoTime());
            }
            mc.getConnection().sendCommand("time query gametime");
        }
    }

    public static void ack(int seq) {
        long now = System.nanoTime();
        synchronized (SAMPLES) {
            var it = SENT_SEQ.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getKey() <= seq) {
                    sample((int) e.getValue()[1], now - e.getValue()[0]);
                    it.remove();
                }
            }
        }
    }

    public static void chat(String content) {
        if (!content.startsWith("feel")) return;
        long now = System.nanoTime();
        synchronized (SAMPLES) {
            try {
                Long t0 = SENT_CHAT.remove(Integer.parseInt(content.substring(4)));
                if (t0 != null) sample(2, now - t0);
            } catch (NumberFormatException ignored) {}
        }
    }

    public static void system(String s) {
        if (!s.contains("The time is")) return;
        long now = System.nanoTime();
        synchronized (SAMPLES) {
            Long t0 = SENT_CMD.poll();
            if (t0 != null) sample(3, now - t0);
        }
    }

    public static void chunk() {
        synchronized (SAMPLES) {
            chunks++;
        }
    }

    public static int corrections() {
        synchronized (SAMPLES) {
            return corrections;
        }
    }

    public static void rollback() {
        if (!active) return;
        synchronized (SAMPLES) {
            rollbacks++;
        }
    }

    public static void corrected() {
        synchronized (SAMPLES) {
            corrections++;
        }
    }

    /** entity movement: a burst is one server tick's batch; the gaps between bursts are the world's cadence as seen here */
    public static void entityMove() {
        long now = System.nanoTime();
        synchronized (SAMPLES) {
            entityPackets++;
            if (lastEntity != 0 && now - lastEntity > 15_000_000L) sample(4, now - lastEntity);
            lastEntity = now;
        }
    }

    private static String stat(String name, int k) {
        int n = Math.min(N[k], SAMPLES[k].length);
        if (n == 0) return name + " -";
        long[] s = Arrays.copyOf(SAMPLES[k], n);
        Arrays.sort(s);
        double sum = 0;
        for (long v : s) sum += v;
        return String.format(Locale.ROOT, "%s n=%d mean %.1f p50 %.1f p95 %.1f max %.1f ms", name, N[k], sum / n / 1e6, s[n / 2] / 1e6,
                s[Math.min(n - 1, (int) (0.95 * n))] / 1e6, s[n - 1] / 1e6);
    }

    public static String report(String label) {
        if (!active) return null;
        active = false;
        double secs = (System.nanoTime() - startNanos) / 1e9;
        Minecraft mc = Minecraft.getInstance();
        int ping = -1;
        if (mc.getConnection() != null && mc.player != null) {
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) ping = info.getLatency();
        }
        synchronized (SAMPLES) {
            return String.format(Locale.ROOT, "serveFeel '%s' (%.1f s): %s | %s | %s | %s | %s | chunks %.1f/s, corrections %d, block rollbacks %d, wrong settled states %d of %d, entity packets %d, unanswered blocks %d chat %d cmd %d, server's ping %d ms",
                    label, secs, stat("place ack", 0), stat("break ack", 1), stat("chat echo", 2), stat("command", 3), stat("world update gap", 4),
                    chunks / secs, corrections, rollbacks, wrong, checked, entityPackets, SENT_SEQ.size(), SENT_CHAT.size(), SENT_CMD.size(), ping);
        }
    }
}
