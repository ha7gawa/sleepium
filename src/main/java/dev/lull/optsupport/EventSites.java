package dev.lull.optsupport;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Allocation-free events nobody listens to (EventSiteMixin rewrites {@code MinecraftForge.EVENT_BUS.post(new E(...))}
 * in Forge's hook classes into an invokedynamic bound here, one call site per place).
 *
 * <p>Each site is a MutableCallSite. While type E has no listener, its target is the bus' answer for an empty listener
 * list ({@code isCancelable() && isCanceled()}, false for a fresh event): the JIT sees a constant target, inlines it,
 * and escape analysis removes the {@code new E(...)} at the caller altogether. Otherwise the target is the normal
 * {@code bus.post(event)}. Any listener registration anywhere switches every fast site back to the normal target first
 * (CompositeEventBus calls {@link #invalidate}); a site becomes fast again by itself after a post that finds no
 * listener. Only when MinecraftForge.EVENT_BUS is our CompositeEventBus.
 */
public final class EventSites {
    private static final List<Site> SITES = new ArrayList<>();
    private static final MethodHandle SLOW, FAST;
    public static volatile long sites, fastSwitches, invalidations;

    static {
        try {
            SLOW = MethodHandles.lookup().findStatic(EventSites.class, "slow",
                    MethodType.methodType(boolean.class, Site.class, IEventBus.class, Event.class));
            FAST = MethodHandles.lookup().findStatic(EventSites.class, "fast",
                    MethodType.methodType(boolean.class, IEventBus.class, Event.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private EventSites() {}

    static final class Site {
        final Class<?> type;
        final MutableCallSite cs;
        final MethodHandle fast, slow;
        volatile boolean isFast;

        Site(Class<?> type, MethodType mt) {
            this.type = type;
            this.cs = new MutableCallSite(mt);
            this.fast = FAST.asType(mt);
            this.slow = MethodHandles.insertArguments(SLOW, 0, this).asType(mt);
            cs.setTarget(slow);
        }
    }

    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type, Class<?> eventType) {
        Site s = new Site(eventType, type);
        synchronized (SITES) {
            SITES.add(s);
            sites = SITES.size();
        }
        return s.cs;
    }

    /** what EventBus.post returns for an event no listener sees */
    private static boolean fast(IEventBus bus, Event event) {
        return event.isCanceled() && event.isCancelable();
    }

    /** The normal post; afterwards the site turns fast if this type has (now) no listener at all. */
    private static boolean slow(Site s, IEventBus bus, Event event) {
        boolean r = bus.post(event);
        if (!s.isFast && bus == MinecraftForge.EVENT_BUS && bus instanceof CompositeEventBus cb && !cb.hasListeners(s.type)) {
            synchronized (SITES) {
                if (!cb.hasListeners(s.type)) {   // re-check under the lock that invalidate() takes
                    s.cs.setTarget(s.fast);
                    s.isFast = true;
                    MutableCallSite.syncAll(new MutableCallSite[] {s.cs});
                    fastSwitches++;
                }
            }
        }
        return r;
    }

    /** A listener was added or removed somewhere: every fast site back to the normal post (before it can be missed). */
    public static void invalidate() {
        synchronized (SITES) {
            List<MutableCallSite> changed = new ArrayList<>();
            for (Site s : SITES) {
                if (s.isFast) {
                    s.cs.setTarget(s.slow);
                    s.isFast = false;
                    changed.add(s.cs);
                }
            }
            if (!changed.isEmpty()) {
                MutableCallSite.syncAll(changed.toArray(new MutableCallSite[0]));
                invalidations++;
            }
        }
    }

    /**
     * The events posted from inside a (non-player: players stay on the render thread) entity's rendering; in a holder,
     * loaded on the first ask (client classes: a dedicated server never loads them).
     */
    private static final class RenderEvents {
        static final Class<?>[] ENTITY_RENDER_EVENTS = {net.minecraftforge.client.event.RenderLivingEvent.Pre.class,
            net.minecraftforge.client.event.RenderLivingEvent.Post.class, net.minecraftforge.client.event.RenderNameTagEvent.class,
            net.minecraftforge.client.event.RenderItemInFrameEvent.class};
    }
    /**
     * One of them has a listener now, asked of the bus (a mod's listener must not run on a worker); cheap, callers ask
     * once per frame (listeners can be added at any time).
     */
    /** RenderNameTagEvent has a listener now (NameTagSkipMixin, client only; asked every call: a mod may register one later) */
    public static boolean nameTagListened() {
        return !(MinecraftForge.EVENT_BUS instanceof CompositeEventBus cb) || cb.mayHaveListeners(net.minecraftforge.client.event.RenderNameTagEvent.class);
    }

    public static boolean renderListened() {
        if (!(MinecraftForge.EVENT_BUS instanceof CompositeEventBus cb)) return true;
        for (Class<?> c : RenderEvents.ENTITY_RENDER_EVENTS) if (cb.mayHaveListeners(c)) return true;
        return false;
    }

    /** The entity render events with a listener now: bit i = the i-th of them (every bit when the bus cannot say). */
    public static int renderListenedMask() {
        if (!(MinecraftForge.EVENT_BUS instanceof CompositeEventBus cb)) return -1;
        int m = 0;
        for (int i = 0; i < RenderEvents.ENTITY_RENDER_EVENTS.length; i++) if (cb.mayHaveListeners(RenderEvents.ENTITY_RENDER_EVENTS[i])) m |= 1 << i;
        return m;
    }

    /**
     * Does this renderer post one of the listened events ({@code listened}, renderListenedMask)? Each event is posted by
     * the renderers that construct it - RenderLivingEvent by LivingEntityRenderer, RenderItemInFrameEvent by
     * ItemFrameRenderer, RenderNameTagEvent by EntityRenderer - so a listener of one runs for those entities only. Read
     * once per renderer class from its bytes: a NEW of an event in a method of the class or a superclass, or in a loader
     * hook method they call (one level). Unreadable: every event.
     */
    public static boolean postsListened(Class<?> renderer, int listened) {
        return listened != 0 && (POSTS.get(renderer) & listened) != 0;
    }

    private static final ClassValue<Integer> POSTS = new ClassValue<>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            int m = posted(type);
            if (Boolean.getBoolean("lab.prender.why"))
                System.out.println("[sleep/opt] render events posted by " + type.getName() + ": " + (m == -1 ? "unreadable (all)" : Integer.toBinaryString(m)) + "; listened now " + Integer.toBinaryString(renderListenedMask()));
            return m;
        }
    };

    private static int posted(Class<?> type) {
        String[] names = new String[RenderEvents.ENTITY_RENDER_EVENTS.length];
        for (int i = 0; i < names.length; i++) names[i] = org.objectweb.asm.Type.getInternalName(RenderEvents.ENTITY_RENDER_EVENTS[i]);
        java.util.Set<String> hooks = new java.util.HashSet<>();
        int m = 0;
        try {
            for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
                var cn = dev.lull.ClassBytes.readBefore(k.getClassLoader(), k.getName().replace('.', '/'), org.objectweb.asm.ClassReader.SKIP_FRAMES);
                if (cn == null) return -1;
                for (var mn : cn.methods) m |= constructs(mn, names, hooks);
            }
            for (String h : hooks) {   // owner.name+desc of a loader hook a renderer calls: that method alone
                int dot = h.indexOf('.'), paren = h.indexOf('(');
                var cn = dev.lull.ClassBytes.readBefore(type.getClassLoader(), h.substring(0, dot), org.objectweb.asm.ClassReader.SKIP_FRAMES);
                if (cn == null) return -1;
                for (var mn : cn.methods)
                    if (mn.name.equals(h.substring(dot + 1, paren)) && mn.desc.equals(h.substring(paren))) m |= constructs(mn, names, null);
            }
        } catch (java.io.IOException | RuntimeException e) {
            if (Boolean.getBoolean("lab.prender.why")) System.out.println("[sleep/opt] render events of " + type.getName() + " unreadable: " + e);
            return -1;
        }
        return m;
    }

    private static int constructs(org.objectweb.asm.tree.MethodNode mn, String[] names, java.util.Set<String> hooks) {
        int m = 0;
        if (mn.instructions == null) return 0;
        for (var in : mn.instructions) {
            if (in instanceof org.objectweb.asm.tree.TypeInsnNode t && t.getOpcode() == org.objectweb.asm.Opcodes.NEW) {
                for (int i = 0; i < names.length; i++) if (t.desc.equals(names[i])) m |= 1 << i;
            } else if (hooks != null && in instanceof org.objectweb.asm.tree.MethodInsnNode mi && mi.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                    && mi.owner.startsWith("net/minecraftforge/")) {
                hooks.add(mi.owner + "." + mi.name + mi.desc);
            }
        }
        return m;
    }

    public static String report() {
        int fast = 0;
        java.util.Set<String> slow = new java.util.TreeSet<>();
        synchronized (SITES) {
            for (Site s : SITES) {
                if (s.isFast) fast++;
                else slow.add(s.type.getName().substring(s.type.getName().lastIndexOf('.') + 1));
            }
        }
        return "event sites " + sites + " (fast now " + fast + "), fast switches " + fastSwitches + ", invalidations " + invalidations
                + ", listened " + slow;
    }
}
