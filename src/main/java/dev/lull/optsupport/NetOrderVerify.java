package dev.lull.optsupport;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * -Dlab.opt.NetOrderVerify=true (measurement only, with NetReactor; notes/91): every connection's packets reach netty's
 * write (Connection.doSendPacket) in the order each sending thread handed them to Connection.sendPacket - the order
 * NetReactor's batches and NetFlushLater's later hand-overs must keep (sends of different threads race in vanilla too,
 * so the order is checked per sending thread). A packet that arrives while an older packet of its thread is still
 * waiting is out of order; a packet recorded nowhere (sent through a path this check does not see) is counted apart.
 */
public final class NetOrderVerify {
    public static final boolean ON = Boolean.getBoolean("lab.opt.NetOrderVerify");
    public static final LongAdder IN_ORDER = new LongAdder(), OUT_OF_ORDER = new LongAdder(), UNRECORDED = new LongAdder();
    private static final AtomicInteger LOGGED = new AtomicInteger();

    private NetOrderVerify() {}

    /** the connection mixin's state (NetOrderVerifyMixin) - for writes that bypass doSendPacket (1.20.1's batch drain) */
    public interface Holder {
        State sleep$orderState();
    }

    /** one connection's packets in flight, a FIFO per sending thread */
    public static final class State {
        private Thread[] threads = new Thread[4];
        @SuppressWarnings("unchecked")
        private ArrayDeque<Object>[] queues = new ArrayDeque[4];
        private int n;

        /** Connection.sendPacket's head (the sending thread) */
        public synchronized void in(Object packet) {
            Thread t = Thread.currentThread();
            int i = 0;
            while (i < n && threads[i] != t) i++;
            if (i == n) {
                if (n == threads.length) {
                    threads = java.util.Arrays.copyOf(threads, n * 2);
                    queues = java.util.Arrays.copyOf(queues, n * 2);
                }
                threads[n] = t;
                queues[n] = new ArrayDeque<>();
                n++;
            }
            queues[i].addLast(packet);
        }

        /** Connection.doSendPacket's head (the event loop) */
        public void out(Object packet) {
            String bad = null;
            boolean found = false;
            synchronized (this) {
                for (int i = 0; i < n; i++) {
                    if (queues[i].peekFirst() == packet) {
                        queues[i].pollFirst();
                        IN_ORDER.increment();
                        return;
                    }
                }
                for (int i = 0; i < n && !found; i++) {
                    if (queues[i].contains(packet)) {
                        found = true;
                        Object head = queues[i].peekFirst();
                        bad = String.format(Locale.ROOT, "%s from %s while %s (sent before it, %d waiting) has not been written",
                                packet.getClass().getSimpleName(), threads[i].getName(), head == null ? "?" : head.getClass().getSimpleName(), queues[i].size());
                        Object o;
                        while ((o = queues[i].pollFirst()) != null && o != packet) {}   // resynchronise behind it
                    }
                }
            }
            if (!found) {
                UNRECORDED.increment();
                return;
            }
            OUT_OF_ORDER.increment();
            if (LOGGED.getAndIncrement() < 10) System.out.println("[sleep/opt] NetOrderVerify: out of order: " + bad);
        }
    }

    public static void register() {
        dev.lull.bots.BotLoad.WINDOWS.add(new dev.lull.bots.BotLoad.Window() {
            public void start() {}

            public String end(long t) {
                return report();
            }
        });
    }

    /** cumulative since the start */
    public static String report() {
        return String.format(Locale.ROOT, "netorder: %d packets written in their thread's order, %d out of order, %d unrecorded",
                IN_ORDER.sum(), OUT_OF_ORDER.sum(), UNRECORDED.sum());
    }
}
