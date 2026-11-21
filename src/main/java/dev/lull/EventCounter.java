package dev.lull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

/**
 * Counts posts of every concrete event type on the game bus, without a hand-written list.
 *
 * <p>Discovery: {@code EventBus.listenerLists} (a {@code LockHelper<Class, ListenerList>}) gets an entry the
 * first time a type is posted or listened to. The bus module is declared {@code open}, so plain reflection
 * works. Every second we look for new keys and attach a LOWEST-priority, receiveCanceled listener to each.
 * A type posted for the very first time is therefore missed for at most ~1 s.
 *
 * <p>Side effect to keep in mind: while counting, every event has at least one listener, so this mode
 * measures <b>how often</b> events fire, not how much a listener-free post costs (that is what
 * {@code /sleep bench} is for). {@link #stop()} detaches all counting listeners again.
 */
public final class EventCounter {
    static final EventCounter INSTANCE = new EventCounter();

    /** every counting consumer ever attached (identity): AsyncChunkSave tells them from real listeners */
    private static final java.util.Set<Object> COUNTING = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static volatile java.lang.reflect.Field consumerField;

    /**
     * A listener of the bus that is one of these counters (a ConsumerEventHandler around a counting consumer): it only
     * counts posts, it never looks at an event's data.
     */
    public static boolean isCounting(Object busListener) {
        try {
            java.lang.reflect.Field f = consumerField;
            if (f == null) {
                Class<?> c = busListener.getClass();
                while (c != null && !c.getName().equals("net.neoforged.bus.ConsumerEventHandler")) c = c.getSuperclass();
                if (c == null) return false;
                f = c.getDeclaredField("consumer");
                f.setAccessible(true);
                consumerField = f;
            }
            if (!f.getDeclaringClass().isInstance(busListener)) return false;
            return COUNTING.contains(f.get(busListener));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static final int DISCOVERY_PERIOD_TICKS = 20;

    static final class Counter {
        final Class<?> type;
        final LongAdder serverThread = new LongAdder();
        final LongAdder otherThreads = new LongAdder();
        Consumer<? extends Event> listener;
        boolean rejected;
        /** Listeners on this exact type at stop time, excluding our own counting listener. -1 = unknown. */
        int listeners = -1;

        Counter(Class<?> type) {
            this.type = type;
        }

        long total() {
            return serverThread.sum() + otherThreads.sum();
        }
    }

    private final Map<Class<?>, Counter> counters = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile Thread serverThread;
    private IEventBus bus;
    private Object listenerLists;
    private Method getReadMap;
    private int tickCounter;
    private long startNanos;
    private long stopNanos;

    private EventCounter() {}

    void attach(IEventBus bus) {
        this.bus = bus;
        try {
            Object real = bus instanceof dev.lull.optsupport.CompositeEventBus c ? c.delegate() : bus;
            Field f = real.getClass().getDeclaredField("listenerLists");
            f.setAccessible(true);
            listenerLists = f.get(real);
            getReadMap = listenerLists.getClass().getDeclaredMethod("getReadMap");
            getReadMap.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            SleepMod.LOGGER.error("Cannot reach EventBus internals ({}); event counting disabled", bus.getClass(), e);
            listenerLists = null;
        }
    }

    /** -Dsleep.noEventCount=true: no counting listeners (they force every event site of the bus onto the full post path) */
    private static final boolean OFF = Boolean.getBoolean("sleep.noEventCount");

    boolean available() {
        return listenerLists != null && !OFF;
    }

    boolean enabled() {
        return enabled;
    }

    /** Server thread, from ServerTickEvent.Pre. */
    void onServerTick(Thread thread) {
        serverThread = thread;
        if (enabled && ++tickCounter % DISCOVERY_PERIOD_TICKS == 0) {
            discover();
        }
    }

    void start() {
        if (!available()) {
            return;
        }
        for (Counter c : counters.values()) {
            c.serverThread.reset();
            c.otherThreads.reset();
        }
        startNanos = System.nanoTime();
        enabled = true;
        discover();
    }

    void stop() {
        enabled = false;
        stopNanos = System.nanoTime();
        captureListenerCounts();
        for (Counter c : counters.values()) {
            if (c.listener != null) {
                bus.unregister(c.listener);
                COUNTING.remove(c.listener);
                c.listener = null;
            }
        }
    }

    double windowSeconds() {
        long end = enabled ? System.nanoTime() : stopNanos;
        return Math.max(1e-9, (end - startNanos) / 1e9);
    }

    int trackedTypes() {
        return (int) counters.values().stream().filter(c -> c.listener != null).count();
    }

    List<Counter> snapshotSorted() {
        List<Counter> list = new ArrayList<>(counters.values());
        list.removeIf(c -> c.total() == 0);
        list.sort(Comparator.comparingLong(Counter::total).reversed());
        return list;
    }

    private void discover() {
        Map<?, ?> map;
        try {
            map = (Map<?, ?>) getReadMap.invoke(listenerLists);
        } catch (ReflectiveOperationException e) {
            SleepMod.LOGGER.error("EventBus discovery failed", e);
            return;
        }
        for (Object key : map.keySet().toArray()) {
            if (key instanceof Class<?> type && isCountable(type)) {
                Counter c = counters.computeIfAbsent(type, Counter::new);
                if (c.listener == null && !c.rejected) {
                    subscribe(c);
                }
            }
        }
    }

    /** ListenerList.getListeners() includes listeners of parent event types, i.e. what one post really invokes. */
    private void captureListenerCounts() {
        try {
            Map<?, ?> map = (Map<?, ?>) getReadMap.invoke(listenerLists);
            for (Counter c : counters.values()) {
                Object list = map.get(c.type);
                if (list != null) {
                    Object[] arr = (Object[]) list.getClass().getMethod("getListeners").invoke(list);
                    c.listeners = arr.length - (c.listener != null ? 1 : 0);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            SleepMod.LOGGER.warn("Cannot read listener counts", e);
        }
    }

    private static boolean isCountable(Class<?> type) {
        return Event.class.isAssignableFrom(type)
                && !Modifier.isAbstract(type.getModifiers())
                && !type.getName().startsWith("dev.lull.") && !type.getName().startsWith("dev.probe.");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void subscribe(Counter c) {
        Class type = c.type;
        Consumer<Event> listener = e -> {
            // Exact class match: a listener on a concrete parent also receives its subclasses.
            if (enabled && e.getClass() == type) {
                (Thread.currentThread() == serverThread ? c.serverThread : c.otherThreads).increment();
            }
        };
        try {
            COUNTING.add(listener);
            bus.addListener(EventPriority.LOWEST, true, type, listener);
            c.listener = listener;
        } catch (IllegalArgumentException e) {
            // e.g. mod-bus-only event types rejected by the game bus class checker
            c.rejected = true;
        }
    }
}
