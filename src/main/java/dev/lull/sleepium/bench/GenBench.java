package dev.lull.sleepium.bench;

import dev.lull.sleepium.Sleepium;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * /sleepbench gen <x> <z> <radius>: world generation timed on the server thread - every chunk of the square (block
 * coordinates, radius in blocks as Chunky) provided, which generates it and populates it once its neighbours exist
 * (1.12.2 generates on the server thread only). Unloaded and saved every 32 rows so memory stays flat. One line:
 * chunks, seconds, chunks/s. The chunks of a run must be new (a fresh world or a far square).
 */
public final class GenBench extends CommandBase {
    @Override
    public String getName() {
        return "sleepbench";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/sleepbench gen <x> <z> <radius>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 4 || !"gen".equals(args[0])) throw new CommandException(getUsage(sender));
        run(server, parseInt(args[1]), parseInt(args[2]), parseInt(args[3], 1, 4096), sender);
    }

    public static void run(MinecraftServer server, int x, int z, int r, ICommandSender sender) {
        WorldServer w = server.getWorld(0);
        ChunkProviderServer p = w.getChunkProvider();
        int cx0 = (x - r) >> 4, cx1 = (x + r) >> 4, cz0 = (z - r) >> 4, cz1 = (z + r) >> 4;
        long t0 = System.nanoTime();
        int n = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                p.provideChunk(cx, cz);
                n++;
            }
            if ((cx - cx0) % 32 == 31) {
                p.queueUnloadAll();
                for (int i = 0; i < 40; i++) p.tick();   // (a tick unloads and saves up to 100 queued chunks)
            }
        }
        double s = (System.nanoTime() - t0) / 1e9;
        String line = String.format(java.util.Locale.ROOT, "[sleepbench] gen %d chunks in %.1f s = %.1f chunks/s", n, s, n / s);
        Sleepium.LOGGER.info(line + "; " + dev.lull.sleepium.server.TickIndex.report());
        if (sender != null) sender.sendMessage(new TextComponentString(line));
        p.queueUnloadAll();
    }
}
