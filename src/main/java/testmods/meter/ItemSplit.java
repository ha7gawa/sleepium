package testmods.meter;

/**
 * Test only (-Dsleep.meter.tickSplit=true): the client's ItemEntity.tick timed in parts - the whole tick, move, super.tick
 * (the entity base tick: fluids, fire, portals) and the fluid update - with the items ticked and moved; every 200 game
 * ticks the averages per game tick are printed. Changes nothing in the game.
 */
public final class ItemSplit {
    private ItemSplit() {}

    private static long t0, tm, ts, tf, all, move, sup, fluid, ticked, moved, lastTime = -1, ticks;

    private static boolean client(net.minecraft.world.entity.Entity e) {
        return e.level().isClientSide;
    }

    public static void begin(net.minecraft.world.entity.Entity e) {
        if (client(e)) t0 = System.nanoTime();
    }

    public static void end(net.minecraft.world.entity.Entity e) {
        if (!client(e) || t0 == 0) return;
        all += System.nanoTime() - t0;
        t0 = 0;
        ticked++;
        long gt = e.level().getGameTime();
        if (gt != lastTime) {
            lastTime = gt;
            if (++ticks % 200 == 0) {
                System.out.printf(java.util.Locale.ROOT, "[sleep] item split (per game tick): items %d, moved %d, tick %.2f ms (move %.2f, super.tick %.2f, fluid update %.2f)%n",
                        ticked / 200, moved / 200, all / 200 / 1e6, move / 200 / 1e6, sup / 200 / 1e6, fluid / 200 / 1e6);
                all = move = sup = fluid = ticked = moved = 0;
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
