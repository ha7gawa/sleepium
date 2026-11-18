package dev.lull.optsupport;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

/**
 * -Dlab.dumpTickers=N (11-18, measuring): at server tick N every level's block-entity tickers are printed - position
 * and type - once ("[sleep/tickers] dim x y z type"). Which placed blocks tick, for the dense block-entity scenes.
 */
public final class TickerDump {
    private TickerDump() {}

    public static final int AT = Integer.getInteger("lab.dumpTickers", 0);
    private static int ticks;

    public static void tick(MinecraftServer server) {
        if (AT <= 0 || ++ticks != AT) return;
        for (ServerLevel level : server.getAllLevels()) {
            int n = 0;
            for (TickingBlockEntity t : level.blockEntityTickers) {
                if (t.isRemoved()) continue;
                var p = t.getPos();
                System.out.println("[sleep/tickers] " + level.dimension().location() + " " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + t.getType());
                n++;
            }
            System.out.println("[sleep/tickers] " + level.dimension().location() + " total " + n);
        }
    }
}
