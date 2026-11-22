package dev.lull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a player's chunk waits on its way (notes/92, measurement only: -Dsleep.serveMeter=true
 * -Dsleep.serveMeter.timeline=true): per chunk position the first time of each stage -
 * <ol start="0">
 *   <li>REQ: the player-ticket tracker wants a ticket (DistanceManager.PlayerTicketTracker.onLevelChange) - it goes
 *       through the ticket throttler (4 in flight in vanilla) and a main-thread hop;</li>
 *   <li>ADD: the PLAYER ticket is added (DistanceManager.addTicket);</li>
 *   <li>NOISE: generation's noise step starts (ChunkStatusTasks.generateNoise; often before REQ - a neighbour's ticket
 *       already asked for it at a lower status);</li>
 *   <li>LIT: the light step's future completes (ChunkStatusTasks.light);</li>
 *   <li>FULL: the full step's future completes (ChunkStatusTasks.full);</li>
 *   <li>READY: ready to send (ChunkMap.onChunkReadyToSend: ticking, its neighbours full, send dependencies done);</li>
 *   <li>VIEW: it enters a player's view (ChunkMap.markChunkPendingToSend(player, pos): from then on a missing chunk is a
 *       hole on that player's screen);</li>
 *   <li>SENT: written for a player (PlayerChunkSender.sendChunk).</li>
 * </ol>
 * The report, for the chunks sent in the window: the felt wait VIEW -> SENT, and for those not ready when they entered
 * the view, how long after it each later stage came (what the view waited for).
 */
public final class ChunkTimeline {
    public static final boolean ON = Boolean.getBoolean("sleep.serveMeter") && Boolean.getBoolean("sleep.serveMeter.timeline");
    public static final int REQ = 0, ADD = 1, NOISE = 2, LIT = 3, FULL = 4, READY = 5, VIEW = 6, SENT = 7;
    private static final String[] NAMES = {"req", "ticket", "noise", "lit", "full", "ready", "view", "sent"};
    private static final ConcurrentHashMap<Long, long[]> T = new ConcurrentHashMap<>();
    private static volatile long windowStart;

    private ChunkTimeline() {}

    public static void stamp(long pos, int stage) {
        if (!ON) return;
        long now = System.nanoTime();
        long[] a = T.computeIfAbsent(pos, k -> new long[8]);
        synchronized (a) {
            if (a[SENT] != 0 && (stage == VIEW || stage == REQ)) Arrays.fill(a, 0);   // a new trip after it was sent
            if (a[stage] == 0) a[stage] = now;
        }
    }

    public static void start() {
        T.clear();
        windowStart = System.nanoTime();
    }

    private static String stat(ArrayList<Long> v) {
        if (v.isEmpty()) return "-";
        long[] s = v.stream().mapToLong(Long::longValue).sorted().toArray();
        return String.format(Locale.ROOT, "%.0f/%.0f/%.0f", s[s.length / 2] / 1e6, s[Math.min(s.length - 1, (int) (s.length * 0.9))] / 1e6, s[s.length - 1] / 1e6);
    }

    public static String report() {
        if (!ON) return "";
        long w0 = windowStart;
        ArrayList<Long> felt = new ArrayList<>(), feltLate = new ArrayList<>();
        ArrayList<ArrayList<Long>> after = new ArrayList<>();   // stage - VIEW for the chunks not ready at VIEW
        for (int i = 0; i < 8; i++) after.add(new ArrayList<>());
        int[] atView = new int[8];   // the last stage reached when the chunk entered the view
        int sent = 0, late = 0, unsentInView = 0;
        for (long[] a0 : T.values()) {
            long[] a;
            synchronized (a0) {
                a = a0.clone();
            }
            if (a[VIEW] == 0 || a[VIEW] < w0) continue;
            if (a[SENT] == 0) {
                unsentInView++;
                continue;
            }
            sent++;
            felt.add(a[SENT] - a[VIEW]);
            int last = -1;
            for (int s = REQ; s <= READY; s++) if (a[s] != 0 && a[s] <= a[VIEW]) last = s;
            atView[last < 0 ? VIEW : last]++;
            if (a[READY] == 0 || a[READY] > a[VIEW]) {
                late++;
                feltLate.add(a[SENT] - a[VIEW]);
                for (int s = REQ; s <= READY; s++) if (a[s] != 0) after.get(s).add(a[s] - a[VIEW]);
            }
        }
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
                "chunk timeline: %d entered a view and were sent, view -> sent %s ms (median/p90/max); %d of them not ready at view (view -> sent %s), %d in view not sent yet; last stage at view:",
                sent, stat(felt), late, stat(feltLate), unsentInView));
        for (int s = REQ; s <= READY; s++) b.append(' ').append(NAMES[s]).append(' ').append(atView[s]);
        b.append(" none ").append(atView[VIEW]).append("; for the late ones, stage - view:");
        for (int s = REQ; s <= READY; s++) b.append(' ').append(NAMES[s]).append(' ').append(stat(after.get(s))).append(" (n=").append(after.get(s).size()).append(')');
        return b.toString();
    }
}
