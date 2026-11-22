package dev.lull.optsupport;

import java.util.Locale;

/**
 * Measurement only ({@code -Dlab.pipescout=true}, the PipeScout* mixins): where the per-player work of a server tick goes,
 * timed directly on the server thread - the entity tracker (ChunkMap.tick(): chunk tracking per player, updatePlayers
 * for entities / players that changed section, ServerEntity.sendChanges, addPairing = spawn packets), the sends made by
 * the server thread (count; time sampled 1 in 16), chunk sending (sendNextChunks, the chunk packets built), the players'
 * connection tick and their own doTick, movement packets handled. Printed with BotLoad's measure window.
 */
public final class PipeScout {
    public static final boolean ON = Boolean.getBoolean("lab.pipescout");
    public static volatile Thread serverThread;

    // server thread only
    public static long tracker, trackerN, chunkTracking, updatePlayers, updatePlayersN, updatePlayer, sendChanges, sendChangesN,
            pairing, pairingN, sends, sendsTracker, cmove, cmoveN, cmoveTrack, sendSampled, sendSampledNs, nextChunks, chunkPackets, chunkPacketNs, connTick, doTick, moves, moveNs;
    private static boolean inTracker;
    private static long t0Cmove, t0CmTrack;
    private static long t0Tracker, t0Track, t0Upd, t0Send, t0Pair, t0Net, t0Next, t0Chunk, t0Conn, t0Do, t0Move;
    private static int depthUpd;

    private PipeScout() {}

    /** with BotLoad's measure window (called once at mod construction time when ON) */
    public static void register() {
        dev.lull.bots.BotLoad.WINDOWS.add(new dev.lull.bots.BotLoad.Window() {
            public void start() { reset(); }

            public String end(long ticks) { return report(ticks); }
        });
    }

    public static boolean onServer() {
        return Thread.currentThread() == serverThread;
    }

    public static void trackerStart() { serverThread = Thread.currentThread(); inTracker = true; t0Tracker = System.nanoTime(); }
    public static void trackerEnd() { tracker += System.nanoTime() - t0Tracker; trackerN++; inTracker = false; }
    public static void cmoveStart() { t0Cmove = System.nanoTime(); }
    public static void cmoveEnd() { cmove += System.nanoTime() - t0Cmove; cmoveN++; }
    /** ChunkMap.move: the loop over tracked entities ends where the player's section check begins (getLastSectionPos) */
    public static void cmoveLoopEnd() { cmoveTrack += System.nanoTime() - t0Cmove; }
    public static void trackStart() { t0Track = System.nanoTime(); }
    public static void trackEnd() { chunkTracking += System.nanoTime() - t0Track; }
    public static void updStart() { if (depthUpd++ == 0) t0Upd = System.nanoTime(); updatePlayersN++; }
    public static void updEnd() { if (--depthUpd == 0) updatePlayers += System.nanoTime() - t0Upd; }
    public static void updOne() { updatePlayer++; }
    public static void changesStart() { t0Send = System.nanoTime(); }
    public static void changesEnd() { sendChanges += System.nanoTime() - t0Send; sendChangesN++; }
    public static void pairStart() { t0Pair = System.nanoTime(); }
    public static void pairEnd() { pairing += System.nanoTime() - t0Pair; pairingN++; }

    public static boolean netStart() {
        if (!onServer()) return false;
        if (inTracker) sendsTracker++;
        if ((++sends & 15) == 0) { t0Net = System.nanoTime(); return true; }
        return false;
    }

    public static void netEnd() {
        if (t0Net != 0 && onServer()) {
            sendSampledNs += System.nanoTime() - t0Net;
            sendSampled++;
            t0Net = 0;
        }
    }

    public static void nextStart() { t0Next = System.nanoTime(); }
    public static void nextEnd() { nextChunks += System.nanoTime() - t0Next; }
    public static void chunkStart() { t0Chunk = System.nanoTime(); }
    public static void chunkEnd() { chunkPacketNs += System.nanoTime() - t0Chunk; chunkPackets++; }
    public static void connStart() { t0Conn = System.nanoTime(); }
    public static void connEnd() { connTick += System.nanoTime() - t0Conn; }
    public static void doStart() { t0Do = System.nanoTime(); }
    public static void doEnd() { doTick += System.nanoTime() - t0Do; }

    public static void moveStart() { if (onServer()) t0Move = System.nanoTime(); }

    public static void moveEnd() {
        if (t0Move != 0 && onServer()) {
            moveNs += System.nanoTime() - t0Move;
            moves++;
            t0Move = 0;
        }
    }

    public static void reset() {
        tracker = trackerN = chunkTracking = updatePlayers = updatePlayersN = updatePlayer = sendChanges = sendChangesN = pairing = pairingN = 0;
        sends = sendsTracker = cmove = cmoveN = cmoveTrack = sendSampled = sendSampledNs = nextChunks = chunkPackets = chunkPacketNs = connTick = doTick = moves = moveNs = 0;
    }

    /** ms per tick over `ticks` server ticks */
    public static String report(long ticks) {
        if (!ON || ticks <= 0) return "";
        double t = ticks;
        double sendMean = sendSampled == 0 ? 0 : sendSampledNs / (double) sendSampled;
        return String.format(Locale.ROOT,
                "pipescout ms/tick: tracker %.2f (chunk tracking %.2f; updatePlayers %.2f in %.0f calls, %.0f pairs checked; sendChanges %.2f for %.0f entities;"
                        + " addPairing %.2f x%.1f); server-thread sends %.0f/tick ~%.0f ns each = %.2f; send chunks %.2f (%.1f chunk packets %.0f us each);"
                        + " connection ticks %.2f (doTick %.2f); moves handled %.1f/tick %.2f (ChunkMap.move %.2f in %.1f, its loop over tracked entities %.2f); sends in the tracker %.0f/tick",
                tracker / 1e6 / t, chunkTracking / 1e6 / t, updatePlayers / 1e6 / t, updatePlayersN / t, updatePlayer / t, sendChanges / 1e6 / t,
                sendChangesN / t, pairing / 1e6 / t, pairingN / t, sends / t, sendMean, sends * sendMean / 1e6 / t, nextChunks / 1e6 / t,
                chunkPackets / t, chunkPackets == 0 ? 0 : chunkPacketNs / 1e3 / chunkPackets, connTick / 1e6 / t, doTick / 1e6 / t, moves / t, moveNs / 1e6 / t,
                cmove / 1e6 / t, cmoveN / t, cmoveTrack / 1e6 / t, sendsTracker / t);
    }
}
