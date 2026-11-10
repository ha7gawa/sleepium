package testmods.meter;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Test only (-Dsleep.meter.tickSplit=true): what the client's entity ticks would mean for parallel item ticks (notes/67)
 * - per item tick: a section crossed, particles / sounds / world writes made, a collidable entity near; per entity query
 * during another entity's tick: who asked, whether items came back, whether any of them comes before the asker in the
 * tick order. Printed every 200 game ticks. Changes nothing in the game.
 */
public final class EntityCensus {
    private EntityCensus() {}

    // the walk: each entity's index this tick, the one being ticked
    private static final Map<Entity, Integer> INDEX = new IdentityHashMap<>();
    private static Entity current;
    private static long walkTime = -1;
    private static int next;

    // item ticks
    private static long items, crossed, withParticles, withSounds, withWrites, collidableNear;
    private static long section, particles0, sounds0, writes0;
    public static long particles, sounds;
    private static boolean inItem;

    // queries by other entities' ticks: class -> {queries, with items, with an item before the asker}
    private static final Map<String, long[]> QUERIES = new TreeMap<>();

    public static void walk(Entity e, boolean begin) {
        if (!e.level().isClientSide) return;
        if (begin) {
            long t = e.level().getGameTime();
            if (t != walkTime) {
                walkTime = t;
                INDEX.clear();
                next = 0;
            }
            INDEX.put(e, next++);
            current = e;
        } else current = null;
    }

    public static void itemBegin(ItemEntity e) {
        if (!e.level().isClientSide) return;
        inItem = true;
        section = SectionPos.asLong(e.blockPosition());
        particles0 = particles;
        sounds0 = sounds;
        writes0 = dev.lull.BeTickStats.clientWrites;
    }

    public static void itemEnd(ItemEntity e) {
        if (!e.level().isClientSide || !inItem) return;
        inItem = false;
        items++;
        if (SectionPos.asLong(e.blockPosition()) != section) crossed++;
        if (particles != particles0) withParticles++;
        if (sounds != sounds0) withSounds++;
        if (dev.lull.BeTickStats.clientWrites != writes0) withWrites++;
        if (!e.level().getEntities(e, e.getBoundingBox().inflate(1.0), Entity::canBeCollidedWith).isEmpty()) collidableNear++;
    }

    /** an entity query answered while another entity ticked (Level.getEntities, both forms) */
    public static void query(List<?> result) {
        Entity asker = current;
        if (asker == null || asker instanceof ItemEntity || !asker.level().isClientSide) return;
        long[] q = QUERIES.computeIfAbsent(asker.getClass().getSimpleName(), k -> new long[3]);
        q[0]++;
        Integer ai = INDEX.get(asker);
        boolean any = false, before = false;
        for (Object o : result)
            if (o instanceof ItemEntity it) {
                any = true;
                Integer ii = INDEX.get(it);
                if (ii != null && ai != null && ii < ai) before = true;
            }
        if (any) q[1]++;
        if (before) q[2]++;
    }

    public static void print() {
        System.out.printf(java.util.Locale.ROOT, "[sleep] entity census (200 ticks): item ticks %d - crossed a section %d, particles %d, sounds %d, world writes %d, a collidable entity near %d; queries by other ticks {class=[queries, with items, with an item before the asker]}: %s%n",
                items, crossed, withParticles, withSounds, withWrites, collidableNear, toString(QUERIES));
        items = crossed = withParticles = withSounds = withWrites = collidableNear = 0;
        QUERIES.clear();
    }

    private static String toString(Map<String, long[]> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append(k).append('=').append(java.util.Arrays.toString(v)).append(' '));
        return sb.append('}').toString();
    }
}
