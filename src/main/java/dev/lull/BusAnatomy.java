package dev.lull;

import dev.lull.bus.CompositeDispatcher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Where do the nanoseconds of IEventBus.post go? Same listeners, different dispatch shapes (/sleep busbench).
 *
 *   bus      - NeoForge.EVENT_BUS.post(e)                         (vanilla: megamorphic loop in EventBus)
 *   loop     - our own for-loop over the SAME EventListener[]     (bus minus its bookkeeping)
 *   compose  - CompositeDispatcher: unrolled, one call site per listener
 *   direct   - the listener bodies as plain static calls          (lower bound)
 * plus the cost of one SubscribeEvent.receiveCanceled() proxy call (paid per listener per post on cancellable events).
 */
final class BusAnatomy {
    record Row(String name, double ns) {}

    private BusAnatomy() {}

    static ListenerList listenerList(IEventBus bus, Class<?> type) throws ReflectiveOperationException {
        Object real = bus instanceof dev.lull.optsupport.CompositeEventBus c ? c.delegate() : bus;
        Field f = dev.lull.Names.fieldOf(real.getClass(), "listenerLists");
        f.setAccessible(true);
        Object helper = f.get(real);
        Method m = dev.lull.Names.methodOf(helper.getClass(), "getReadMap");
        m.setAccessible(true);
        return (ListenerList) ((Map<?, ?>) m.invoke(helper)).get(type);
    }

    interface Body {
        void run(int iterations);
    }

    /** Best of 5 after 3 warm-up rounds, ns per iteration. */
    static double time(int n, Body body) {
        for (int w = 0; w < 3; w++) body.run(n / 4);
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime();
            body.run(n);
            best = Math.min(best, (System.nanoTime() - t0) / (double) n);
        }
        return best;
    }

    static List<Row> run(int n) throws ReflectiveOperationException {
        IEventBus bus = NeoForge.EVENT_BUS;
        List<Row> rows = new ArrayList<>();
        caseFor(rows, bus, n, 10, ScaleListeners.Scale10.class, i -> { var e = new ScaleListeners.Scale10(); e.id = i; return e; });
        caseFor(rows, bus, n, 50, ScaleListeners.Scale50.class, i -> { var e = new ScaleListeners.Scale50(); e.id = i; return e; });
        caseFor(rows, bus, n / 2, 100, ScaleListeners.Scale100.class, i -> { var e = new ScaleListeners.Scale100(); e.id = i; return e; });
        caseFor(rows, bus, n, 50, ScaleListeners.Scale50Cancellable.class, i -> { var e = new ScaleListeners.Scale50Cancellable(); e.id = i; return e; });
        caseFor(rows, bus, n, 50, ScaleListeners.Scale50Consumer.class, i -> { var e = new ScaleListeners.Scale50Consumer(); e.id = i; return e; });
        caseFor(rows, bus, n, 50, ScaleListeners.Scale50ConsumerCancellable.class, i -> { var e = new ScaleListeners.Scale50ConsumerCancellable(); e.id = i; return e; });

        // lower bound: 100 filter bodies as direct code
        rows.add(new Row("direct: 100 inline filter bodies", time(n, k -> {
            for (int i = 0; i < k; i++) for (int j = 0; j < 100; j++) if (i == 1000 + j) ScaleListeners.sink++;
        })));

        // one annotation proxy call (what SubscribeEventListener.invoke does per listener on cancellable events)
        SubscribeEvent ann = dev.lull.Names.publicMethod(ScaleListeners.class, "onC50_0", ScaleListeners.Scale50Cancellable.class)
                .getAnnotation(SubscribeEvent.class);
        rows.add(new Row("SubscribeEvent.receiveCanceled() proxy call", time(n * 10, k -> {
            int c = 0;
            for (int i = 0; i < k; i++) if (ann.receiveCanceled()) c++;
            ScaleListeners.sink += c;
        })));
        return rows;
    }

    private static void caseFor(List<Row> rows, IEventBus bus, int n, int count, Class<? extends Event> type,
                                IntFunction<? extends Event> make) throws ReflectiveOperationException {
        bus.post(make.apply(0));                                  // make sure the listener list exists and is built
        EventListener[] ls = listenerList(bus, type).getListeners();
        CompositeDispatcher.Dispatch composite = CompositeDispatcher.create(ls);
        String tag = type.getSimpleName() + " (" + ls.length + " listeners)";
        rows.add(new Row(tag + " bus.post", time(n, k -> {
            for (int i = 0; i < k; i++) bus.post(make.apply(i));
        })));
        rows.add(new Row(tag + " own loop", time(n, k -> {
            for (int i = 0; i < k; i++) {
                Event e = make.apply(i);
                for (EventListener l : ls) l.invoke(e);
            }
        })));
        rows.add(new Row(tag + " composite", time(n, k -> {
            for (int i = 0; i < k; i++) composite.dispatch(make.apply(i));
        })));
    }

    /** Every scale listener increments sink only for its own id: bus and composite must add the same amount. */
    static List<String> sinkEquivalence(IEventBus bus) throws ReflectiveOperationException {
        List<String> failures = new ArrayList<>();
        Object[][] cases = {
                {ScaleListeners.Scale50.class, 1010, 1060, (IntFunction<Event>) i -> { var e = new ScaleListeners.Scale50(); e.id = i; return e; }},
                {ScaleListeners.Scale50Cancellable.class, 5000, 5050, (IntFunction<Event>) i -> { var e = new ScaleListeners.Scale50Cancellable(); e.id = i; return e; }},
                {ScaleListeners.Scale50Consumer.class, 7000, 7050, (IntFunction<Event>) i -> { var e = new ScaleListeners.Scale50Consumer(); e.id = i; return e; }},
                {ScaleListeners.Scale50ConsumerCancellable.class, 8000, 8050, (IntFunction<Event>) i -> { var e = new ScaleListeners.Scale50ConsumerCancellable(); e.id = i; return e; }}};
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked") IntFunction<Event> make = (IntFunction<Event>) c[3];
            CompositeDispatcher.Dispatch d = CompositeDispatcher.create(listenerList(bus, (Class<?>) c[0]).getListeners());
            long before = ScaleListeners.sink;
            for (int id = (int) c[1] - 5; id < (int) c[2] + 5; id++) bus.post(make.apply(id));
            long viaBus = ScaleListeners.sink - before;
            before = ScaleListeners.sink;
            for (int id = (int) c[1] - 5; id < (int) c[2] + 5; id++) d.dispatch(make.apply(id));
            long viaComposite = ScaleListeners.sink - before;
            if (viaBus != viaComposite || viaBus != 50) {
                failures.add(((Class<?>) c[0]).getSimpleName() + ": bus " + viaBus + " vs composite " + viaComposite + " (expected 50)");
            }
        }
        return failures;
    }

    static String format(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) sb.append(String.format(Locale.ROOT, "%-58s %9.1f ns%n", r.name(), r.ns()));
        return sb.toString();
    }
}
