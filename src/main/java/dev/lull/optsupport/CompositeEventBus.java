package dev.lull.optsupport;

import dev.lull.bus.CompositeDispatcher;
import java.util.function.Consumer;
import net.minecraftforge.eventbus.EventBus;
import net.minecraftforge.eventbus.ListenerList;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.GenericEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher;
import net.minecraftforge.eventbus.api.IEventExceptionHandler;
import net.minecraftforge.eventbus.api.IEventListener;

/**
 * MinecraftForge.EVENT_BUS (installed by dev.lull.opt.ForgeBusMixin); the 1.20.1 / Forge eventbus 6 form of 1.21.1's.
 * An EventBus itself, built from MinecraftForge's own builder, not a wrapper around one: mods that reach into the bus'
 * internals (Gnetum: its private fields and methods, MinecraftForge.EVENT_BUS cast to EventBus) find the real state -
 * one state, the listeners included (up to 1.1.4 a wrapper: ClassCastException).
 *
 * <p>Registration, unregistration, start() / shutdown() and posts with an invoke dispatcher are EventBus' own (super),
 * unchanged. {@link #post(Event)} dispatches through a {@link CompositeDispatcher} generated for the event class's
 * listener array. Forge keeps one static ListenerList per event class (parents' listeners merged in) with one array per
 * bus id; the array is rebuilt - a new array - after any (un)registration, so a changed set is seen by identity.
 *
 * <p>Vanilla post semantics that are kept: shut down (MinecraftForge builds the bus shut down until Forge starts it;
 * the wrapper sees start() / shutdown() itself and posts through the delegate while not started), checkTypesOnDispatch
 * (then always the delegate), phase tracking (EventPriority entries of the array are invoked, they set the event's
 * phase), the result ({@code isCancelable() && isCanceled()}) and the exception handler (the bus itself: MinecraftForge
 * gives none), called with index -1 from the generated code, which does not know which listener threw.
 */
public final class CompositeEventBus extends EventBus {
    /** Inlinable no-listener fast path in post() ({@code -Dlab.opt.busFastPath=false} disables it for A/B). */
    static final boolean FAST_PATH = !"false".equals(System.getProperty("lab.opt.busFastPath"));

    /** Lists this short use a plain loop (same cost as vanilla); longer ones get a generated composite. */
    static final int COMPOSITE_MIN = Integer.getInteger("lab.opt.compositeMin", 3);

    /** eventbus.checkTypesOnDispatch: the bus checks every event's type, the wrapper leaves every post to it */
    private static final boolean CHECK_TYPES = Boolean.parseBoolean(System.getProperty("eventbus.checkTypesOnDispatch", "false"));

    private record Built(IEventListener[] source, CompositeDispatcher.Dispatch dispatch) {}

    private static final class Holder {
        final ListenerList list;   // per class, for good (EventListenerHelper keeps it)
        volatile Built built;      // one volatile read on the hot path
        final java.util.concurrent.atomic.LongAdder posts = new java.util.concurrent.atomic.LongAdder();

        Holder(ListenerList list) {
            this.list = list;
        }
    }

    // ---- optional statistics (-Dlab.opt.busStats=true): which path every post takes, and posts per event type
    public static final boolean STATS = Boolean.getBoolean("lab.opt.busStats");
    public static final String[] PATH_NAMES = {"composite (generated dispatcher)", "short list (plain loop)",
            "no listeners", "fallback: not started", "fallback: type checks", "dispatcher post (delegated)"};
    private static final java.util.concurrent.atomic.LongAdder[] PATHS = new java.util.concurrent.atomic.LongAdder[6];
    private static final java.util.Map<Class<?>, Holder> TYPES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Long> LAST_TYPES = new java.util.HashMap<>();

    static {
        for (int i = 0; i < PATHS.length; i++) PATHS[i] = new java.util.concurrent.atomic.LongAdder();
    }

    private static void count(int path, Holder h) {
        PATHS[path].increment();
        if (h != null) h.posts.increment();
    }

    public static long[] pathCounts() {
        long[] r = new long[PATHS.length];
        for (int i = 0; i < r.length; i++) r[i] = PATHS[i].sum();
        return r;
    }

    /** Top event types by posts since the previous call, per tick. */
    public static synchronized String topTypes(int n, long ticks) {
        java.util.List<java.util.Map.Entry<Class<?>, Long>> d = new java.util.ArrayList<>();
        for (var e : TYPES.entrySet()) {
            long now = e.getValue().posts.sum(), before = LAST_TYPES.getOrDefault(e.getKey(), 0L);
            LAST_TYPES.put(e.getKey(), now);
            if (now > before) d.add(java.util.Map.entry(e.getKey(), now - before));
        }
        d.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "     top event types (%d types posted):%n", d.size()));
        for (int i = 0; i < Math.min(n, d.size()); i++) {
            Class<?> c = d.get(i).getKey();
            String name = c.getName().substring(c.getName().lastIndexOf('.') + 1);
            sb.append(String.format(java.util.Locale.ROOT, "       %-45s %10.1f%n", name, d.get(i).getValue() / (double) ticks));
        }
        return sb.toString();
    }

    /** an event only the wrapper listens to: finds the bus id (EventBus.busID is private; one listener, then gone) */
    public static final class BusProbe extends Event {}

    private final EventBus delegate;
    private final int busId;
    private final boolean trackPhases;
    /** started and not shut down since: posts may take the wrapper's paths (else the delegate: shut down, events dropped) */
    private volatile boolean live;
    private final ClassValue<Holder> holders = new ClassValue<>() {
        @Override
        protected Holder computeValue(Class<?> type) {
            Holder h = new Holder(EventListenerHelper.getListenerList(type));
            if (STATS) TYPES.put(type, h);
            return h;
        }
    };

    public CompositeEventBus(net.minecraftforge.eventbus.BusBuilderImpl builder) {
        super(builder);
        this.delegate = this;   // (the fields read below are EventBus' own, on this instance)
        // the bus id: EventBus' own field (the eventbus module is open). Not unregister(): it walks every event's
        // listener list, and with mods constructed in parallel (a mod touching MinecraftForge in its constructor,
        // Immersive Fixes) a list another thread is creating is not sized for this id yet - AIOOBE, the game did not
        // start (1.1.5-1.1.6)
        int id = -1;
        try {
            var f = EventBus.class.getDeclaredField("busID");
            f.setAccessible(true);
            id = f.getInt(this);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // found below instead
        }
        if (id < 0) {   // a probe listener on an event nobody posts: found, then left (removing it is the race above)
            Consumer<BusProbe> probe = e -> {};
            super.addListener(EventPriority.NORMAL, false, BusProbe.class, probe);
            ListenerList pl = EventListenerHelper.getListenerList(BusProbe.class);
            for (int i = 0; i < 4096 && id < 0; i++) {
                try {
                    if (pl.getListeners(i).length > 0) id = i;
                } catch (RuntimeException end) {
                    break;   // past the lists' size
                }
            }
        }
        if (id < 0) throw new IllegalStateException("cannot find the event bus' id");
        this.busId = id;
        boolean phases = true;
        try {
            var f = EventBus.class.getDeclaredField("trackPhases");   // the eventbus module is open
            f.setAccessible(true);
            phases = f.getBoolean(delegate);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // true is the builder's default (MinecraftForge keeps it)
        }
        this.trackPhases = phases;
        System.out.println("[sleep/opt] MinecraftForge.EVENT_BUS is a CompositeEventBus (an EventBus of its own, bus id " + id + ")");
    }

    /** the EventBus holding the listeners: this one (it was a wrapped bus up to 1.1.4) */
    public EventBus delegate() {
        return delegate;
    }

    @Override
    public boolean post(Event event) {
        if (FAST_PATH && !STATS) {
            // Tiny, inlinable path for the common case "nobody listens": when the JIT inlines it into the call site,
            // escape analysis can drop the event allocation entirely. The result is the bus' own for an empty list.
            Holder h = holders.get(event.getClass());
            Built b = h.built;
            if (b != null && b.source().length == 0 && live && h.list.getListeners(busId) == b.source()) {
                return event.isCanceled() && event.isCancelable();
            }
        }
        return postFull(event);
    }

    /** True if posting {@code type} would invoke at least one listener (listeners of parent types included). */
    public boolean hasListeners(Class<?> type) {
        if (!live || CHECK_TYPES) return true;
        return holders.get(type).list.getListeners(busId).length > 0;
    }

    /** Forge's lists are made per class with their parents' listeners: the same answer as hasListeners. */
    public boolean mayHaveListeners(Class<?> type) {
        return hasListeners(type);
    }

    private static final boolean PHYSICAL_CLIENT = net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient();

    /** posts that took the full path (somebody listens, or not known): MoveMemo's "no event with listeners" */
    public static long fullPosts;

    /** Full post: listener changes, dispatch, fallbacks and statistics. */
    private boolean postFull(Event event) {
        fullPosts++;
        if (!live || CHECK_TYPES) {
            if (STATS) count(live ? 4 : 3, null);
            return super.post(event);   // identical vanilla behaviour (incl. returning early when shut down)
        }
        Holder h = holders.get(event.getClass());
        IEventListener[] current = h.list.getListeners(busId);
        Built b = h.built;
        if (b == null || current != b.source()) {
            b = build(h, current, event.isCancelable());   // first post, or the listener set changed since
        }
        int n = current.length;
        if (STATS) count(n == 0 ? 2 : n < COMPOSITE_MIN ? 1 : 0, h);
        if (n == 0) {
            return event.isCanceled() && event.isCancelable();
        }
        if (ModProfile.ON && ModProfile.take(dev.lull.optsupport.ModProfile.SERVER_EVENT)) {   // the Mods page's recording (the HUD: 1 post in 16): each listener timed, the plain loop
            int i = 0;
            try {
                for (; i < n; i++) {
                    if (!trackPhases && current[i].getClass() == EventPriority.class) continue;
                    long t0 = System.nanoTime();
                    current[i].invoke(event);
                    ModProfile.listener(current[i], event, System.nanoTime() - t0);
                }
            } catch (Throwable t) {
                ((IEventExceptionHandler) delegate).handleException(delegate, event, current, i, t);
                throw t;
            }
            return event.isCancelable() && event.isCanceled();
        }
        if (n < COMPOSITE_MIN) {
            int i = 0;
            try {
                for (; i < n; i++) {
                    if (!trackPhases && current[i].getClass() == EventPriority.class) continue;
                    current[i].invoke(event);
                }
            } catch (Throwable t) {
                ((IEventExceptionHandler) delegate).handleException(delegate, event, current, i, t);
                throw t;
            }
            return event.isCancelable() && event.isCanceled();
        }
        try {
            b.dispatch().dispatch(event);
        } catch (Throwable t) {
            ((IEventExceptionHandler) delegate).handleException(delegate, event, current, -1, t);
            throw t;
        }
        return event.isCancelable() && event.isCanceled();
    }

    private Built build(Holder h, IEventListener[] current, boolean cancelable) {
        Built b = new Built(current, current.length < COMPOSITE_MIN ? null : CompositeDispatcher.create(current, cancelable, trackPhases));
        h.built = b;
        return b;
    }

    // ---- everything else: EventBus' own (super)

    @Override public boolean post(Event event, IEventBusInvokeDispatcher wrapper) {
        if (STATS) count(5, null);
        return super.post(event, wrapper);
    }
    @Override public void start() { EventSites.invalidate(); super.start(); live = true; }
    @Override public void shutdown() { live = false; EventSites.invalidate(); super.shutdown(); }
    // listener changes: every allocation-free event site back to the normal post, before and after (EventSites)
    @Override public void register(Object target) { EventSites.invalidate(); super.register(target); EventSites.invalidate(); }
    @Override public void unregister(Object object) { EventSites.invalidate(); super.unregister(object); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(Consumer<T> consumer) { EventSites.invalidate(); super.addListener(consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(priority, consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(priority, receiveCanceled, consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(priority, receiveCanceled, eventType, consumer); EventSites.invalidate(); }
    @Override public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, Consumer<T> consumer) { EventSites.invalidate(); super.addGenericListener(genericClassFilter, consumer); EventSites.invalidate(); }
    @Override public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, Consumer<T> consumer) { EventSites.invalidate(); super.addGenericListener(genericClassFilter, priority, consumer); EventSites.invalidate(); }
    @Override public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, boolean receiveCanceled, Consumer<T> consumer) { EventSites.invalidate(); super.addGenericListener(genericClassFilter, priority, receiveCanceled, consumer); EventSites.invalidate(); }
    @Override public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) { EventSites.invalidate(); super.addGenericListener(genericClassFilter, priority, receiveCanceled, eventType, consumer); EventSites.invalidate(); }
}
