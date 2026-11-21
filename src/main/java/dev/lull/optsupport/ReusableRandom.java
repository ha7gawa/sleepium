package dev.lull.optsupport;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * WorldgenAlloc (randoms): PositionalRandomFactory.at(x, y, z) builds a new XoroshiroRandomSource (three objects) for
 * one nextFloat/nextDouble at call sites that never keep it — surface gradients (bedrock, deepslate) per block, ore veins
 * per vein block, surface depth per column. One instance per thread and call site, reset to exactly the state the
 * constructor would give (Xoroshiro128PlusPlus's zero-seed replacement included, the gaussian's cached value dropped).
 * Factories other than Xoroshiro's are called as usual.
 */
public final class ReusableRandom {
    public static final int GRADIENT = 0, ORE = 1, DEPTH = 2, AQUIFER = 3;

    /** implemented by accessor mixins */
    public interface Factory {
        long sleep$seedLo();

        long sleep$seedHi();
    }

    public interface Source {
        net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus sleep$rng();

        net.minecraft.world.level.levelgen.MarsagliaPolarGaussian sleep$gaussian();
    }

    public interface State {
        void sleep$setLo(long v);

        void sleep$setHi(long v);
    }

    /**
     * -Dlab.opt.RandomSlots (11-21, lab): on our pool's threads the instances live in the thread's own slots
     * (ThreadSlots) - a field read instead of ThreadLocal's hash per call (ore veins ask per vein block: ThreadLocalMap
     * .getEntry was 2.8 % of doFill on .103)
     */
    private static final boolean THREAD_SLOTS = Boolean.getBoolean("lab.opt.RandomSlots");
    private static final ThreadLocal<XoroshiroRandomSource[]> SLOTS = ThreadLocal.withInitial(() -> new XoroshiroRandomSource[4]);

    private ReusableRandom() {}

    public static RandomSource at(PositionalRandomFactory f, int x, int y, int z, int slot) {
        if (!(f instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory) || !(f instanceof Factory fa)) return f.at(x, y, z);
        long lo = Mth.getSeed(x, y, z) ^ fa.sleep$seedLo(), hi = fa.sleep$seedHi();
        XoroshiroRandomSource[] a;
        Object[] ts;
        if (THREAD_SLOTS && (ts = ThreadSlots.mine()) != null) {
            a = (XoroshiroRandomSource[]) ts[ThreadSlots.RANDOMS];
            if (a == null) ts[ThreadSlots.RANDOMS] = a = new XoroshiroRandomSource[4];
        } else {
            a = SLOTS.get();
        }
        XoroshiroRandomSource r = a[slot];
        if (r == null) return a[slot] = new XoroshiroRandomSource(lo, hi);
        if ((lo | hi) == 0L) {   // as Xoroshiro128PlusPlus(long, long)
            lo = -7046029254386353131L;
            hi = 7640891576956012809L;
        }
        Source s = (Source) r;
        State st = (State) (Object) s.sleep$rng();
        st.sleep$setLo(lo);
        st.sleep$setHi(hi);
        s.sleep$gaussian().reset();
        return r;
    }
}
