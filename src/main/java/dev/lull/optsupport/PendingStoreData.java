package dev.lull.optsupport;

import java.lang.reflect.Field;
import net.minecraft.nbt.CompoundTag;

/** IOWorker$PendingStore.data (a package-private class's field) for IOWorkerRegionScanMixin: the data a queued chunk write holds. */
public final class PendingStoreData {
    private static volatile Field data;

    private PendingStoreData() {}

    public static CompoundTag of(Object pendingStore) throws ReflectiveOperationException {
        Field f = data;
        if (f == null) {
            f = dev.lull.Names.fieldOf(pendingStore.getClass(), "data");
            f.setAccessible(true);
            data = f;
        }
        return (CompoundTag) f.get(pendingStore);
    }
}
