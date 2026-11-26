package dev.lull.sleepium.bench;

import dev.lull.sleepium.Sleepium;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * -Dsleep.bench=label (client): opens the save "bench" (a creative flat world, made on the first run), builds the scene
 * (-Dsleep.bench.scene, default client_entities: assets/sleepium/bench/<scene>.txt, 1.12.2 commands run on the
 * integrated server), puts the player at -Dsleep.bench.tp ("24 158 4 0 25": x y z yaw pitch, spectator), waits settle
 * seconds, then times every frame for secs seconds. One line "[sleepbench] label: fps, frame p50 / p99, entities",
 * then the client stops. FabricBench's role on 1.12.2.
 */
public final class ClientBench {
    private ClientBench() {}

    static final String LABEL = System.getProperty("sleep.bench");
    static final String SCENE = System.getProperty("sleep.bench.scene", "client_entities");
    static final double[] TP = Arrays.stream(System.getProperty("sleep.bench.tp", "24 158 4 0 25").trim().split("[ ,]+")).mapToDouble(Double::parseDouble).toArray();
    static final int SETTLE = Integer.getInteger("sleep.bench.settle", 30), SECS = Integer.getInteger("sleep.bench.secs", 30);

    static int state;
    static long mark, t0, last;
    static final long[] FRAMES = new long[1 << 20];
    static int n;

    public static void register() {
        if (LABEL == null) return;
        MinecraftForge.EVENT_BUS.register(new ClientBench());
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {   // the view held: a mouse moved over the window turns the player
            Minecraft mc = Minecraft.getMinecraft();
            if ((state == 3 || state == 4) && mc.player != null) {
                mc.player.rotationYaw = mc.player.prevRotationYaw = (float) TP[3];
                mc.player.rotationPitch = mc.player.prevRotationPitch = (float) TP[4];
                mc.player.rotationYawHead = mc.player.prevRotationYawHead = (float) TP[3];
                // the camera's position too: a spectator after a teleport drifts by tiny amounts, and the frame
                // interpolates it by the fraction of a tick - fog at the horizon then varied by 1 LSB between runs
                mc.player.posX = mc.player.prevPosX = mc.player.lastTickPosX = TP[0];
                mc.player.posY = mc.player.prevPosY = mc.player.lastTickPosY = TP[1];
                mc.player.posZ = mc.player.prevPosZ = mc.player.lastTickPosZ = TP[2];
                mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
            }
            return;
        }
        if (e.phase != TickEvent.Phase.END || state != 4) return;
        long now = System.nanoTime();
        if (last != 0 && n < FRAMES.length) FRAMES[n++] = now - last;
        last = now;
    }

    /** -Dsleep.bench.cmdFile=<file>: lines "<seconds into the window> <command>" */
    static final List<String[]> CMDS = new ArrayList<>();
    static int cmdAt;

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

    static void run(IntegratedServer srv, EntityPlayerMP p, String c) {
        srv.getCommandManager().executeCommand(p, c);
    }

    static List<String> scene() {
        List<String> out = new ArrayList<>();
        try (var in = ClientBench.class.getResourceAsStream("/assets/sleepium/bench/" + SCENE + ".txt")) {
            if (in == null) throw new IllegalStateException("no scene " + SCENE);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) if (!l.isEmpty()) out.add(l);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out;
    }

    /** the player at the view: spectator, position and look set on the server (the client follows) */
    static void place(IntegratedServer srv, EntityPlayerMP p) {
        p.setGameType(GameType.SPECTATOR);
        p.connection.setPlayerLocation(TP[0], TP[1], TP[2], (float) TP[3], (float) TP[4]);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.nanoTime();
        switch (state) {
            case 0 -> {
                if (mc.currentScreen instanceof GuiMainMenu) {
                    state = 1;
                    WorldSettings ws = new WorldSettings(20260924L, GameType.CREATIVE, false, false, WorldType.FLAT);
                    ws.enableCommands();
                    // a void world: no far terrain to load at the render distance's edge (its fog varied between runs)
                    ws.setGeneratorOptions("3;minecraft:air;1;");
                    mc.launchIntegratedServer("bench", "bench", ws);   // an existing save opens as it is (the settings are for a new one)
                }
            }
            case 1 -> {
                if (mc.player != null && mc.world != null && mc.getIntegratedServer() != null) {
                    state = 2;
                    mark = now;
                }
            }
            case 2 -> {
                if (now - mark > 3_000_000_000L) {
                    IntegratedServer srv = mc.getIntegratedServer();
                    java.util.UUID id = mc.player.getUniqueID();
                    srv.addScheduledTask(() -> {
                        EntityPlayerMP p = srv.getPlayerList().getPlayerByUUID(id);
                        srv.getPlayerList().addOp(p.getGameProfile());
                        place(srv, p);   // the scene's chunks loaded around the player first
                    });
                    state = 6;
                    mark = now;
                }
            }
            case 6 -> {   // the scene once the chunks around the view are loaded (a command into an unloaded chunk is refused)
                if (now - mark > 3_000_000_000L) {
                    IntegratedServer srv = mc.getIntegratedServer();
                    java.util.UUID id = mc.player.getUniqueID();
                    List<String> cmds = scene();
                    srv.addScheduledTask(() -> {
                        EntityPlayerMP p = srv.getPlayerList().getPlayerByUUID(id);
                        for (String c : cmds) run(srv, p, c);
                        place(srv, p);
                    });
                    state = 3;
                    mark = now;
                }
            }
            case 3 -> {
                if (now - mark > SETTLE * 1_000_000_000L) {
                    state = 4;
                    FrameParts.counting = true;
                    last = 0;
                    n = 0;
                    t0 = now;
                }
            }
            case 4 -> {
                if (cmdAt < CMDS.size() && now - t0 >= (long) (Double.parseDouble(CMDS.get(cmdAt)[0]) * 1e9)) {   // -Dsleep.bench.cmdFile: changes during the window
                    String c = CMDS.get(cmdAt++)[1];
                    IntegratedServer srv = mc.getIntegratedServer();
                    java.util.UUID id = mc.player.getUniqueID();
                    srv.addScheduledTask(() -> run(srv, srv.getPlayerList().getPlayerByUUID(id), c));
                    Sleepium.LOGGER.info("[sleepbench] command at " + (now - t0) / 1_000_000 + " ms: " + c);
                }
                if (now - t0 > SECS * 1_000_000_000L) {
                    state = 5;
                    long[] f = Arrays.copyOf(FRAMES, n);
                    Arrays.sort(f);
                    double fps = n / ((now - t0) / 1e9);
                    boolean at = Math.abs(mc.player.posX - TP[0]) < 1 && Math.abs(mc.player.posY - TP[1]) < 1 && Math.abs(mc.player.posZ - TP[2]) < 1;
                    Sleepium.LOGGER.info(String.format(Locale.ROOT, "[sleepbench] %s: %.0f fps, frame p50 %.2f / p99 %.2f ms, %d frames, entities %d, at the view %s",
                            LABEL, fps, n == 0 ? 0 : f[n / 2] / 1e6, n == 0 ? 0 : f[(int) (n * 0.99)] / 1e6, n, mc.world.loadedEntityList.size(), at));
                    FrameParts.counting = false;
                    if (FrameParts.ON) Sleepium.LOGGER.info("[sleepbench] " + FrameParts.report(n));
                    Sleepium.LOGGER.info("[sleepbench] " + dev.lull.sleepium.render.ItemModelCache.report() + "; " + dev.lull.sleepium.render.BlockModelListCache.report() + "; " + dev.lull.sleepium.render.PartBatch.report() + "; " + dev.lull.sleepium.render.EntityCache.report());
                    // the frame for an exactness comparison (screenshots/bench-<label>.png; clouds off in the bench's options)
                    net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.gameDir, "bench-" + LABEL + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                    mc.shutdown();
                }
            }
            default -> { }
        }
    }
}
