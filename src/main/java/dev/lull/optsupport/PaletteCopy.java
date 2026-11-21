package dev.lull.optsupport;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.SingleValuePalette;

/**
 * PaletteCopy (-Dlab.opt.PaletteCopyMixin): PalettedContainer.Data.copyFrom - the copy into a bigger palette when a
 * section gets one block state more than its palette holds (features add ores, dirt, gravel, leaves ... to sections
 * the noise left with a few states: 4096 entries copied each time) - with the same palette calls and the same bits.
 *
 * <p>Vanilla, per entry i: {@code storage.set(i, palette.idFor(old.valueFor(oldStorage.get(i))))}. valueFor of the
 * vanilla palettes is a pure read, and idFor of the new palette changes it only the first time a value is seen (it
 * adds it); later calls with that value return the same id and change nothing. So the old id -> new id answer is
 * computed once per old id, at the entry where that old id first occurs - the same idFor calls that change the
 * palette, in the same order, the same ids - and the entries are written as whole words: the new storage's long[] gets
 * at word w the entries w * valuesPerLong ... packed low to high as SimpleBitStorage.set puts them (bits outside the
 * written slots kept). A new id outside the storage's range (vanilla's set throws) or an exception from a palette
 * writes the entries before it first, then the same call throws / vanilla's loop goes on, as vanilla would have.
 * Only for vanilla classes (old storage Simple / Zero with at most 8 bits, new storage Simple of the same size and not
 * the old one, palettes Single / Linear / HashMap -> Linear / HashMap / Global); anything else runs vanilla's loop.
 *
 * <p>-Dlab.featcpu.verify=true: before the copy the new palette and storage are copied, vanilla's loop is replayed on
 * the copies afterwards, and the raw words and palette entries must be equal.
 */
public final class PaletteCopy {
    public static final boolean VERIFY = Boolean.getBoolean("lab.featcpu.verify");
    private static final AtomicLong CHECKS = new AtomicLong(), BAD = new AtomicLong(), FAST = new AtomicLong();
    private static final ThreadLocal<int[]> REMAP = ThreadLocal.withInitial(() -> new int[256]);

    private PaletteCopy() {}

    private static boolean vanillaPalette(Class<?> c, boolean source) {
        return c == LinearPalette.class || c == HashMapPalette.class || (source ? c == SingleValuePalette.class : c == GlobalPalette.class);
    }

    /** the copy done (true), or not taken (false: the caller runs vanilla's loop) */
    public static <T> boolean copy(Palette<T> from, BitStorage fromStorage, Palette<T> to, BitStorage toStorage) {
        if (toStorage == fromStorage || toStorage.getClass() != SimpleBitStorage.class) return false;
        Class<?> fc = fromStorage.getClass();
        if (fc != SimpleBitStorage.class && fc != ZeroBitStorage.class) return false;
        if (!vanillaPalette(from.getClass(), true) || !vanillaPalette(to.getClass(), false)) return false;
        int size = fromStorage.getSize();
        if (toStorage.getSize() != size || fromStorage.getBits() > 8) return false;
        if (!VERIFY) {
            run(from, fromStorage, to, toStorage, size);
            return true;
        }
        long[] raw0 = toStorage.getRaw().clone();
        Palette<T> p0 = to.copy();
        run(from, fromStorage, to, toStorage, size);
        SimpleBitStorage s0 = new SimpleBitStorage(toStorage.getBits(), size, raw0);
        for (int i = 0; i < fromStorage.getSize(); i++) {
            T t = from.valueFor(fromStorage.get(i));
            s0.set(i, p0.idFor(t));
        }
        boolean ok = Arrays.equals(raw0, toStorage.getRaw()) && p0.getSize() == to.getSize();
        for (int k = 0; ok && k < p0.getSize(); k++) ok = p0.valueFor(k) == to.valueFor(k);
        long c = CHECKS.incrementAndGet();
        if (!ok) {
            long b = BAD.incrementAndGet();
            if (b <= 20) System.out.println("[sleep/featcpu] PALETTE COPY MISMATCH " + from.getClass().getSimpleName() + " -> "
                    + to.getClass().getSimpleName() + " bits " + fromStorage.getBits() + " -> " + toStorage.getBits() + ", palette "
                    + p0.getSize() + " vs " + to.getSize());
        }
        if ((c & ((1 << 14) - 1)) == 0) System.out.println("[sleep/featcpu] " + summary());
        return true;
    }

    private static <T> void run(Palette<T> from, BitStorage fromStorage, Palette<T> to, BitStorage toStorage, int size) {
        int fb = fromStorage.getBits();
        long[] src = fromStorage.getRaw();
        int fvpl = fb == 0 ? 1 : 64 / fb;
        long fmask = (1L << fb) - 1L;
        int[] remap = REMAP.get();
        Arrays.fill(remap, 0, 1 << fb, -1);
        long[] dst = toStorage.getRaw();
        int tb = toStorage.getBits();
        int tvpl = 64 / tb;
        long tmask = (1L << tb) - 1L;
        int w = 0, slot = 0, srcWord = 0, srcSlot = 0;
        long acc = 0L, used = 0L;
        int i = 0, bad = 0;
        try {
            for (; i < size; i++) {
                int o = fb == 0 ? 0 : (int) (src[srcWord] >>> (srcSlot * fb) & fmask);
                if (++srcSlot == fvpl) {
                    srcSlot = 0;
                    srcWord++;
                }
                int v = remap[o];
                if (v < 0) {
                    v = to.idFor(from.valueFor(o));
                    if (v < 0 || v > tmask) {
                        bad = v;
                        break;
                    }   // vanilla's set throws at this entry: below, after the entries before it
                    remap[o] = v;
                }
                acc |= (long) v << (slot * tb);
                used |= tmask << (slot * tb);
                if (++slot == tvpl) {
                    dst[w] = dst[w] & ~used | acc;
                    w++;
                    slot = 0;
                    acc = 0L;
                    used = 0L;
                }
            }
        } finally {
            if (slot > 0) dst[w] = dst[w] & ~used | acc;
        }
        FAST.incrementAndGet();
        if (i < size) {   // the out-of-range id: vanilla's set of entry i with it (throws), else vanilla's loop for the rest
            toStorage.set(i, bad);
            for (i++; i < fromStorage.getSize(); i++) {
                T u = from.valueFor(fromStorage.get(i));
                toStorage.set(i, to.idFor(u));
            }
        }
    }

    public static String summary() {
        return "palette copies " + FAST.get() + " fast, " + CHECKS.get() + " checked, " + BAD.get() + " mismatches";
    }
}
