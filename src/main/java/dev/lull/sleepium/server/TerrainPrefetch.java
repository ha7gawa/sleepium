package dev.lull.sleepium.server;

import dev.lull.sleepium.Sleepium;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeCache;
import net.minecraft.world.biome.BiomeHills;
import net.minecraft.world.biome.BiomeMesa;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.biome.BiomeSavannaMutated;
import net.minecraft.world.biome.BiomeTaiga;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenCaves;
import net.minecraft.world.gen.MapGenRavine;
import net.minecraft.world.gen.layer.GenLayer;

/**
 * -Dsleep.opt.TerrainPrefetch (lab): the overworld's terrain - noise, biomes, surface, caves, ravines: the part of
 * ChunkGeneratorOverworld.generateChunk before the structures - computed ahead on worker threads for the chunks
 * around the ones being generated; the server thread takes the result and does the rest as vanilla (the structures'
 * registration in the same order, the Chunk, its biome array, its sky light map; population unchanged).
 *
 * <p>Same terrain: each worker has its own copy of the generator's per-call state (scratch arrays, Random), of the
 * cave and ravine carvers, of the biome provider (a deep copy of its GenLayer chain - the layers keep a mutable chunk
 * seed - and its own BiomeCache) and its own IntCache pools (IntCacheThreadMixin); the noise generators are shared, they
 * are only read. Every step is seeded from the chunk's coordinates as vanilla seeds it. Biomes that write their own
 * fields while building the surface (hills, mutated savanna, taiga: topBlock / fillerBlock per column, written before
 * they are read) run on the worker's copies; the server thread then sets the shared biomes' topBlock / fillerBlock to
 * what vanilla's pass over that chunk would have left (readers later - caves, lakes - see vanilla's values). A chunk
 * with a mesa (its bands are initialised lazily from the world) or a biome that is not vanilla's is left to vanilla.
 *
 * <p>Gives way: a generator, biome provider, carver or noise class that is not vanilla's, or a listener of Forge's
 * ReplaceBiomeBlocks event - the plain vanilla path then.
 */
public final class TerrainPrefetch {
    private TerrainPrefetch() {}

    public static final boolean ON = Boolean.getBoolean("sleep.opt.TerrainPrefetch");
    /** -Dsleep.terrain.verify: the server thread computes every chunk as vanilla too and compares stage by stage (vanilla's result is used) */
    public static final boolean VERIFY = Boolean.getBoolean("sleep.terrain.verify");
    public static long verified;
    public static final long[] STAGE_MISMATCH = new long[5];   // noise, biomes, surface, caves, ravines
    static final int RADIUS = Integer.getInteger("sleep.terrain.radius", 3);
    static final int MAX_PENDING = Integer.getInteger("sleep.terrain.maxPending", 512);
    static final int THREADS = Integer.getInteger("sleep.terrain.threads", Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2)));

    /** what a worker made for one chunk */
    public static final class Result {
        public final ChunkPrimer primer;
        public final Biome[] biomes;
        /** shared biome -> {topBlock, fillerBlock} after vanilla's pass over this chunk */
        public final Map<Biome, IBlockState[]> states;
        /** verify mode: the primer's blocks after noise, surface, caves, ravines */
        public char[][] stages;

        public Result(ChunkPrimer p, Biome[] b, Map<Biome, IBlockState[]> s) {
            primer = p;
            biomes = b;
            states = s;
        }
    }

    private static final ConcurrentHashMap<Long, CompletableFuture<Result>> PENDING = new ConcurrentHashMap<>();
    private static volatile String disabled;
    private static ChunkGeneratorOverworld source;
    private static World sourceWorld;
    public static long used, vanilla, aborted, requested, waits, waitNanos;
    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "Sleepium terrain " + NAMES.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final ThreadLocal<Worker> WORKER = new ThreadLocal<>();

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** the server thread, at the start of generateChunk: is the generator one we may run on workers */
    public static boolean usable(ChunkGeneratorOverworld gen, World world) {
        if (!ON || disabled != null) return false;
        if (source == gen) return true;
        String why = check(gen, world);
        if (why != null) {
            disabled = why;
            Sleepium.LOGGER.info("[sleep] terrain prefetch off: {}", why);
            return false;
        }
        source = gen;
        sourceWorld = world;
        Sleepium.LOGGER.info("[sleep] terrain prefetch on: {} worker threads, radius {}", THREADS, RADIUS);
        return true;
    }

    private static String check(ChunkGeneratorOverworld gen, World world) {
        if (gen.getClass() != ChunkGeneratorOverworld.class) return "generator " + gen.getClass().getName();
        if (world.getBiomeProvider().getClass() != BiomeProvider.class) return "biome provider " + world.getBiomeProvider().getClass().getName();
        try {
            for (Field f : fields(ChunkGeneratorOverworld.class)) {
                Object v = f.get(gen);
                if (v instanceof MapGenBase && f.getType() == MapGenBase.class && v.getClass() != MapGenCaves.class && v.getClass() != MapGenRavine.class)
                    return "carver " + v.getClass().getName();
                if (v != null && f.getType().getName().startsWith("net.minecraft.world.gen.NoiseGenerator") && !v.getClass().getName().startsWith("net.minecraft.world.gen.NoiseGenerator"))
                    return "noise " + v.getClass().getName();
            }
        } catch (ReflectiveOperationException e) {
            return "reflection: " + e;
        }
        net.minecraftforge.event.terraingen.ChunkGeneratorEvent.ReplaceBiomeBlocks ev =
                new net.minecraftforge.event.terraingen.ChunkGeneratorEvent.ReplaceBiomeBlocks(gen, 0, 0, new ChunkPrimer(), world);
        int busId = busId();
        if (busId < 0 || ev.getListenerList().getListeners(busId).length > 0) return "a listener of ReplaceBiomeBlocks";
        return null;
    }

    private static int busId() {
        try {
            Field f = net.minecraftforge.fml.common.eventhandler.EventBus.class.getDeclaredField("busID");
            f.setAccessible(true);
            return f.getInt(net.minecraftforge.common.MinecraftForge.EVENT_BUS);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** the server thread: the chunks around (x, z) not generated yet are computed ahead */
    public static void requestAround(int x, int z, java.util.function.BiPredicate<Integer, Integer> generated) {
        if (PENDING.size() >= MAX_PENDING) {   // the player went elsewhere: forget what is far from here
            int far = RADIUS * 4;
            PENDING.entrySet().removeIf(e -> {
                long k = e.getKey();
                int cx = (int) (k >> 32), cz = (int) k;
                return Math.abs(cx - x) > far || Math.abs(cz - z) > far;
            });
        }
        for (int dx = -RADIUS; dx <= RADIUS; dx++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (PENDING.size() >= MAX_PENDING) return;
                int cx = x + dx, cz = z + dz;
                long k = key(cx, cz);
                if ((dx == 0 && dz == 0) || PENDING.containsKey(k) || generated.test(cx, cz)) continue;
                requested++;
                PENDING.put(k, CompletableFuture.supplyAsync(() -> compute(cx, cz), POOL));
            }
    }

    /** the server thread: the prefetched terrain of (x, z), waiting for it when it is being computed; null: none */
    public static Result take(int x, int z) {
        CompletableFuture<Result> f = PENDING.remove(key(x, z));
        if (f == null) return null;
        if (!f.isDone()) {
            waits++;
            long t = System.nanoTime();
            Result r0 = f.join();
            waitNanos += System.nanoTime() - t;
            if (r0 == null) aborted++;
            return r0;
        }
        Result r = f.join();
        if (r == null) aborted++;
        return r;
    }

    static Result compute(int x, int z) {
        Worker w = WORKER.get();
        if (w == null) {
            try {
                w = new Worker(source, sourceWorld);
            } catch (ReflectiveOperationException e) {
                disabled = "worker copy: " + e;
                return null;
            }
            WORKER.set(w);
        }
        try {
            return w.compute(x, z);
        } catch (Throwable t) {
            Sleepium.LOGGER.warn("[sleep] terrain prefetch of {} {} failed, vanilla generates it: {}", x, z, t.toString());
            return null;
        }
    }

    /** on a worker thread: its biome provider (ChunkGeneratorOverworld.setBlocksInChunk asks the world's) */
    public static BiomeProvider provider(World world) {
        Worker w = WORKER.get();
        return w != null && w.computing ? w.provider : world.getBiomeProvider();
    }

    /** on a worker thread: the biome of a column for the carvers (vanilla asks the world; same value, from the worker's provider, mapped to its copies) */
    public static Biome biome(World world, BlockPos pos) {
        Worker w = WORKER.get();
        if (w == null || !w.computing) return world.getBiome(pos);
        return w.view(w.provider.getBiome(pos, net.minecraft.init.Biomes.PLAINS));
    }

    static boolean mutating(Biome b) {
        return b instanceof BiomeHills || b instanceof BiomeSavannaMutated || b instanceof BiomeTaiga;
    }

    /** one worker thread's copies */
    static final class Worker {
        final ChunkGeneratorOverworld gen;
        final BiomeProvider provider;
        final MapGenBase caves, ravines;
        final World world;
        final Map<Biome, Biome> copies = new IdentityHashMap<>();
        boolean computing;
        private final Field biomesField;

        Worker(ChunkGeneratorOverworld src, World world) throws ReflectiveOperationException {
            this.world = world;
            gen = shallowCopy(src);
            MapGenBase c = null, r = null;
            Field biomes = null;
            for (Field f : fields(ChunkGeneratorOverworld.class)) {
                Object v = f.get(gen);
                if (f.getType() == Random.class) f.set(gen, new Random());
                else if (v instanceof double[]) f.set(gen, ((double[]) v).clone());
                else if (v instanceof float[]) f.set(gen, ((float[]) v).clone());
                else if (f.getType() == Biome[].class) {
                    f.set(gen, null);
                    biomes = f;
                } else if (v instanceof MapGenCaves || v instanceof MapGenRavine) {
                    MapGenBase m = shallowCopy((MapGenBase) v);
                    // its own Random and its own scratch arrays (MapGenRavine's rs: shared, it raced - 272 ravine chunks off)
                    for (Field g : fields(v.getClass())) {
                        Object gv = g.get(m);
                        if (g.getType() == Random.class) g.set(m, new Random());
                        else if (gv instanceof float[]) g.set(m, ((float[]) gv).clone());
                        else if (gv instanceof double[]) g.set(m, ((double[]) gv).clone());
                        else if (gv instanceof int[]) g.set(m, ((int[]) gv).clone());
                    }
                    f.set(gen, m);
                    if (v instanceof MapGenCaves) c = m;
                    else r = m;
                }
            }
            caves = c;
            ravines = r;
            biomesField = biomes;
            provider = copyProvider(world.getBiomeProvider());
        }

        Biome view(Biome b) {
            if (!mutating(b)) return b;
            return copies.computeIfAbsent(b, k -> {
                try {
                    return shallowCopy(k);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        Result compute(int x, int z) throws ReflectiveOperationException {
            computing = true;
            try {
                // vanilla's generateChunk, up to the structures
                Field rf = null;
                for (Field f : fields(ChunkGeneratorOverworld.class)) if (f.getType() == Random.class) rf = f;
                ((Random) rf.get(gen)).setSeed((long) x * 341873128712L + (long) z * 132897987541L);
                ChunkPrimer primer = new ChunkPrimer();
                gen.setBlocksInChunk(x, z, primer);   // its world.getBiomeProvider() -> this worker's (TerrainPrefetchMixin)
                char[][] st = VERIFY ? new char[4][] : null;
                if (st != null) st[0] = data(primer).clone();
                Biome[] biomes = provider.getBiomes((Biome[]) biomesField.get(gen), x * 16, z * 16, 16, 16);
                biomesField.set(gen, biomes);
                for (Biome b : biomes)
                    if (b instanceof BiomeMesa || !b.getClass().getName().startsWith("net.minecraft.world.biome.")) return null;   // vanilla's then
                Biome[] mapped = new Biome[biomes.length];
                for (int i = 0; i < biomes.length; i++) mapped[i] = view(biomes[i]);
                gen.replaceBiomeBlocks(x, z, primer, mapped);
                if (st != null) st[1] = data(primer).clone();
                if (caves != null) caves.generate(world, x, z, primer);
                if (st != null) st[2] = data(primer).clone();
                if (ravines != null) ravines.generate(world, x, z, primer);
                if (st != null) st[3] = data(primer).clone();
                Map<Biome, IBlockState[]> states = new IdentityHashMap<>();
                for (Biome b : biomes)
                    if (mutating(b) && !states.containsKey(b)) {
                        Biome copy = copies.get(b);
                        states.put(b, new IBlockState[] {copy.topBlock, copy.fillerBlock});
                    }
                Result res = new Result(primer, biomes.clone(), states);
                res.stages = st;
                return res;
            } finally {
                computing = false;
            }
        }
    }

    static BiomeProvider copyProvider(BiomeProvider src) throws ReflectiveOperationException {
        BiomeProvider p = shallowCopy(src);
        Map<Object, Object> memo = new IdentityHashMap<>();
        for (Field f : fields(BiomeProvider.class)) {
            Object v = f.get(p);
            if (v instanceof GenLayer) f.set(p, copyLayer((GenLayer) v, memo));
            else if (v instanceof BiomeCache) f.set(p, new BiomeCache(p));
        }
        return p;
    }

    static GenLayer copyLayer(GenLayer l, Map<Object, Object> memo) throws ReflectiveOperationException {
        Object done = memo.get(l);
        if (done != null) return (GenLayer) done;
        GenLayer c = shallowCopy(l);
        memo.put(l, c);
        for (Field f : fields(l.getClass())) {
            Object v = f.get(c);
            if (v instanceof GenLayer) f.set(c, copyLayer((GenLayer) v, memo));
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    static <T> T shallowCopy(T o) throws ReflectiveOperationException {
        Object c = unsafe().allocateInstance(o.getClass());
        for (Field f : fields(o.getClass())) f.set(c, f.get(o));
        return (T) c;
    }

    static List<Field> fields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                out.add(f);
            }
        return out;
    }

    private static sun.misc.Unsafe UNSAFE;

    static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        if (UNSAFE == null) {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        }
        return UNSAFE;
    }

    private static Field DATA;

    public static char[] data(ChunkPrimer p) {
        try {
            if (DATA == null)
                for (Field f : fields(ChunkPrimer.class)) if (f.getType() == char[].class) DATA = f;
            return (char[]) DATA.get(p);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String report() {
        return "terrain prefetch: " + (!ON ? "off" : disabled != null ? "gave way (" + disabled + ")" : "on")
                + ", used " + used + ", vanilla " + vanilla + ", aborted " + aborted + ", requested " + requested
                + String.format(java.util.Locale.ROOT, ", waited %d times %.1f s", waits, waitNanos / 1e9)
                + (VERIFY ? ", verified " + verified + ", mismatches noise/biomes/surface/caves/ravines " + java.util.Arrays.toString(STAGE_MISMATCH) : "");
    }
}
