package dev.lull.optsupport;

/** SpecTicks (lab): an entity's slot in the current block, kept on the entity (no map lookups); valid for its epoch only */
public interface SpecSlot {
    int sleep$specSlot(long epoch);

    void sleep$setSpecSlot(int slot, long epoch);
}
