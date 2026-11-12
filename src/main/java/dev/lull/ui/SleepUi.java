package dev.lull.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Where the settings screen opens (physical client only): the mod list's Config button, and a "Sleepium" button on the
 * game's Options screen (title screen and pause menu alike).
 */
public final class SleepUi {
    private SleepUi() {}

    public static void register(ModContainer container, IEventBus gameBus) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory) (c, parent) -> new SleepScreen(parent));
        gameBus.addListener(ScreenEvent.Init.Post.class, e -> {
            // the Options screen and the pause menu: a button at the top left
            if (!(e.getScreen() instanceof OptionsScreen) && !(e.getScreen() instanceof net.minecraft.client.gui.screens.PauseScreen p && p.showsPauseMenu())) return;
            var screen = e.getScreen();
            Button b = Button.builder(Component.translatable("sleepium.ui.open"), x -> Minecraft.getInstance().setScreen(new SleepScreen(screen)))
                    .bounds(6, 6, 72, 20).tooltip(Tooltip.create(Component.translatable("sleepium.ui.open.tooltip"))).build();
            e.addListener(b);
        });
        // live numbers: frame times (every frame), the integrated server's tick and the sleep engine (its thread)
        gameBus.addListener(net.neoforged.bus.api.EventPriority.LOWEST, net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class, e -> SleepStats.frame());
        // the main menu measures what it can before a world opens (Calibration: then kept, no measuring in the game)
        gameBus.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> dev.lull.optsupport.GpuAnim.menuTick());
        gameBus.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> SleepStats.serverTick(e.getServer()));
        gameBus.addListener(net.neoforged.neoforge.client.event.RenderGuiEvent.Post.class, e -> {
            if (Minecraft.getInstance().screen == null) SleepStats.drawHud(e.getGuiGraphics());
        });
        // a shaderpack being prepared: its progress in the corner, on menus and in the game alike
        gameBus.addListener(ScreenEvent.Render.Post.class, e -> preparing(e.getGuiGraphics()));
        gameBus.addListener(net.neoforged.neoforge.client.event.RenderGuiEvent.Post.class, e -> {
            if (Minecraft.getInstance().screen == null) preparing(e.getGuiGraphics());
        });
        if (SHOT != null) gameBus.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Post.class, e -> shots());
        if (APPLY_TEST != null) gameBus.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Post.class, e -> applyTest());
        if (SHOT_WORLD != null) gameBus.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Post.class, e -> worldShots());
        if (SWITCH != null) gameBus.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Post.class, e -> switchTest());
    }

    private static void preparing(net.minecraft.client.gui.GuiGraphics g) {
        String p = dev.lull.shaders.ShaderPipeline.preparing();
        if (p == null) return;
        int sp = p.lastIndexOf(' ');
        g.drawString(Minecraft.getInstance().font, Component.translatable("sleepium.shaders.preparing", p.substring(0, sp), p.substring(sp + 1)), 4, 4, 0xFFE08A, true);
    }

    /** -Dsleep.ui.shot=<dir>: a dev probe - opens the screen on the title screen, saves each tab to dir, quits */
    private static final String SHOT = System.getProperty("sleep.ui.shot");
    private static int shotFrame;
    private static String partofParent;

    private static void shots() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) && !(mc.screen instanceof SleepScreen)
                && !(mc.screen instanceof OptionsScreen) && !(mc.screen instanceof PackOptionsScreen) || mc.getOverlay() != null) return;   // (not while resources reload)
        int f = shotFrame++;
        String lang = System.getProperty("sleep.ui.lang");
        if (f == 10 && lang != null) {
            mc.getLanguageManager().setSelected(lang);
            mc.options.languageCode = lang;
            mc.reloadResourcePacks();
        }
        if (f == 40) mc.setScreen(new OptionsScreen(mc.screen, mc.options));
        if (f == 80) {
            try (var img = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                java.nio.file.Path out = java.nio.file.Path.of(SHOT).resolve("options.png");
                java.nio.file.Files.createDirectories(out.getParent());
                img.writeToFile(out);
            } catch (Exception ex) {
                System.out.println("[sleep/ui] shot: " + ex);
            }
        }
        if (f == 90) mc.setScreen(new SleepScreen(mc.screen, 0));
        if (System.getProperty("sleep.ui.partofTest") != null) {   // a part's switch shown (partof-0), clicked (partof-1)
            if (f == 100 && mc.screen instanceof SleepScreen s) partofParent = s.probeShowPart();
            if (f == 140 && mc.screen instanceof SleepScreen s && partofParent != null) s.probeClickPart(partofParent);
            if (f == 130 || f == 170) {
                try (var img = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    java.nio.file.Path out = java.nio.file.Path.of(SHOT).resolve("partof-" + (f == 130 ? 0 : 1) + ".png");
                    java.nio.file.Files.createDirectories(out.getParent());
                    img.writeToFile(out);
                } catch (Exception ex) {
                    System.out.println("[sleep/ui] shot: " + ex);
                }
                System.out.println("[sleep/ui] partof test: parent " + partofParent);
            }
            if (f == 180) mc.stop();
            return;
        }
        int[] at = {120, 160, 200, 240, 280, 320};
        for (int i = 0; i < at.length; i++) {
            if (f != at[i]) continue;
            try (var img = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                java.nio.file.Path out = java.nio.file.Path.of(SHOT).resolve("ui-" + i + ".png");
                java.nio.file.Files.createDirectories(out.getParent());
                img.writeToFile(out);
            } catch (Exception ex) {
                System.out.println("[sleep/ui] shot: " + ex);
            }
            if (System.getProperty("sleep.ui.presetTest") != null && i + 1 == 4) {   // the preset probe: chosen on the screen open now, Done, quit
                if (mc.screen instanceof SleepScreen s) s.probePresetAndClose(System.getProperty("sleep.ui.presetTest"));
                mc.stop();
            } else if (i + 1 < 4) mc.setScreen(new SleepScreen(((SleepScreen) mc.screen).parent(), i + 1));
            else if (i + 1 == 4) {   // the first pack's own options, then its first sub-screen
                try {
                    var dir = mc.gameDirectory.toPath().resolve("shaderpacks");
                    String first;
                    try (var st = java.nio.file.Files.list(dir)) {
                        first = st.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".zip")).sorted().findFirst().orElse(null);
                    }
                    if (first != null) mc.setScreen(new PackOptionsScreen(mc.screen, dev.lull.shaders.PackScreenModel.load(mc.gameDirectory.toPath(), first,
                            mc.getLanguageManager().getSelected())));
                } catch (Exception ex) {
                    System.out.println("[sleep/ui] shot: " + ex);
                }
            } else if (i + 1 == 5 && mc.screen instanceof PackOptionsScreen ps) {
                String sub = System.getProperty("sleep.ui.shotPackScreen", "PERFORMANCE_SETTINGS");
                ps.probeScreen(sub);
            }
            else {
                // -Dsleep.ui.saveTest=<key>: the screen's Done with that flag switched (config/sleepium.properties written)
                String key = System.getProperty("sleep.ui.saveTest");
                if (key != null && mc.screen instanceof SleepScreen s) s.probeToggleAndClose(key);
                String preset = System.getProperty("sleep.ui.presetTest");
                if (preset != null && mc.screen instanceof SleepScreen s) s.probePresetAndClose(preset);
                mc.stop();
            }
        }
    }

    /**
     * -Dsleep.ui.switchTest=<pack>,<dir>: a dev probe of switching packs live in a world - the pack at 3 s, none at 12 s,
     * the pack again at 20 s; a screenshot before each switch and at 28 s
     */
    private static final String SWITCH = System.getProperty("sleep.ui.switchTest");
    private static long levelAt;
    private static int step;

    private static void switchTest() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long now = System.nanoTime();
        if (levelAt == 0) levelAt = now;
        double t = (now - levelAt) / 1e9;
        double[] at = {3, 12, 20, 28};
        if (step >= at.length || t < at[step]) return;
        String[] a = SWITCH.split(",", 2);
        try (var img = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            java.nio.file.Path out = java.nio.file.Path.of(a[1]).resolve("switch-" + step + ".png");
            java.nio.file.Files.createDirectories(out.getParent());
            img.writeToFile(out);
        } catch (Exception ex) {
            System.out.println("[sleep/ui] shot: " + ex);
        }
        System.out.println("[sleep/ui] switch test step " + step + " at " + String.format("%.1f", t) + " s, pack " + dev.lull.shaders.ShaderPipeline.currentName());
        if (step == 0 || step == 2) dev.lull.shaders.ShaderPipeline.request(a[0]);
        else if (step == 1) dev.lull.shaders.ShaderPipeline.request(null);
        step++;
    }

    /** -Dsleep.ui.shotWorld=<dir>: in a world, the overview at 8 s and the corner stats at 14 s, saved to dir */
    private static final String SHOT_WORLD = System.getProperty("sleep.ui.shotWorld");
    private static long worldAt;
    private static int worldStep;

    private static void worldShots() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long now = System.nanoTime();
        if (worldAt == 0) worldAt = now;
        double t = (now - worldAt) / 1e9;
        if (worldStep == 0 && t > 8) {
            mc.setScreen(new SleepScreen(null, 0));
            worldStep = 1;
        } else if (worldStep == 1 && t > 11) {
            shot(mc, "world-overview");
            mc.setScreen(null);
            SleepStats.hud = true;
            worldStep = 2;
        } else if (worldStep == 2 && t > 14) {
            shot(mc, "world-hud");
            worldStep = 3;
        }
    }

    private static void shot(Minecraft mc, String name) {
        try (var img = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            java.nio.file.Path out = java.nio.file.Path.of(SHOT_WORLD).resolve(name + ".png");
            java.nio.file.Files.createDirectories(out.getParent());
            img.writeToFile(out);
        } catch (Exception ex) {
            System.out.println("[sleep/ui] shot: " + ex);
        }
    }

    /**
     * -Dsleep.ui.packApplyTest=OPTION=value,<dir>: in a world with the pack on, a shot, then the option set through the
     * pack options screen and applied, and a shot once the pack is on again (apply-before.png / apply-after.png)
     */
    private static final String APPLY_TEST = System.getProperty("sleep.ui.packApplyTest");
    private static long applyAt;
    private static int applyStep;

    private static void applyTest() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || dev.lull.shaders.ShaderPipeline.currentName() == null || dev.lull.shaders.ShaderPipeline.preparing() != null) return;
        long now = System.nanoTime();
        if (applyAt == 0) applyAt = now;
        double t = (now - applyAt) / 1e9;
        String[] a = APPLY_TEST.split(",", 2);
        String dir = a[1];
        if (applyStep == 0 && t > 6) {
            shotTo(mc, dir, "apply-before");
            try {
                String pack = dev.lull.shaders.ShaderPipeline.currentName();
                PackOptionsScreen s = new PackOptionsScreen(null, dev.lull.shaders.PackScreenModel.load(mc.gameDirectory.toPath(), pack, mc.getLanguageManager().getSelected()));
                mc.setScreen(s);
                String[] kv = a[0].split("=", 2);
                s.probeSetAndApply(kv[0], kv[1]);
                mc.setScreen(null);
                System.out.println("[sleep/ui] apply test: " + a[0] + " applied to " + pack);
            } catch (Exception ex) {
                System.out.println("[sleep/ui] apply test: " + ex);
            }
            applyStep = 1;
            applyAt = now;
        } else if (applyStep == 1 && t > 4) {
            shotTo(mc, dir, "apply-after");
            applyStep = 2;
        }
    }

    private static void shotTo(Minecraft mc, String dir, String name) {
        try (var img = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            java.nio.file.Path out = java.nio.file.Path.of(dir).resolve(name + ".png");
            java.nio.file.Files.createDirectories(out.getParent());
            img.writeToFile(out);
        } catch (Exception ex) {
            System.out.println("[sleep/ui] shot: " + ex);
        }
    }
}
