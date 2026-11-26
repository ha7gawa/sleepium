package dev.lull.sleepium.bench;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.lull.sleepium.Sleepium;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * -Dsleep.bench=<label> (1.7.10): a fresh void world, the scene (assets/sleepium/bench/<scene>.txt) built by commands,
 * the view held (flying, creative), SECS seconds of frames measured after SETTLE; one line (fps, frame p50 / p99), a
 * screenshot (screenshots/bench-<label>.png), then the game quits. -Dsleep.bench.cmdFile: lines "<seconds> <command>"
 * run during the window.
 */
public final class ClientBench {
    public static final String LABEL = System.getProperty("sleep.bench");
    static final String SCENE = System.getProperty("sleep.bench.scene", "client_entities");
    static final double[] TP = Arrays.stream(System.getProperty("sleep.bench.tp", "24 158 4 0 25").trim().split("[ ,]+")).mapToDouble(Double::parseDouble).toArray();
    static final int SETTLE = Integer.getInteger("sleep.bench.settle", 20), SECS = Integer.getInteger("sleep.bench.secs", 30);
    static final List<String[]> CMDS = new ArrayList<>();
    static final ConcurrentLinkedQueue<Runnable> SERVER = new ConcurrentLinkedQueue<>();
    static int state, cmdAt, n;
    static long mark, t0, last;
    static final long[] FRAMES = new long[1 << 20];

    static {
        String f = System.getProperty("sleep.bench.cmdFile");
        if (f != null) try {
            for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(f), StandardCharsets.UTF_8)) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int sp = l.indexOf(' ');
                CMDS.add(new String[] {l.substring(0, sp), l.substring(sp + 1)});
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static void register() {
        if (LABEL == null) return;
        Object o = new ClientBench();
        FMLCommonHandler.instance().bus().register(o);
    }

    static List<String> scene() {
        List<String> out = new ArrayList<>();
        try (InputStream in = ClientBench.class.getResourceAsStream("/assets/sleepium/bench/" + SCENE + ".txt")) {
            if (in == null) throw new IllegalStateException("no scene " + SCENE);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) if (!l.isEmpty()) out.add(l);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out;
    }

    static EntityPlayerMP player() {
        MinecraftServer srv = MinecraftServer.getServer();
        return srv == null || srv.getConfigurationManager().playerEntityList.isEmpty() ? null : (EntityPlayerMP) srv.getConfigurationManager().playerEntityList.get(0);
    }

    static void run(String c) {
        EntityPlayerMP p = player();
        if (p != null) MinecraftServer.getServer().getCommandManager().executeCommand(p, c);
    }

    /** the player at the view: creative, flying, position and look set on the server (the client follows) */
    static void place() {
        EntityPlayerMP p = player();
        if (p == null) return;
        p.setGameType(WorldSettings.GameType.CREATIVE);
        p.capabilities.isFlying = true;
        p.sendPlayerAbilities();
        p.playerNetServerHandler.setPlayerLocation(TP[0], TP[1], TP[2], (float) TP[3], (float) TP[4]);
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Runnable r;
        while ((r = SERVER.poll()) != null) r.run();
    }

    @SubscribeEvent
    public void frame(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || state != 4) return;
        long now = System.nanoTime();
        if (last != 0 && n < FRAMES.length) FRAMES[n++] = now - last;
        last = now;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (e.phase == TickEvent.Phase.START) {   // the view held
            if (state >= 3 && mc.thePlayer != null) {
                mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = (float) TP[3];
                mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = (float) TP[4];
                mc.thePlayer.motionX = mc.thePlayer.motionY = mc.thePlayer.motionZ = 0;
            }
            return;
        }
        long now = System.nanoTime();
        switch (state) {
            case 0 -> {
                if (mc.currentScreen instanceof GuiMainMenu) {
                    state = 1;
                    WorldSettings ws = new WorldSettings(20260924L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT);
                    ws.enableCommands();
                    ws.func_82750_a("2;0;1;");   // a void world (one layer of air)
                    mc.launchIntegratedServer("bench", "bench", ws);
                }
            }
            case 1 -> {
                if (mc.thePlayer != null && mc.theWorld != null && MinecraftServer.getServer() != null) {
                    state = 2;
                    mark = now;
                }
            }
            case 2 -> {
                if (now - mark > 3_000_000_000L) {
                    SERVER.add(() -> {
                        MinecraftServer.getServer().getConfigurationManager().func_152605_a(player().getGameProfile());   // op
                        place();
                    });
                    state = 6;
                    mark = now;
                }
            }
            case 6 -> {   // the scene once the chunks around the view are loaded
                if (now - mark > 3_000_000_000L) {
                    List<String> cmds = scene();
                    SERVER.add(() -> {
                        for (String c : cmds) run(c);
                        place();
                    });
                    state = 3;
                    mark = now;
                }
            }
            case 3 -> {
                if (now - mark > SETTLE * 1_000_000_000L) {
                    state = 4;
                    mc.gameSettings.hideGUI = true;   // (F1: no hand, no hotbar - the 1.12.2 bench's spectator showed none)
                    last = 0;
                    n = 0;
                    t0 = now;
                }
            }
            case 4 -> {
                if (cmdAt < CMDS.size() && now - t0 >= (long) (Double.parseDouble(CMDS.get(cmdAt)[0]) * 1e9)) {
                    String c = CMDS.get(cmdAt++)[1];
                    SERVER.add(() -> run(c));
                    Sleepium.LOGGER.info("[sleepbench] command at " + (now - t0) / 1_000_000 + " ms: " + c);
                }
                if (now - t0 > SECS * 1_000_000_000L) {
                    state = 5;
                    long[] f = Arrays.copyOf(FRAMES, n);
                    Arrays.sort(f);
                    double fps = n / ((now - t0) / 1e9);
                    boolean at = Math.abs(mc.thePlayer.posX - TP[0]) < 1 && Math.abs(mc.thePlayer.posZ - TP[2]) < 1;
                    Sleepium.LOGGER.info(String.format(Locale.ROOT, "[sleepbench] %s: %.0f fps, frame p50 %.2f / p99 %.2f ms, %d frames, entities %d, at the view %s",
                            LABEL, fps, n == 0 ? 0 : f[n / 2] / 1e6, n == 0 ? 0 : f[(int) (n * 0.99)] / 1e6, n, mc.theWorld.loadedEntityList.size(), at));
                    Sleepium.LOGGER.info("[sleepbench] " + BenchReports.all());
                    net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, "bench-" + LABEL + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                    mc.shutdown();
                }
            }
            default -> { }
        }
    }
}
