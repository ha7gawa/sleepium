package dev.lull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Fabric (lab): Minecraft's names at run time. The game runs Mojang's names in a dev run and intermediary names
 * (net/minecraft/class_1297, method_5773, field_6002) in production; the engine's code names things the Mojang way in
 * strings (reflection, MixinTouch's questions, class name checks). The table (sleepium-fabric-names.txt.gz, built from
 * Loom's mappings) is read on the first lookup in production only; in a dev run every call returns its argument.
 */
public final class Names {
    private Names() {}

    public static final boolean INTERMEDIARY = Loader.production();

    private static Map<String, String> cls, clsBack, fields, methods, methodsByParams, methodsByName, membersBack;

    private static synchronized void load() {
        if (cls != null) return;
        Map<String, String> c = new HashMap<>(1 << 14), cb = new HashMap<>(1 << 14), f = new HashMap<>(1 << 16), m = new HashMap<>(1 << 17),
                mn = new HashMap<>(1 << 17), mb = new HashMap<>(1 << 17), mp = new HashMap<>(1 << 17);
        try (var in = Names.class.getResourceAsStream("/sleepium-fabric-names.txt.gz")) {
            if (in == null) throw new IllegalStateException("sleepium-fabric-names.txt.gz missing from the jar");
            BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                switch (p[0]) {
                    case "c" -> {
                        c.put(p[1], p[2]);
                        cb.put(p[2], p[1]);
                    }
                    case "f" -> {
                        f.put(p[1] + "." + p[2], p[4]);
                        mb.put(p[4], p[2]);
                    }
                    case "m" -> {
                        m.put(p[1] + "." + p[2] + p[3], p[4]);
                        mp.putIfAbsent(p[1] + "." + p[2] + p[3].substring(0, p[3].indexOf(')') + 1), p[4]);
                        mn.putIfAbsent(p[1] + "." + p[2], p[4]);
                        mn.putIfAbsent("*." + p[2], p[4]);
                        mb.put(p[4], p[2]);
                    }
                    default -> { }
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        clsBack = cb;
        fields = f;
        methods = m;
        methodsByParams = mp;
        methodsByName = mn;
        membersBack = mb;
        cls = c;
    }

    /** a class, Mojang's name (dots or slashes) -> the running name, the same style */
    public static String cls(String mojang) {
        if (!INTERMEDIARY || mojang == null) return mojang;
        load();
        boolean dots = mojang.indexOf('.') >= 0;
        String r = cls.get(dots ? mojang.replace('.', '/') : mojang);
        if (r == null) return mojang;
        return dots ? r.replace('/', '.') : r;
    }

    /** a class, the running name -> Mojang's (the same style) */
    public static String official(String running) {
        if (!INTERMEDIARY || running == null) return running;
        load();
        boolean dots = running.indexOf('.') >= 0;
        String r = clsBack.get(dots ? running.replace('.', '/') : running);
        if (r == null) return running;
        return dots ? r.replace('/', '.') : r;
    }

    /** a field of a class (Mojang's owner, slashes) -> its running name */
    public static String field(String ownerMojang, String name) {
        if (!INTERMEDIARY) return name;
        load();
        return fields.getOrDefault(ownerMojang.replace('.', '/') + "." + name, name);
    }

    /** a method (Mojang's owner, slashes; Mojang's descriptor or null: the first of that name) -> its running name */
    public static String method(String ownerMojang, String name, String desc) {
        if (!INTERMEDIARY) return name;
        load();
        String o = ownerMojang == null ? "*" : ownerMojang.replace('.', '/');
        if (desc != null) {
            String r = methods.get(o + "." + name + desc);
            if (r != null) return r;
        }
        String r = methodsByName.get(o + "." + name);
        if (r == null) r = methodsByName.get("*." + name);
        return r == null ? name : r;
    }

    /** a descriptor with Mojang's class names -> the running ones (null: null) */
    public static String desc(String d) {
        if (!INTERMEDIARY || d == null) return d;
        StringBuilder b = new StringBuilder(d.length());
        int i = 0;
        while (i < d.length()) {
            char ch = d.charAt(i);
            if (ch == 'L') {
                int e = d.indexOf(';', i);
                b.append('L').append(cls(d.substring(i + 1, e))).append(';');
                i = e + 1;
            } else {
                b.append(ch);
                i++;
            }
        }
        return b.toString();
    }

    /** a member's running name -> Mojang's (fields and methods; unknown: as it is) */
    public static String officialMember(String running) {
        if (!INTERMEDIARY) return running;
        load();
        return membersBack.getOrDefault(running, running);
    }

    private static String ownerOf(Class<?> k) {
        return official(k.getName()).replace('.', '/');
    }

    /** a field's running name, looked up on c and then up its superclasses (unknown: as given) */
    public static String fieldName(Class<?> c, String mojangName) {
        if (!INTERMEDIARY || c == null) return mojangName;
        load();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            String r = fields.get(ownerOf(k) + "." + mojangName);
            if (r != null) return r;
        }
        return mojangName;
    }

    /**
     * a method's running name: c, its superclasses and interfaces (an override keeps the intermediary name of the method it
     * overrides); paramsDesc ("(IL...;)", Mojang's class names) picks the overload, null: the first of that name
     */
    public static String methodName(Class<?> c, String mojangName, String paramsDesc) {
        if (!INTERMEDIARY || c == null) return mojangName;
        load();
        java.util.ArrayDeque<Class<?>> q = new java.util.ArrayDeque<>();
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        q.add(c);
        while (!q.isEmpty()) {
            Class<?> k = q.poll();
            if (!seen.add(k)) continue;
            String o = ownerOf(k);
            String r = paramsDesc != null ? methodsByParams.get(o + "." + mojangName + paramsDesc) : null;
            if (r == null) r = methodsByName.get(o + "." + mojangName);
            if (r != null) return r;
            if (k.getSuperclass() != null) q.add(k.getSuperclass());
            java.util.Collections.addAll(q, k.getInterfaces());
        }
        return mojangName;
    }

    private static String paramsDesc(Class<?>[] params) {
        StringBuilder d = new StringBuilder("(");
        for (Class<?> p : params) d.append(descOf(p));
        return d.append(')').toString();
    }

    /** Class.getDeclaredField by Mojang's name */
    public static java.lang.reflect.Field fieldOf(Class<?> c, String mojangName) throws NoSuchFieldException {
        if (!INTERMEDIARY) return c.getDeclaredField(mojangName);
        load();
        return c.getDeclaredField(fields.getOrDefault(ownerOf(c) + "." + mojangName, mojangName));
    }

    /** Class.getField by Mojang's name */
    public static java.lang.reflect.Field publicField(Class<?> c, String mojangName) throws NoSuchFieldException {
        return c.getField(fieldName(c, mojangName));
    }

    /** Class.getDeclaredMethod by Mojang's name (the overload chosen by the parameter types) */
    public static java.lang.reflect.Method methodOf(Class<?> c, String mojangName, Class<?>... params) throws NoSuchMethodException {
        if (!INTERMEDIARY) return c.getDeclaredMethod(mojangName, params);
        return c.getDeclaredMethod(methodName(c, mojangName, paramsDesc(params)), params);
    }

    /** Class.getMethod by Mojang's name */
    public static java.lang.reflect.Method publicMethod(Class<?> c, String mojangName, Class<?>... params) throws NoSuchMethodException {
        if (!INTERMEDIARY) return c.getMethod(mojangName, params);
        return c.getMethod(methodName(c, mojangName, paramsDesc(params)), params);
    }

    /** a nested class by Mojang's simple name (outer$Inner) */
    public static Class<?> nested(Class<?> outer, String simpleMojang) throws ClassNotFoundException {
        return Class.forName(cls(official(outer.getName()) + "$" + simpleMojang), false, outer.getClassLoader());
    }

    /** a class by Mojang's name */
    public static Class<?> forName(String mojang, boolean init, ClassLoader l) throws ClassNotFoundException {
        return Class.forName(cls(mojang), init, l);
    }

    private static String descOf(Class<?> p) {
        if (p.isPrimitive()) {
            if (p == int.class) return "I";
            if (p == long.class) return "J";
            if (p == boolean.class) return "Z";
            if (p == float.class) return "F";
            if (p == double.class) return "D";
            if (p == byte.class) return "B";
            if (p == short.class) return "S";
            if (p == char.class) return "C";
            return "V";
        }
        if (p.isArray()) return "[" + descOf(p.getComponentType());
        return "L" + official(p.getName()).replace('.', '/') + ";";
    }
}
