package dev.lull.optsupport;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A HashMap whose methods hold its monitor: what LocalizeTransformer puts in place of the HashMaps an adapted mod keeps
 * in fields (caches filled while drawing, now from render workers too) and under the classes extending HashMap. The
 * mod's code still sees a HashMap. The functions of compute* and forEach's action run outside the monitor (a cache
 * filled from inside another cache's function must not hold two monitors: no lock order between two maps), so under
 * contention a computing function may run twice and the first value wins - what a cache allows. Iterating a view is
 * not guarded (as Collections.synchronizedMap: the caller's business).
 */
public class SyncHashMap<K, V> extends HashMap<K, V> {
    public SyncHashMap() { super(); }
    public SyncHashMap(int capacity) { super(capacity); }
    public SyncHashMap(int capacity, float load) { super(capacity, load); }
    public SyncHashMap(Map<? extends K, ? extends V> m) { super(m); }

    @Override public synchronized V get(Object k) { return super.get(k); }
    /** the map's own get (a subclass' get may answer a default for a missing key: ETFLruCache) */
    private synchronized V sget(Object k) { return super.get(k); }
    @Override public synchronized V getOrDefault(Object k, V d) { return super.getOrDefault(k, d); }
    @Override public synchronized boolean containsKey(Object k) { return super.containsKey(k); }
    @Override public synchronized boolean containsValue(Object v) { return super.containsValue(v); }
    @Override public synchronized V put(K k, V v) { return super.put(k, v); }
    @Override public synchronized V putIfAbsent(K k, V v) { return super.putIfAbsent(k, v); }
    @Override public synchronized void putAll(Map<? extends K, ? extends V> m) { super.putAll(m); }
    @Override public synchronized V remove(Object k) { return super.remove(k); }
    @Override public synchronized boolean remove(Object k, Object v) { return super.remove(k, v); }
    @Override public synchronized V replace(K k, V v) { return super.replace(k, v); }
    @Override public synchronized boolean replace(K k, V o, V v) { return super.replace(k, o, v); }
    @Override public synchronized void clear() { super.clear(); }
    @Override public synchronized int size() { return super.size(); }
    @Override public synchronized boolean isEmpty() { return super.isEmpty(); }
    @Override public synchronized Object clone() { return super.clone(); }

    @Override
    public V computeIfAbsent(K k, Function<? super K, ? extends V> f) {
        V v = sget(k);
        if (v != null) return v;
        V n = f.apply(k);
        if (n == null) return null;
        synchronized (this) {
            V had = super.get(k);
            if (had != null) return had;
            super.put(k, n);
            return n;
        }
    }

    @Override
    public V computeIfPresent(K k, BiFunction<? super K, ? super V, ? extends V> f) {
        while (true) {
            V old = sget(k);
            if (old == null) return null;
            V n = f.apply(k, old);
            synchronized (this) {
                if (super.get(k) != old) continue;
                if (n == null) super.remove(k);
                else super.put(k, n);
                return n;
            }
        }
    }

    @Override
    public V compute(K k, BiFunction<? super K, ? super V, ? extends V> f) {
        while (true) {
            V old;
            boolean had;
            synchronized (this) {
                old = super.get(k);
                had = old != null || super.containsKey(k);
            }
            V n = f.apply(k, old);
            synchronized (this) {
                V now = super.get(k);
                if (now != old || (now == null && super.containsKey(k) != had)) continue;
                if (n == null) {
                    if (had) super.remove(k);
                } else super.put(k, n);
                return n;
            }
        }
    }

    @Override
    public V merge(K k, V value, BiFunction<? super V, ? super V, ? extends V> f) {
        while (true) {
            V old = sget(k);
            V n = old == null ? value : f.apply(old, value);
            synchronized (this) {
                if (super.get(k) != old) continue;
                if (n == null) super.remove(k);
                else super.put(k, n);
                return n;
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Object[] kv;
        synchronized (this) {
            kv = new Object[super.size() * 2];
            int i = 0;
            for (Map.Entry<K, V> e : super.entrySet()) {
                kv[i++] = e.getKey();
                kv[i++] = e.getValue();
            }
        }
        for (int i = 0; i < kv.length; i += 2) action.accept((K) kv[i], (V) kv[i + 1]);
    }

    @Override
    public synchronized void replaceAll(BiFunction<? super K, ? super V, ? extends V> f) {
        super.replaceAll(f);
    }
}
