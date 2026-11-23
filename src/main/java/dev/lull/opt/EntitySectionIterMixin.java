package dev.lull.opt;

import dev.lull.optsupport.IndexedSection;
import dev.lull.optsupport.OptCounters;
import dev.lull.optsupport.SectionIndex;
import java.util.List;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * exp/section-iter (goes with EntitySectionScanMixin): iterate a section's entities with an index loop over the
 * same backing lists, in the same order. Vanilla allocates per call: two iterators (unmodifiable wrapper + list
 * iterator) for untyped queries; for typed queries a capturing computeIfAbsent lambda, an unmodifiable collection
 * wrapper and its iterators. The typed list is still created by vanilla's own find() the first time.
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionIterMixin<T extends EntityAccess> {
    @Shadow @Final private ClassInstanceMultiMap<T> storage;

    /**
     * @author probe (lab.opt.EntitySectionScanMixin / EntitySectionIndexMixin)
     * @reason index loop (and optional exact spatial index) instead of iterators; an @Inject would allocate a
     *         CallbackInfoReturnable per section visit
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    public AbortableIterationConsumer.Continuation getEntities(AABB bounds, AbortableIterationConsumer<T> consumer) {
        List<T> list = ((ClassInstanceMultiMapAccessor<T>) storage).sleep$all();
        if (INDEX && list.size() >= INDEX_MIN) {
            AbortableIterationConsumer.Continuation c = sleep$indexed(list, bounds, consumer);
            if (c != null) return c;
        }
        for (int i = 0; i < list.size(); i++) {
            T t = list.get(i);
            if (t.getBoundingBox().intersects(bounds) && consumer.accept(t).shouldAbort()) return AbortableIterationConsumer.Continuation.ABORT;
        }
        return AbortableIterationConsumer.Continuation.CONTINUE;
    }

    /** Index path, kept out of getEntities so that method stays small enough to be inlined; null = not indexed. */
    private AbortableIterationConsumer.Continuation sleep$indexed(List<T> list, AABB bounds, AbortableIterationConsumer<T> consumer) {
        IndexedSection is = (IndexedSection) this;
        SectionIndex idx = is.sleep$index();
        Thread t = Thread.currentThread();
        if (idx == null && is.sleep$key() != Long.MIN_VALUE && t == serverThread() && !dev.lull.optsupport.SpecTicks.running()) {
            idx = new SectionIndex(is.sleep$key());
            is.sleep$setIndex(idx);
        }
        if (idx == null || idx.owner != t || dev.lull.optsupport.SpecTicks.running()) return null;   // (a run: boxes move off the owner thread)
        if (INDEX_CHECK) sleep$check(idx, list, bounds);
        return idx.query(list, bounds, consumer);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbortableIterationConsumer.Continuation sleep$indexedTyped(List<T> list, AABB bounds, EntityTypeTest test, AbortableIterationConsumer consumer) {
        IndexedSection is = (IndexedSection) this;
        Class<?> base = test.getBaseClass();
        SectionIndex idx = is.sleep$typedIndex(base);
        Thread th = Thread.currentThread();
        if (idx == null && is.sleep$key() != Long.MIN_VALUE && th == serverThread() && !dev.lull.optsupport.SpecTicks.running()) {
            idx = new SectionIndex(is.sleep$key());
            is.sleep$putTypedIndex(base, idx);
        }
        if (idx == null || idx.owner != th || dev.lull.optsupport.SpecTicks.running()) return null;
        if (INDEX_CHECK) sleep$checkTyped(idx, list, bounds, test);
        return idx.queryTyped(list, bounds, test, consumer);
    }

    private static final boolean INDEX = Boolean.getBoolean("lab.opt.EntitySectionIndexMixin");
    private static final boolean INDEX_CHECK = Boolean.getBoolean("lab.opt.sectionIndexCheck");
    private static final int INDEX_MIN = Integer.getInteger("lab.opt.sectionIndexMin", 48);
    /** The current server's thread (not cached: a client starts a new integrated server, and thread, per world). */
    private static Thread serverThread() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.getRunningThread() : null;
    }

    /** Shadow check: the index must yield exactly the entities (and order) of the vanilla scan. */
    private void sleep$check(SectionIndex idx, List<T> list, AABB bounds) {
        List<T> a = new java.util.ArrayList<>(), b = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) if (list.get(i).getBoundingBox().intersects(bounds)) a.add(list.get(i));
        idx.query(list, bounds, e -> {
            b.add(e);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
        OptCounters.INDEX_CHECKED.incrementAndGet();
        if (!a.equals(b) && OptCounters.INDEX_MISMATCHES.incrementAndGet() <= 5)
            System.out.println("[sleep/opt] section index MISMATCH: vanilla " + a.size() + " vs index " + b.size());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sleep$checkTyped(SectionIndex idx, List<T> list, AABB bounds, EntityTypeTest test) {
        List<Object> a = new java.util.ArrayList<>(), b = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object u = test.tryCast(list.get(i));
            if (u != null && list.get(i).getBoundingBox().intersects(bounds)) a.add(u);
        }
        idx.queryTyped(list, bounds, test, e -> {
            b.add(e);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
        OptCounters.INDEX_CHECKED.incrementAndGet();
        if (!a.equals(b) && OptCounters.INDEX_MISMATCHES.incrementAndGet() <= 5)
            System.out.println("[sleep/opt] typed section index MISMATCH: vanilla " + a.size() + " vs index " + b.size());
    }

    /**
     * @author probe (lab.opt.EntitySectionScanMixin / EntitySectionIndexMixin)
     * @reason as above, typed variant; the typed list is created by vanilla's own find() on first use
     */
    @Overwrite
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <U extends T> AbortableIterationConsumer.Continuation getEntities(EntityTypeTest<T, U> test, AABB bounds, AbortableIterationConsumer<? super U> consumer) {
        List<T> list = ((ClassInstanceMultiMapAccessor<T>) storage).sleep$byClass().get(test.getBaseClass());
        if (list == null) {
            if (this.storage.find(test.getBaseClass()).isEmpty()) return AbortableIterationConsumer.Continuation.CONTINUE;   // vanilla: creates the list
            list = ((ClassInstanceMultiMapAccessor<T>) storage).sleep$byClass().get(test.getBaseClass());
            if (list == null) return AbortableIterationConsumer.Continuation.CONTINUE;
        }
        if (INDEX && list.size() >= INDEX_MIN) {
            AbortableIterationConsumer.Continuation c = sleep$indexedTyped(list, bounds, test, (AbortableIterationConsumer) consumer);
            if (c != null) return c;
        }
        for (int i = 0; i < list.size(); i++) {
            T t = list.get(i);
            U u = test.tryCast(t);
            if (u != null && t.getBoundingBox().intersects(bounds) && consumer.accept(u).shouldAbort()) return AbortableIterationConsumer.Continuation.ABORT;
        }
        return AbortableIterationConsumer.Continuation.CONTINUE;
    }
}
