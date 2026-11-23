package dev.compatprobe;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * -Dsleep.test.tickListener=true (a test, not shipped behaviour): a mod-like LivingTickEvent listener (1.20.1: one event at the start of LivingEntity.tick) that is not
 * thread-safe on purpose - a plain HashMap of counts per entity type and a plain "inside" flag. With Maximum speed's
 * run lock (CompositeEventBus.RUN_POSTS) the listeners never overlap: overlaps 0, Pre == Post for ticked entities,
 * the map intact. Printed when the server stops.
 */
public final class TickListenerProbe {
    private TickListenerProbe() {}

    private static final Map<String, long[]> COUNTS = new HashMap<>();
    private static boolean inside;
    private static long overlaps, pre, post, workerCalls;

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener((LivingEvent.LivingTickEvent e) -> {
            if (e.getEntity().level().isClientSide()) return;
            enter();
            try {
                pre++;
                post++;
                COUNTS.computeIfAbsent(e.getEntity().getType().toShortString(), k -> new long[2])[0]++;
                COUNTS.get(e.getEntity().getType().toShortString())[1]++;
            } finally {
                inside = false;
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            long a = 0, b = 0;
            for (long[] c : COUNTS.values()) {
                a += c[0];
                b += c[1];
            }
            System.out.println("[probe/tick] pre " + pre + ", post " + post + ", map pre " + a + " post " + b + ", types " + COUNTS.size()
                    + ", overlaps " + overlaps + ", calls off the server thread " + workerCalls + ", posts under the run lock " + dev.lull.optsupport.CompositeEventBus.lockedPosts);
        });
        System.out.println("[probe/tick] a thread-unsafe LivingTickEvent listener registered");
    }

    private static void enter() {
        if (inside) overlaps++;
        inside = true;
        if (!Thread.currentThread().getName().equals("Server thread")) workerCalls++;
        // a little work inside, so overlapping calls would be seen
        long x = 0;
        for (int i = 0; i < 200; i++) x += i * 31L;
        if (x == 42) System.out.println();
    }
}
