package dev.lull.bus;

import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V21;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.neoforged.bus.ConsumerEventHandler;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.SubscribeEvent;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Prototype v2: one generated dispatcher per listener list, calls UNROLLED and wrappers FLATTENED.
 *
 * <p>Vanilla bus: {@code for (i...) listeners[i].invoke(event)} is ONE call site for every event type in the game,
 * so it is megamorphic and nothing behind it inlines. Worse, wrappers add a second shared megamorphic site:
 * {@code SubscribeEventListener.invoke -> handler.invoke} (plus an annotation-proxy call to receiveCanceled() on
 * every invocation), and {@code ConsumerEventHandler.invoke -> consumer.accept}.
 *
 * <p>Here each listener gets its own call site in a hidden class generated for this list:
 * <ul>
 *   <li>SubscribeEventListener: its inner generated handler is called directly; receiveCanceled is read ONCE and
 *       compiled in as {@code if (!event.isCanceled())}</li>
 *   <li>ConsumerEventHandler(.WithPredicate): the Consumer is called directly (the only predicate the bus ever builds
 *       is "not cancelled", see EventBus.passNotGenericFilter), with the same compiled-in check</li>
 *   <li>anything else: the listener object itself (safe fallback)</li>
 * </ul>
 * Calls are split into chunks of {@value #CHUNK} private methods so each stays under C2's inlining size limits.
 * Order, priority, cancellation and receiveCanceled semantics are unchanged.
 */
public final class CompositeDispatcher {
    public interface Dispatch {
        void dispatch(Event event);
    }

    static final int CHUNK = 16;

    private record Slot(Object target, boolean consumer, boolean skipIfCanceled) {}

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final String LISTENER = "net/neoforged/bus/api/EventListener";
    private static final String CONSUMER = "java/util/function/Consumer";
    private static final String CANCELLABLE = "net/neoforged/bus/api/ICancellableEvent";
    private static final String EVENT_DESC = "(Lnet/neoforged/bus/api/Event;)V";

    private static final Field SEL_HANDLER, SEL_INFO, CEH_CONSUMER;

    static {
        try {
            SEL_HANDLER = dev.lull.Names.fieldOf(SubscribeEventListener.class, "handler");
            SEL_INFO = dev.lull.Names.fieldOf(SubscribeEventListener.class, "subInfo");
            CEH_CONSUMER = dev.lull.Names.fieldOf(ConsumerEventHandler.class, "consumer");
            SEL_HANDLER.setAccessible(true);
            SEL_INFO.setAccessible(true);
            CEH_CONSUMER.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CompositeDispatcher() {}

    private static Slot flatten(EventListener l) throws IllegalAccessException {
        if (l instanceof SubscribeEventListener sel) {
            EventListener handler = (EventListener) SEL_HANDLER.get(sel);
            boolean receiveCanceled = ((SubscribeEvent) SEL_INFO.get(sel)).receiveCanceled();
            // SubscribeEventListener.invoke only reaches the cancel check for cancellable events (non-cancellable lists
            // are unwrapped by ListenerList), so a wrapped listener always belongs to a cancellable event.
            return new Slot(handler, false, !receiveCanceled);
        }
        if (l instanceof ConsumerEventHandler.WithPredicate w) {
            return new Slot(CEH_CONSUMER.get(w), true, true);
        }
        if (l.getClass() == ConsumerEventHandler.class) {
            return new Slot(CEH_CONSUMER.get(l), true, false);
        }
        return new Slot(l, false, false);
    }

    public static Dispatch create(EventListener[] listeners) {
        List<Slot> slots = new ArrayList<>(listeners.length);
        try {
            for (EventListener l : listeners) slots.add(flatten(l));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }

        String name = "dev/lull/bus/Composite$" + IDS.incrementAndGet();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";   // frames only merge `this` and the Event argument
            }
        };
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL, name, null, "java/lang/Object",
                new String[] {"dev/lull/bus/CompositeDispatcher$Dispatch"});
        for (int i = 0; i < slots.size(); i++) {
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "f" + i, "L" + (slots.get(i).consumer() ? CONSUMER : LISTENER) + ";", null, null).visitEnd();
        }

        // constructor(Object[] targets)
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        for (int i = 0; i < slots.size(); i++) {
            String type = slots.get(i).consumer() ? CONSUMER : LISTENER;
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitLdcInsn(i);
            init.visitInsn(AALOAD);
            init.visitTypeInsn(CHECKCAST, type);
            init.visitFieldInsn(PUTFIELD, name, "f" + i, "L" + type + ";");
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // chunk methods
        int chunks = (slots.size() + CHUNK - 1) / CHUNK;
        for (int c = 0; c < chunks; c++) {
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_FINAL, "c" + c, EVENT_DESC, null, null);
            mv.visitCode();
            for (int i = c * CHUNK; i < Math.min(slots.size(), (c + 1) * CHUNK); i++) {
                Slot s = slots.get(i);
                Label skip = new Label();
                if (s.skipIfCanceled()) {
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitTypeInsn(CHECKCAST, CANCELLABLE);
                    mv.visitMethodInsn(INVOKEINTERFACE, CANCELLABLE, "isCanceled", "()Z", true);
                    mv.visitJumpInsn(IFNE, skip);
                }
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, "f" + i, "L" + (s.consumer() ? CONSUMER : LISTENER) + ";");
                mv.visitVarInsn(ALOAD, 1);
                if (s.consumer()) {
                    mv.visitMethodInsn(INVOKEINTERFACE, CONSUMER, "accept", "(Ljava/lang/Object;)V", true);
                } else {
                    mv.visitMethodInsn(INVOKEVIRTUAL, LISTENER, "invoke", EVENT_DESC, false);
                }
                if (s.skipIfCanceled()) {
                    mv.visitLabel(skip);
                }
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // dispatch(Event) -> c0(e); c1(e); ...
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "dispatch", EVENT_DESC, null, null);
        mv.visitCode();
        for (int c = 0; c < chunks; c++) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, name, "c" + c, EVENT_DESC, false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        try {
            MethodHandles.Lookup hidden = LOOKUP.defineHiddenClass(cw.toByteArray(), true);
            Object[] targets = slots.stream().map(Slot::target).toArray();
            return (Dispatch) hidden.lookupClass().getConstructor(Object[].class).newInstance((Object) targets);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot define composite dispatcher", e);
        }
    }
}
