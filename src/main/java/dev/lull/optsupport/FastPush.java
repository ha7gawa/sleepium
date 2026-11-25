package dev.lull.optsupport;

import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * lab.fast (Maximum speed, not exact): crowd pushes without a serial commit. A run tick's doPush(other) - vanilla's
 * other.push(this), Entity.push(Entity) - is computed on the worker from the two positions as they are, and its two
 * impulses are added to each entity's accumulator (Acc: two doubles under the entity's monitor); after the run every
 * entity with an impulse gets one push(dx, 0, dz). Until now each push was a deferred lambda run on the server thread
 * at the run's end (pig pens: ~43 k a walk, 10-15 ms of the commit). Classes that override push(Entity) or
 * push(double, double, double) keep the deferred call (their own behaviour).
 */
public final class FastPush {
    private FastPush() {}

    /** an entity's pending impulse (fields added to Entity by ServerParallelItemsPushAccMixin) */
    public interface Acc {
        double sleep$pdx();
        double sleep$pdz();
        void sleep$pset(double dx, double dz);
        boolean sleep$pending();
        void sleep$setPending(boolean p);
    }

    private static final ClassValue<Boolean> PLAIN = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            try {
                // (LivingEntity's push(Entity) only skips a sleeping one: push() below does the same)
                Class<?> d = dev.lull.Names.publicMethod(c, "push", Entity.class).getDeclaringClass();
                return (d == Entity.class || d == net.minecraft.world.entity.LivingEntity.class)
                        && dev.lull.Names.publicMethod(c, "push", double.class, double.class, double.class).getDeclaringClass() == Entity.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };

    /** the entities with an impulse, per thread (a worker's list - only its thread adds; drained after the run) */
    private static final List<ArrayList<Entity>> LISTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final ThreadLocal<ArrayList<Entity>> MINE = ThreadLocal.withInitial(() -> {
        ArrayList<Entity> l = new ArrayList<>(256);
        LISTS.add(l);
        return l;
    });
    public static long pushes, applied, kept;

    /** both classes take vanilla's push: the impulse can be summed */
    public static boolean plain(Entity a, Entity b) {
        return PLAIN.get(a.getClass()) && PLAIN.get(b.getClass());
    }

    /** self.doPush(other) = other.push(self), vanilla's Entity.push(Entity) with other as this: the impulses summed */
    public static void push(Entity self, Entity other) {
        pushes++;
        if (other instanceof net.minecraft.world.entity.LivingEntity le && le.isSleeping()) return;   // (LivingEntity.push(Entity))
        if (other.isPassengerOfSameVehicle(self)) return;
        if (self.noPhysics || other.noPhysics) return;
        double d0 = self.getX() - other.getX();
        double d1 = self.getZ() - other.getZ();
        double d2 = net.minecraft.util.Mth.absMax(d0, d1);
        if (d2 < 0.01F) return;
        d2 = Math.sqrt(d2);
        d0 /= d2;
        d1 /= d2;
        double d3 = 1.0 / d2;
        if (d3 > 1.0) d3 = 1.0;
        d0 *= d3;
        d1 *= d3;
        d0 *= 0.05F;
        d1 *= 0.05F;
        if (!other.isVehicle() && other.isPushable()) add(other, -d0, -d1);
        if (!self.isVehicle() && self.isPushable()) add(self, d0, d1);
    }

    private static void add(Entity e, double dx, double dz) {
        Acc a = (Acc) e;
        boolean first;
        synchronized (e) {
            a.sleep$pset(a.sleep$pdx() + dx, a.sleep$pdz() + dz);
            first = !a.sleep$pending();
            if (first) a.sleep$setPending(true);
        }
        if (first) MINE.get().add(e);
    }

    /** after a run (server thread, workers idle): each entity's summed impulse as one push */
    public static void drain() {
        for (ArrayList<Entity> l : LISTS) {
            for (int i = 0, n = l.size(); i < n; i++) {
                Entity e = l.get(i);
                Acc a = (Acc) e;
                double dx = a.sleep$pdx(), dz = a.sleep$pdz();
                a.sleep$pset(0, 0);
                a.sleep$setPending(false);
                if (!e.isRemoved()) {
                    e.push(dx, 0.0, dz);
                    applied++;
                }
            }
            l.clear();
        }
    }
}
