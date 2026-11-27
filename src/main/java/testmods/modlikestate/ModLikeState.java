package testmods.modlikestate;

/**
 * The mod-like test mixins' state, written the way many mods write it: plain HashMaps and plain counters, fine on the
 * server thread alone. The atomic counters beside them count the truth: lost plain updates, calls off the server
 * thread and exceptions show a parallel tick reaching mod code that was not made for it. A line every 200 ticks.
 */
public final class ModLikeState {
    private ModLikeState() {}

    static final java.util.HashMap<Integer, int[]> SHEEP = new java.util.HashMap<>();
    static final java.util.HashMap<Long, int[]> CROPS = new java.util.HashMap<>();
    static long sheepPlain, cropPlain;
    static final java.util.concurrent.atomic.AtomicLong sheepTrue = new java.util.concurrent.atomic.AtomicLong(), cropTrue = new java.util.concurrent.atomic.AtomicLong(),
            sheepOff = new java.util.concurrent.atomic.AtomicLong(), cropOff = new java.util.concurrent.atomic.AtomicLong(),
            errors = new java.util.concurrent.atomic.AtomicLong();
    static volatile Thread server;

    public static void sheep(int id) {
        sheepTrue.incrementAndGet();
        if (Thread.currentThread() != server) sheepOff.incrementAndGet();
        try {
            sheepPlain++;
            SHEEP.computeIfAbsent(id, k -> new int[1])[0]++;
        } catch (Throwable t) {
            errors.incrementAndGet();
        }
    }

    public static void crop(long pos) {
        cropTrue.incrementAndGet();
        if (Thread.currentThread() != server) cropOff.incrementAndGet();
        try {
            cropPlain++;
            CROPS.computeIfAbsent(pos, k -> new int[1])[0]++;
        } catch (Throwable t) {
            errors.incrementAndGet();
        }
    }

    private static int ticks;

    private static final java.util.ArrayList<long[]> ACTIVE_FEATURES = new java.util.ArrayList<>();
    private static long featureCalls, featureOverlaps;

    public static void enterFeatures(int cx, int cz) {
        synchronized (ACTIVE_FEATURES) {
            featureCalls++;
            for (long[] a : ACTIVE_FEATURES) {
                if (Math.abs(a[0] - cx) <= 2 && Math.abs(a[1] - cz) <= 2) {
                    if (++featureOverlaps <= 5) {
                        System.out.println("[sleep/test] OVERLAPPING FEATURES WITHOUT LOCK: (" + cx + "," + cz + ") vs (" + a[0] + "," + a[1] + ") total=" + featureOverlaps);
                    }
                }
            }
            ACTIVE_FEATURES.add(new long[] {cx, cz});
            if (featureCalls == 1 || featureCalls % 100 == 0) {
                System.out.println("[sleep/test] overwriteChunkGen: features=" + featureCalls + " overlaps=" + featureOverlaps);
            }
        }
    }

    public static void leaveFeatures(int cx, int cz) {
        synchronized (ACTIVE_FEATURES) {
            for (int i = ACTIVE_FEATURES.size() - 1; i >= 0; i--) {
                long[] a = ACTIVE_FEATURES.get(i);
                if (a[0] == cx && a[1] == cz) {
                    ACTIVE_FEATURES.remove(i);
                    return;
                }
            }
        }
    }

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> {
            server = Thread.currentThread();
            if (++ticks % 200 != 0) return;
            long mapSheep = 0, mapCrops = 0;
            try {
                for (int[] v : SHEEP.values()) mapSheep += v[0];
                for (int[] v : CROPS.values()) mapCrops += v[0];
            } catch (Throwable t) {
                errors.incrementAndGet();
            }
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[sleep/test] modlike: sheep calls %d (off the server thread %d), plain counter lost %d, map lost %d; crops calls %d (off %d), plain lost %d, map lost %d; exceptions %d",
                    sheepTrue.get(), sheepOff.get(), sheepTrue.get() - sheepPlain, sheepTrue.get() - mapSheep,
                    cropTrue.get(), cropOff.get(), cropTrue.get() - cropPlain, cropTrue.get() - mapCrops, errors.get()));
        });
    }
}
