package dev.lull.sleepium.bench;

import dev.lull.sleepium.Sleepium;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * /sleepbench gen <x> <z> <radius>: world generation timed on the server thread - every chunk of the square (block
 * coordinates, radius in blocks as Chunky) loaded, which generates it and populates it once its neighbours exist.
 * Unloaded and saved every 32 rows so memory stays flat. One line: chunks, seconds, chunks/s. The chunks must be new.
 */
public final class GenBench extends CommandBase {
    @Override
    public String getCommandName() {
        return "sleepbench";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/sleepbench gen <x> <z> <radius>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 4 || !"gen".equals(args[0])) throw new WrongUsageException(getCommandUsage(sender));
        run(MinecraftServer.getServer(), parseInt(sender, args[1]), parseInt(sender, args[2]), parseIntBounded(sender, args[3], 1, 4096), sender);
    }

    public static void run(MinecraftServer server, int x, int z, int r, ICommandSender sender) {
        WorldServer w = server.worldServerForDimension(0);
        ChunkProviderServer p = w.theChunkProviderServer;
        int cx0 = (x - r) >> 4, cx1 = (x + r) >> 4, cz0 = (z - r) >> 4, cz1 = (z + r) >> 4;
        long t0 = System.nanoTime();
        int n = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                p.loadChunk(cx, cz);
                n++;
            }
            if ((cx - cx0) % 32 == 31) {
                p.unloadAllChunks();
                for (int i = 0; i < 40; i++) p.unloadQueuedChunks();   // (a call unloads and saves up to 100 queued chunks)
            }
        }
        double s = (System.nanoTime() - t0) / 1e9;
        String line = String.format(java.util.Locale.ROOT, "[sleepbench] gen %d chunks in %.1f s = %.1f chunks/s", n, s, n / s);
        Sleepium.LOGGER.info(line + "; " + dev.lull.sleepium.server.TickIndex.report());
        if (sender != null) sender.addChatMessage(new ChatComponentText(line));
        p.unloadAllChunks();
    }
}
