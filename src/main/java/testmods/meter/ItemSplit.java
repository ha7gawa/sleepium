package testmods.meter;

/**
 * Test only (-Dsleep.meter.tickSplit=true): the client's ItemEntity.tick timed in parts - the whole tick, move, super.tick
 * (the entity base tick: fluids, fire, portals) and the fluid update - with the items ticked and moved; every 200 game
 * ticks the averages per game tick are printed. Changes nothing in the game.
 */
public final class ItemSplit {
    private ItemSplit() {}

    private static long t0, tm, ts, tf, all, move, sup, fluid, ticked, moved, lastTime = -1, ticks;
    /** Entity.move's parts for the client's items: the whole move, collide, setOnGroundWithMovement, tryCheckInsideBlocks */
    private static final long[] PT0 = new long[4], PART = new long[4];

    public static void part(net.minecraft.world.entity.Entity e, int k, boolean begin) {
        if (!(e instanceof net.minecraft.world.entity.item.ItemEntity) || !client(e)) return;
        if (begin) PT0[k] = System.nanoTime();
        else PART[k] += System.nanoTime() - PT0[k];
    }

    private static boolean client(net.minecraft.world.entity.Entity e) {
        return e.level().isClientSide;
    }

    public static void begin(net.minecraft.world.entity.Entity e) {
        if (client(e)) {
            EntityCensus.itemBegin((net.minecraft.world.entity.item.ItemEntity) e);
            t0 = System.nanoTime();
        }
    }

    public static void end(net.minecraft.world.entity.Entity e) {
        if (!client(e) || t0 == 0) return;
        all += System.nanoTime() - t0;
        t0 = 0;
        EntityCensus.itemEnd((net.minecraft.world.entity.item.ItemEntity) e);
        ticked++;
        long gt = e.level().getGameTime();
        if (gt != lastTime) {
            lastTime = gt;
            if (++ticks % 200 == 0) {
                System.out.printf(java.util.Locale.ROOT, "[sleep] item split (per game tick): items %d, moved %d, tick %.2f ms (move %.2f, super.tick %.2f, fluid update %.2f); move parts: collide %.2f, support %.2f, inside blocks %.2f, rest %.2f%n",
                        ticked / 200, moved / 200, all / 200 / 1e6, move / 200 / 1e6, sup / 200 / 1e6, fluid / 200 / 1e6,
                        PART[1] / 200 / 1e6, PART[2] / 200 / 1e6, PART[3] / 200 / 1e6, (PART[0] - PART[1] - PART[2] - PART[3]) / 200 / 1e6);
                all = move = sup = fluid = ticked = moved = 0;
                java.util.Arrays.fill(PART, 0);
                EntityCensus.print();
            }
        }
    }

    public static void moveBegin(net.minecraft.world.entity.Entity e) {
        if (client(e)) tm = System.nanoTime();
    }

    public static void moveEnd(net.minecraft.world.entity.Entity e) {
        if (client(e)) {
            move += System.nanoTime() - tm;
            moved++;
        }
    }

    public static void superBegin(net.minecraft.world.entity.Entity e) {
        if (client(e)) ts = System.nanoTime();
    }

    public static void superEnd(net.minecraft.world.entity.Entity e) {
        if (client(e)) sup += System.nanoTime() - ts;
    }

    public static void fluidBegin(net.minecraft.world.entity.Entity e) {
        if (client(e)) tf = System.nanoTime();
    }

    public static void fluidEnd(net.minecraft.world.entity.Entity e) {
        if (client(e)) fluid += System.nanoTime() - tf;
    }
}
