package dev.lull;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;

/**
 * Exactness tests (-Dsleep.seedEntities=true): vanilla's randoms that are seeded from the clock, made reproducible so two
 * runs of a scene draw the same numbers. Entities and levels are seeded where they are made (SeedEntitiesMixin,
 * SeedLevelMixin); {@link #reseed} (/sleep reseed, right before a scene is built) resets the rest: each level's random,
 * thread-safe random and random-tick generator, the static randoms of Sensor (a sensor's first scan tick) and Mth, and
 * the counter that seeds every new ShufflingList (a brain's weighted behaviour order) and Explosion; and mods' static
 * clock-seeded randoms (STATICS: Create's).
 */
public final class TestSeeds {
    private TestSeeds() {}

    public static final boolean ON = Boolean.getBoolean("sleep.seedEntities");
    private static final AtomicLong NEXT = new AtomicLong();

    /** the seed of the next object made with a clock-seeded random */
    public static long next() {
        return NEXT.getAndIncrement() * 0x9E3779B97F4A7C15L + 4242;
    }

    public static void reseed(MinecraftServer server) {
        for (var lv : server.getAllLevels()) {
            long s = lv.dimension().location().toString().hashCode() * 31L + 777;
            lv.random.setSeed(s);
            ((dev.lull.mixin.SeedLevelAccessor) lv).sleep$threadSafeRandom().setSeed(s + 1);
            ((dev.lull.mixin.SeedLevelAccessor) lv).sleep$setRandValue((int) (s * 3 + 1));
        }
        RandomSource sensor = dev.lull.mixin.SeedSensorAccessor.sleep$random();
        sensor.setSeed(91);
        dev.lull.mixin.SeedMthAccessor.sleep$random().setSeed(92);
        NEXT.set(0);
        long k = 93;
        for (String spec : STATICS) seedStatic(spec, k++);
    }

    /**
     * Mods' static randoms made with new Random() (clock-seeded): Create's belt item angle (TransportedItemStack.R), its
     * shared RANDOM, the package style and logistics pickers; more with -Dsleep.seedStatics=Class#field,... A class that
     * is not there is skipped.
     */
    private static final java.util.List<String> STATICS = new java.util.ArrayList<>(java.util.List.of(
            "com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack#R",
            "com.simibubi.create.Create#RANDOM",
            "com.simibubi.create.content.logistics.box.PackageStyles#STYLE_PICKER",
            "com.simibubi.create.content.logistics.packagerLink.LogisticsManager#r"));

    static {
        String more = System.getProperty("sleep.seedStatics");
        if (more != null) for (String s : more.split(",")) if (!s.isBlank()) STATICS.add(s.trim());
    }

    private static void seedStatic(String spec, long seed) {
        int h = spec.indexOf('#');
        try {
            Class<?> c = Class.forName(spec.substring(0, h), true, TestSeeds.class.getClassLoader());
            java.lang.reflect.Field f = dev.lull.Names.fieldOf(c, spec.substring(h + 1));
            f.setAccessible(true);
            Object r = f.get(null);
            if (r instanceof java.util.Random jr) jr.setSeed(seed);
            else if (r instanceof RandomSource rs) rs.setSeed(seed);
        } catch (ClassNotFoundException e) {
            // the mod is not there
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[sleep/test] static random " + spec + " not seeded: " + e);
        }
    }
}
