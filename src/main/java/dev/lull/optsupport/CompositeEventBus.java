package dev.lull.optsupport;

import dev.lull.bus.CompositeDispatcher;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.IEventExceptionHandler;

/**
 * exp/bus-composite: NeoForge.EVENT_BUS (installed by dev.lull.opt.NeoForgeBusMixin) - an EventBus itself, built from
 * NeoForge's own builder, not a wrapper around one: mods that reach into the bus' internals (Gnetum: privateLookupIn
 * EventBus, its shutdown / exceptionHandler fields, its private getListenerList and doPostChecks, NeoForge.EVENT_BUS
 * cast to EventBus) find the real state - one state, the listeners included (up to 1.1.4 a wrapper: ClassCastException).
 *
 * <p>Registration, unregistration, start() and phase posts are EventBus' own (super), unchanged.
 * {@link #post(Event)} dispatches through a {@link CompositeDispatcher} generated for the event's listener list.
 * A rebuilt list is detected by array identity: {@code ListenerList.getListeners()} returns a new array after any
 * (un)registration, so no invalidation hooks are needed.
 *
 * <p>Vanilla post semantics that are kept: the shutdown flag (read from the delegate), checkTypesOnDispatch (then the
 * wrapper simply falls back to the delegate), and the exception handler, called with index -1 because the unrolled
 * code does not know which listener threw.
 */
public final class CompositeEventBus extends EventBus {
    private static final CompositeDispatcher.Dispatch EMPTY = e -> {};

    /** Inlinable no-listener fast path in post() ({@code -Dlab.opt.busFastPath=false} disables it for A/B). */
    static final boolean FAST_PATH = !"false".equals(System.getProperty("lab.opt.busFastPath"));

    /** Lists this short use a plain loop (same cost as vanilla); longer ones get a generated composite. */
    static final int COMPOSITE_MIN = Integer.getInteger("lab.opt.compositeMin", 3);

    private record Built(ListenerList list, EventListener[] source, CompositeDispatcher.Dispatch dispatch) {}

    private static final class Holder {
        volatile Built built;   // one volatile read on the hot path
        final java.util.concurrent.atomic.LongAdder posts = new java.util.concurrent.atomic.LongAdder();
    }

    // ---- optional statistics (-Dlab.opt.busStats=true): which path every post takes, and posts per event type
    public static final boolean STATS = Boolean.getBoolean("lab.opt.busStats");
    public static final String[] PATH_NAMES = {"composite (generated dispatcher)", "short list (plain loop)",
            "no listeners", "fallback: first post of a type", "fallback: shut down / type checks", "phase post (delegated)"};
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

    private final EventBus delegate;
    /**
     * EventBus.shutdown, read on every post: a VarHandle the JIT folds into a plain volatile read only from a static
     * final field (an instance field went through VarHandleGuards' checks on each call: 0.9 % of the server thread in
     * a 200-mod pack's profile)
     */
    private static final VarHandle SHUTDOWN = shutdownHandle();

    private static VarHandle shutdownHandle() {
        try {
            return MethodHandles.privateLookupIn(EventBus.class, MethodHandles.lookup()).findVarHandle(EventBus.class, "shutdown", boolean.class);
        } catch (ReflectiveOperationException e) {
            return null;   // the constructor reports it
        }
    }
    private final boolean fallbackAlways;
    private final IEventExceptionHandler exceptionHandler;
    private final Object listenerLists;
    private final Method getReadMap;
    private final ClassValue<Holder> holders = new ClassValue<>() {
        @Override
        protected Holder computeValue(Class<?> type) {
            Holder h = new Holder();
            if (STATS) TYPES.put(type, h);
            return h;
        }
    };

    public CompositeEventBus(net.neoforged.bus.BusBuilderImpl builder) {
        super(builder);
        this.delegate = this;   // (the fields below are EventBus' own, read on this instance)
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(EventBus.class, MethodHandles.lookup());
            if (SHUTDOWN == null) throw new NoSuchFieldException("EventBus.shutdown");
            this.fallbackAlways = (boolean) lookup.findVarHandle(EventBus.class, "checkTypesOnDispatch", boolean.class).get(delegate);
            this.exceptionHandler = (IEventExceptionHandler) lookup.findVarHandle(EventBus.class, "exceptionHandler",
                    IEventExceptionHandler.class).get(delegate);
            var f = EventBus.class.getDeclaredField("listenerLists");
            f.setAccessible(true);
            this.listenerLists = f.get(delegate);
            this.getReadMap = listenerLists.getClass().getDeclaredMethod("getReadMap");
            this.getReadMap.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot wrap EventBus", e);
        }
        System.out.println("[sleep/opt] NeoForge.EVENT_BUS is a CompositeEventBus (an EventBus of its own)");
    }

    /** the EventBus holding the listeners: this one (it was a wrapped bus up to 1.1.4) */
    public EventBus delegate() {
        return delegate;
    }

    @Override
    public <T extends Event> T post(T event) {
        if (FAST_PATH && !STATS) {
            // Tiny, inlinable path for the common case "nobody listens" (97-99 % of posts): when the JIT inlines it
            // into the call site, escape analysis can drop the event allocation entirely. Every condition that
            // matters for exactness is checked; anything else goes to the full path.
            Built b = holders.get(event.getClass()).built;
            if (b != null && b.source().length == 0 && b.list().getListeners() == b.source() && !fallbackAlways
                    && !(boolean) SHUTDOWN.getVolatile(delegate)) {
                return event;
            }
        }
        return postFull(event);
    }

    /**
     * True if posting {@code type} would invoke at least one listener (listeners of parent types included), or if
     * that is not known yet (type never posted): callers may skip building and posting an event only on false.
     */
    public boolean hasListeners(Class<?> type) {
        if (fallbackAlways || (boolean) SHUTDOWN.getVolatile(delegate)) return true;
        Built b = holders.get(type).built;
        if (b != null) return b.list().getListeners().length > 0;
        ListenerList list = lookupList(type);
        return list == null || list.getListeners().length > 0;
    }

    /**
     * Like hasListeners, but a type never posted is answered too: the bus creates a type's listener list when a listener
     * registers for it or when it is first posted, so no list means nobody registered for it; what a first post would
     * find then are its parent types' listeners.
     */
    public boolean mayHaveListeners(Class<?> type) {
        if (fallbackAlways || (boolean) SHUTDOWN.getVolatile(delegate)) return true;
        Built b = holders.get(type).built;
        if (b != null) return b.list().getListeners().length > 0;
        ListenerList list = lookupList(type);
        if (list != null) return list.getListeners().length > 0;
        for (Class<?> c = type.getSuperclass(); c != null && Event.class.isAssignableFrom(c); c = c.getSuperclass()) {
            ListenerList pl = lookupList(c);
            if (pl != null && pl.getListeners().length > 0) return true;
        }
        return false;
    }

    private static final boolean PHYSICAL_CLIENT = net.neoforged.fml.loading.FMLEnvironment.dist.isClient();

    /** Full post: first posts, listener changes, dispatch, fallbacks and statistics. */
    /** posts that took the full path (somebody listens, or not known): MoveMemo's "no event with listeners" */
    public static long fullPosts;

    private <T extends Event> T postFull(T event) {
        fullPosts++;
        // ParallelRender: a mod renderer posting an event somebody listens to (GeckoLib's GeoRenderEvent) from a worker job:
        // the listener must not run there; the job goes to the render thread and the type stays there
        // (client only: ParallelRender is a client class; touching it on a dedicated server loads client classes)
        if (PHYSICAL_CLIENT && ParallelRender.inJob() && mayHaveListeners(event.getClass())) throw ParallelRender.REFUSE_LISTENED;
        if (fallbackAlways || (boolean) SHUTDOWN.getVolatile(delegate)) {
            if (STATS) count(4, holders.get(event.getClass()));
            return super.post(event);   // identical vanilla behaviour (incl. returning early when shut down)
        }
        Holder h = holders.get(event.getClass());
        Built b = h.built;
        if (b == null) {
            ListenerList list = lookupList(event.getClass());
            if (list == null) {
                if (STATS) count(3, h);
                return super.post(event);   // first ever post of this type: let the bus create the list
            }
            b = build(h, list, list.getListeners());
        }
        EventListener[] current = b.list().getListeners();
        if (current != b.source()) {
            b = build(h, b.list(), current);   // listener set changed since the composite was generated
        }
        int n = current.length;
        if (STATS) count(n == 0 ? 2 : n < COMPOSITE_MIN ? 1 : 0, h);
        if (n == 0) {
            return event;
        }
        if ((ModProfile.ON_SERVER || ModProfile.ON_CLIENT) && ModProfile.timingHere()) {   // the Mods page's recording (the HUD: 1 post in 16): each listener timed, the plain loop (what the composite does)
            int i = 0;
            try {
                for (; i < n; i++) {
                    long t0 = System.nanoTime();
                    current[i].invoke(event);
                    ModProfile.listener(current[i], event, System.nanoTime() - t0);
                }
            } catch (Throwable t) {
                exceptionHandler.handleException(this, event, current, i, t);
                throw t;
            }
            return event;
        }
        if (n < COMPOSITE_MIN) {
            int i = 0;
            try {
                for (; i < n; i++) current[i].invoke(event);
            } catch (Throwable t) {
                exceptionHandler.handleException(this, event, current, i, t);
                throw t;
            }
            return event;
        }
        try {
            b.dispatch().dispatch(event);
        } catch (Throwable t) {
            exceptionHandler.handleException(this, event, current, -1, t);
            throw t;
        }
        return event;
    }

    private static Built build(Holder h, ListenerList list, EventListener[] current) {
        Built b = new Built(list, current, current.length < COMPOSITE_MIN ? EMPTY : CompositeDispatcher.create(current));
        h.built = b;
        return b;
    }

    /** the listeners posting type would call (EventPriority markers included), or null if not known */
    public net.neoforged.bus.api.EventListener[] listenersOf(Class<?> type) {
        if (fallbackAlways || (boolean) SHUTDOWN.getVolatile(delegate)) return null;
        ListenerList list = lookupList(type);
        return list == null ? null : list.getListeners();
    }

    private ListenerList lookupList(Class<?> type) {
        try {
            return (ListenerList) ((Map<?, ?>) getReadMap.invoke(listenerLists)).get(type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- everything else: EventBus' own (super)

    @Override public <T extends Event> T post(EventPriority phase, T event) {
        if (STATS) count(5, holders.get(event.getClass()));
        return super.post(phase, event);
    }
    // listener changes: every allocation-free event site back to the normal post, before and after (EventSites)
    /** the objects and classes given to register, in order (ParallelEntities finds a generated listener's method there) */
    private final java.util.List<Object> targets = new java.util.concurrent.CopyOnWriteArrayList<>();

    public java.util.List<Object> registered() { return targets; }

    @Override public void register(Object target) { EventSites.invalidate(); super.register(target); targets.add(target); EventSites.invalidate(); }
    @Override public void unregister(Object object) { EventSites.invalidate(); super.unregister(object); EventSites.invalidate(); }
    @Override public void start() { EventSites.invalidate(); super.start(); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(Consumer<T> consumer) { EventSites.invalidate(); super.addListener(consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(eventType, consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(priority, consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(EventPriority priority, Class<T> eventType, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(priority, eventType, consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(priority, receiveCanceled, consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(priority, receiveCanceled, eventType, consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(boolean receiveCanceled, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(receiveCanceled, consumer); EventSites.invalidate(); }
    @Override public <T extends Event> void addListener(boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) { EventSites.invalidate(); super.addListener(receiveCanceled, eventType, consumer); EventSites.invalidate(); }
}
