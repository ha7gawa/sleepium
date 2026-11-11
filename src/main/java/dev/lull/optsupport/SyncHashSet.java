package dev.lull.optsupport;

import java.util.Collection;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** SyncHashMap's HashSet: the sets an adapted mod keeps in fields, guarded by their monitor (iteration is not). */
public class SyncHashSet<E> extends HashSet<E> {
    public SyncHashSet() { super(); }
    public SyncHashSet(int capacity) { super(capacity); }
    public SyncHashSet(int capacity, float load) { super(capacity, load); }
    public SyncHashSet(Collection<? extends E> c) { super(c); }

    @Override public synchronized boolean add(E e) { return super.add(e); }
    @Override public synchronized boolean remove(Object o) { return super.remove(o); }
    @Override public synchronized boolean contains(Object o) { return super.contains(o); }
    @Override public synchronized int size() { return super.size(); }
    @Override public synchronized boolean isEmpty() { return super.isEmpty(); }
    @Override public synchronized void clear() { super.clear(); }
    @Override public synchronized boolean addAll(Collection<? extends E> c) { return super.addAll(c); }
    @Override public synchronized boolean removeAll(Collection<?> c) { return super.removeAll(c); }
    @Override public synchronized boolean retainAll(Collection<?> c) { return super.retainAll(c); }
    @Override public synchronized boolean containsAll(Collection<?> c) { return super.containsAll(c); }
    @Override public synchronized Object[] toArray() { return super.toArray(); }
    @Override public synchronized <T> T[] toArray(T[] a) { return super.toArray(a); }
    @Override public synchronized Object clone() { return super.clone(); }

    @Override
    public synchronized boolean removeIf(Predicate<? super E> filter) {
        return super.removeIf(filter);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super E> action) {
        for (Object e : toArray()) action.accept((E) e);
    }
}
