package dev.lull;

import net.minecraft.client.Minecraft;

/**
 * Fabric lab (-Dsleep.fabricBench=label): the client opens the save "bench", makes its player an operator, builds a scene
 * (-Dsleep.fabricBench.scene, default client_entities), stands at -Dsleep.fabricBench.tp ("24 258 4 0 25", the NeoForge
 * client_entities view), waits settle seconds, then times every frame for secs seconds: fps, p50 / p99 frame ms. One line,
 * then the client stops. ClientAuto's role on NeoForge, kept small.
 */
public final class FabricBench {
    private FabricBench() {}

    static final String LABEL = System.getProperty("sleep.fabricBench");
    static final String SCENE = System.getProperty("sleep.fabricBench.scene", "client_entities");
    static final String TP = System.getProperty("sleep.fabricBench.tp", "24 258 4 0 25").replace(',', ' ');
    static final int SETTLE = Integer.getInteger("sleep.fabricBench.settle", 40), SECS = Integer.getInteger("sleep.fabricBench.secs", 30);

    static int state;
    static long t0, mark;
    static final long[] FRAMES = new long[1 << 20];
    static int n;
    static long last;

    public static void register() {
        if (LABEL == null) return;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(FabricBench::tick);
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.END.register(ctx -> frame());
    }

    static void frame() {
        if (state != 4) return;
        long now = System.nanoTime();
        if (last != 0 && n < FRAMES.length) FRAMES[n++] = now - last;
        last = now;
    }

    static final double tx, ty, tz;
    static final float tyaw, tpitch;
    static {
        String[] p = TP.trim().split("\s+");
        tx = Double.parseDouble(p[0]);
        ty = Double.parseDouble(p[1]);
        tz = Double.parseDouble(p[2]);
        tyaw = p.length > 3 ? Float.parseFloat(p[3]) : 0f;
        tpitch = p.length > 4 ? Float.parseFloat(p[4]) : 0f;
    }
    static long lastTp;
    static int fixes;

    static boolean atView(Minecraft mc) {
        return mc.player != null && mc.player.distanceToSqr(tx, ty, tz) < 1.0;   // (tp with whole numbers centres on the block: x.5, z.5)
    }

    /** the camera kept at the view: a teleport back (once a second at most) when moved, the look set every tick */
    static void hold(Minecraft mc, long now) {
        if (mc.player == null) return;
        if (!atView(mc) && now - lastTp > 1_000_000_000L) {
            cmd(mc, "tp @s " + TP);
            lastTp = now;
            fixes++;
        }
        mc.player.setYRot(tyaw);
        mc.player.setXRot(tpitch);
        mc.player.setDeltaMovement(0, 0, 0);
    }

    /** a command run on the integrated server as the player, operator level, its output suppressed (no chat involved) */
    static void cmd(Minecraft mc, String c) {
        var srv = mc.getSingleplayerServer();
        java.util.UUID id = mc.player.getUUID();
        srv.execute(() -> {
            var sp = srv.getPlayerList().getPlayer(id);
            if (sp == null) return;
            srv.getCommands().performPrefixedCommand(sp.createCommandSourceStack().withPermission(4).withSuppressedOutput(), c);
        });
    }

    static void tick(Minecraft mc) {
        long now = System.nanoTime();
        switch (state) {
            case 0 -> {
                if (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                    state = 1;
                    mc.createWorldOpenFlows().openWorld("bench", () -> mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
                }
            }
            case 1 -> {
                if (mc.player != null && mc.level != null && mc.getSingleplayerServer() != null) {
                    var srv = mc.getSingleplayerServer();
                    srv.execute(() -> srv.getPlayerList().op(mc.player.getGameProfile()));
                    state = 2;
                    mark = now;
                }
            }
            case 2 -> {
                if (now - mark > 3_000_000_000L) {
                    cmd(mc, "gamemode spectator");
                    cmd(mc, "tp @s " + TP);
                    cmd(mc, "function sleep:" + SCENE);
                    cmd(mc, "tp @s " + TP);
                    state = 3;
                    mark = now;
                }
            }
            case 3 -> {
                hold(mc, now);
                if (now - mark > SETTLE * 1_000_000_000L && atView(mc) && mc.level.hasChunk(((int) tx) >> 4, ((int) tz) >> 4)) {
                    state = 4;
                    last = 0;
                    n = 0;
                    t0 = now;
                }
            }
            case 4 -> {
                hold(mc, now);
                if (now - t0 > SECS * 1_000_000_000L) {
                    state = 5;
                    long[] f = java.util.Arrays.copyOf(FRAMES, n);
                    java.util.Arrays.sort(f);
                    long sum = 0;
                    for (long x : f) sum += x;
                    double fps = n / ((now - t0) / 1e9);
                    SleepMod.LOGGER.info(String.format(java.util.Locale.ROOT, "[fabricbench] %s: %.0f fps, frame p50 %.2f / p99 %.2f ms, %d frames, entities %d, at the view %s, view fixes %d",
                            LABEL, fps, n == 0 ? 0 : f[n / 2] / 1e6, n == 0 ? 0 : f[(int) (n * 0.99)] / 1e6, n, mc.level.getEntityCount(), atView(mc), fixes));
                    if (Boolean.getBoolean("lab.opt.RenderCacheMixin"))
                        SleepMod.LOGGER.info("[fabricbench] " + dev.lull.optsupport.RenderCache.report().replace("\n", "\n[fabricbench] "));
                    mc.stop();
                }
            }
            default -> { }
        }
    }
}
