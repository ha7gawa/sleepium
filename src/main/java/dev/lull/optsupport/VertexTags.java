package dev.lull.optsupport;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * State another mod writes into every vertex from a global at the moment the vertex is written, not from the
 * VertexConsumer calls: a recording keeps it per run of vertices and a replay puts it back around those vertices.
 *
 * <p>Iris (shaderpacks): BufferBuilder adds the current entity, block entity and item id to each entity vertex
 * (CapturedRenderingState, set by its mixins around a dispatcher's render, an armor layer, an item render; the pack's
 * shaders pick materials by them). A replay outside those calls - the armor layer ran only when it was recorded - wrote
 * id 0 for the armor: the pack drew it as another material. Reflection only (Iris is optional, not on the classpath).
 */
public final class VertexTags {
    private static final MethodHandle GET_E, GET_BE, GET_I, SET_E, SET_BE, SET_I;
    /** a mod with such state is loaded */
    public static final boolean ON;

    static {
        MethodHandle ge = null, gb = null, gi = null, se = null, sb = null, si = null;
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Object inst = dev.lull.Names.publicField(c, "INSTANCE").get(null);
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            MethodType get = MethodType.methodType(int.class), set = MethodType.methodType(void.class, int.class);
            ge = l.findVirtual(c, dev.lull.Names.methodName(c, "getCurrentRenderedEntity", null), get).bindTo(inst);
            gb = l.findVirtual(c, dev.lull.Names.methodName(c, "getCurrentRenderedBlockEntity", null), get).bindTo(inst);
            gi = l.findVirtual(c, dev.lull.Names.methodName(c, "getCurrentRenderedItem", null), get).bindTo(inst);
            se = l.findVirtual(c, dev.lull.Names.methodName(c, "setCurrentEntity", null), set).bindTo(inst);
            sb = l.findVirtual(c, dev.lull.Names.methodName(c, "setCurrentBlockEntity", null), set).bindTo(inst);
            si = l.findVirtual(c, dev.lull.Names.methodName(c, "setCurrentRenderedItem", null), set).bindTo(inst);
        } catch (Throwable t) {
            ge = null;
        }
        ON = ge != null;
        GET_E = ge;
        GET_BE = gb;
        GET_I = gi;
        SET_E = se;
        SET_BE = sb;
        SET_I = si;
        if (ON) System.out.println("[sleep/opt] vertex tags: Iris's entity / block entity / item ids kept with recorded vertices");
    }

    private VertexTags() {}

    /** the state now into t[at..at+2] */
    static void read(int[] t, int at) {
        try {
            t[at] = (int) GET_E.invokeExact();
            t[at + 1] = (int) GET_BE.invokeExact();
            t[at + 2] = (int) GET_I.invokeExact();
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** t[at..at+2] made the state */
    static void write(int[] t, int at) {
        try {
            SET_E.invokeExact(t[at]);
            SET_BE.invokeExact(t[at + 1]);
            SET_I.invokeExact(t[at + 2]);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
