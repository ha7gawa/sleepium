package dev.lull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.GenericEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.IEventListener;

/**
 * Counts posts of every concrete event type on the game bus, without a hand-written list.
 *
 * <p>Discovery: Forge's eventbus 6 keeps one ListenerList per event class for all buses, in
 * {@code EventListenerHelper.listeners} (a {@code LockHelper<Class, ListenerList>} - 1.21.1: NeoForge's per-bus
 * {@code EventBus.listenerLists}); a class gets its entry the first time it is posted or listened to on any bus. The
 * eventbus module is open, so plain reflection works; the keys are copied under the LockHelper's read lock. Every
 * second we look for new keys and attach a LOWEST-priority, receiveCanceled listener to each. A type posted for the
 * very first time is therefore missed for at most ~1 s. Types of other buses (the mod buses) are found too: their
 * counting listeners are refused or never called, and they stay at zero (left out of the report).
 *
 * <p>1.20.1: generic events (AttachCapabilitiesEvent, RegistryEvent-like) take listeners only through
 * addGenericListener with their exact generic type, which the counter does not know: they are not counted (listed as
 * rejected in the report's count of types).
 *
 * <p>Side effect to keep in mind: while counting, every event has at least one listener, so this mode
 * measures <b>how often</b> events fire, not how much a listener-free post costs (that is what
 * {@code /sleep bench} is for). {@link #stop()} detaches all counting listeners again.
 */
final class EventCounter {
    static final EventCounter INSTANCE = new EventCounter();

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
    /** EventListenerHelper.listeners' map and lock (null: not reachable) */
    private Map<?, ?> listenerLists;
    /** the helper's lock: a ReadWriteLock (eventbus 6.0, LockHelper) or a plain monitor (6.2 in Forge 47.4.2x, internal.Cache) */
    private Object listenerLock;
    private int busId = -1;
    private int tickCounter;
    private long startNanos;
    private long stopNanos;

    private EventCounter() {}

    void attach(IEventBus bus) {
        this.bus = bus;
        try {
            Field f = EventListenerHelper.class.getDeclaredField("listeners");
            f.setAccessible(true);
            Object helper = f.get(null);
            Field m = helper.getClass().getDeclaredField("map");
            m.setAccessible(true);
            Field l = helper.getClass().getDeclaredField("lock");
            l.setAccessible(true);
            listenerLists = (Map<?, ?>) m.get(helper);
            listenerLock = l.get(helper);
            if (listenerLock == null) throw new IllegalStateException("no lock in " + helper.getClass());
            busId = BusAnatomy.busId(bus);
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

    /** Server thread, from ServerTickEvent (START). */
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

    int rejectedTypes() {
        return (int) counters.values().stream().filter(c -> c.rejected).count();
    }

    List<Counter> snapshotSorted() {
        List<Counter> list = new ArrayList<>(counters.values());
        list.removeIf(c -> c.total() == 0);
        list.sort(Comparator.comparingLong(Counter::total).reversed());
        return list;
    }

    /** the classes with a listener list now (copied under the helper's read lock: other threads post and register) */
    private Object[] knownTypes() {
        if (listenerLock instanceof ReadWriteLock rw) {
            rw.readLock().lock();
            try {
                return listenerLists.keySet().toArray();
            } finally {
                rw.readLock().unlock();
            }
        }
        synchronized (listenerLock) {   // eventbus 6.2: the cache writes its map under synchronized (lock)
            return listenerLists.keySet().toArray();
        }
    }

    private void discover() {
        for (Object key : knownTypes()) {
            if (key instanceof Class<?> type && isCountable(type)) {
                Counter c = counters.computeIfAbsent(type, Counter::new);
                if (c.listener == null && !c.rejected) {
                    subscribe(c);
                }
            }
        }
    }

    /**
     * A ListenerList's array holds the listeners of parent event types too, i.e. what one post really invokes, and
     * (Forge) one EventPriority marker per priority present: those are not listeners.
     */
    private void captureListenerCounts() {
        if (busId < 0) return;
        try {
            for (Counter c : counters.values()) {
                if (c.rejected) continue;
                int n = 0;
                for (IEventListener l : EventListenerHelper.getListenerList(c.type).getListeners(busId)) if (!(l instanceof EventPriority)) n++;
                c.listeners = n - (c.listener != null ? 1 : 0);
            }
        } catch (RuntimeException e) {
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
        if (GenericEvent.class.isAssignableFrom(type)) {   // addListener refuses them (see the class comment)
            c.rejected = true;
            return;
        }
        Consumer<Event> listener = e -> {
            // Exact class match: a listener on a concrete parent also receives its subclasses.
            if (enabled && e.getClass() == type) {
                (Thread.currentThread() == serverThread ? c.serverThread : c.otherThreads).increment();
            }
        };
        try {
            bus.addListener(EventPriority.LOWEST, true, type, listener);
            c.listener = listener;
        } catch (IllegalArgumentException e) {
            // e.g. mod-bus-only event types (IModBusEvent) rejected by the game bus
            c.rejected = true;
        }
    }
}
