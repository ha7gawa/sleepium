package dev.lull.optsupport;

/** Added to EntitySection by EntitySectionIndexMixin: its section key and its (lazy) spatial index. */
public interface IndexedSection {
    long sleep$key();

    void sleep$setKey(long key);

    SectionIndex sleep$index();

    void sleep$setIndex(SectionIndex index);

    /** every index of the section marked for rebuilding (SpecTicks: boxes changed off the owner thread, or undone) */
    void sleep$markAllDirty();

    /** Index over the typed list of {@code baseClass} (getEntitiesOfClass queries); null if none yet. */
    SectionIndex sleep$typedIndex(Class<?> baseClass);

    void sleep$putTypedIndex(Class<?> baseClass, SectionIndex index);

    /** Bounding-box change of an entity of this section: update the typed indices (no lambda per call). */
    void sleep$forEachTyped(Object entity, net.minecraft.world.phys.AABB bb);
}
