package dev.lull.optsupport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * -Dlab.opt.SavedDataNameMixin (notes/88, log only): NeoForge writes a SavedData's tag on the IO pool
 * (IOUtilities.withIOWorker); when that write throws, the stack names neither the data nor its mod (11-21: a null key
 * at data.X.&lt;null&gt; in the be_dense factory - "Exception stopping the server", rethrown by waitUntilIOWorkerComplete).
 * The write is wrapped: on a failure one line names the SavedData class, its file and the paths of null keys in the
 * tag, then the same exception goes on as before. Nothing else changes (same task, same pool, same order).
 */
public final class SavedDataName {
    private SavedDataName() {}

    /** the copy SavedData.save just made (main thread: copy() and withIOWorker follow each other in one call) */
    private static final ThreadLocal<CompoundTag> COPIED = new ThreadLocal<>();

    public static void copied(CompoundTag tag) {
        COPIED.set(tag);
    }

    /** test only: -Dlab.test.savedDataNullKey=true puts a null key into the first SavedData tag written (its write fails as 11-21's did) */
    private static final java.util.concurrent.atomic.AtomicBoolean TEST_NULL_KEY = new java.util.concurrent.atomic.AtomicBoolean(Boolean.getBoolean("lab.test.savedDataNullKey"));

    public static Runnable wrap(Runnable task, Object data, File file) {
        CompoundTag tag = COPIED.get();
        COPIED.remove();
        if (tag != null && TEST_NULL_KEY.compareAndSet(true, false) && tag.get("data") instanceof CompoundTag d) d.put(null, new CompoundTag());
        return () -> {
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                String where;
                try {
                    List<String> nulls = new ArrayList<>();
                    if (tag != null) nullKeys(tag, "", nulls, 0);
                    where = nulls.isEmpty() ? "no null keys" : "null keys at " + nulls;
                } catch (Throwable t) {
                    where = "tag not readable: " + t;
                }
                System.out.println("[sleep] SavedData " + data.getClass().getName() + " (" + (file == null ? "?" : file.getName()) + ") could not be written: " + e + "; " + where);
                throw e;
            }
        };
    }

    private static void nullKeys(Tag t, String path, List<String> out, int depth) {
        if (depth > 64 || out.size() >= 10) return;
        if (t instanceof CompoundTag c) {
            for (String k : c.getAllKeys()) {
                Tag v = c.get(k);
                String p = path.isEmpty() ? String.valueOf(k) : path + "." + k;
                if (k == null) out.add(path.isEmpty() ? "<null>" : path + ".<null>");
                if (v == null) out.add(p + " = null value");
                else nullKeys(v, p, out, depth + 1);
            }
        } else if (t instanceof ListTag l) {
            for (int i = 0; i < l.size(); i++) nullKeys(l.get(i), path + "[" + i + "]", out, depth + 1);
        }
    }
}
