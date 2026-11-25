package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/** Field reads by name for the proof builders, cached per class without building a key string per read. */
public final class Reflect {
    private static final ClassValue<ConcurrentHashMap<String, Field>> FIELDS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, Field> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private Reflect() {}

    public static Object get(Object o, String name) {
        Class<?> cls = o.getClass();
        Field f = FIELDS.get(cls).computeIfAbsent(name, n -> {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                try {
                    Field x = dev.lull.Names.fieldOf(c, n);
                    x.setAccessible(true);
                    return x;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new IllegalStateException("no field " + n + " in " + cls.getName());
        });
        try {
            return f.get(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
