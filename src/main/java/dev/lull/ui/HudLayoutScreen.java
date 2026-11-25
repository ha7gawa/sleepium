package dev.lull.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The HUD's boxes moved with the mouse (players asked for it, 11-25): the HUD drawn over the game as it is, a box dragged
 * where it should be, the arrows move the chosen box a pixel of the HUD (Shift: ten), the wheel sets the size a percent (Shift: ten).
 * A place is kept as a fraction of the room beside the box (Hud.pos), so a box stays where it was put at any window size.
 */
public final class HudLayoutScreen extends Screen {
    private final Screen parent;
    private final List<Hud.Box> boxes = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private String chosen, dragging;
    private double grabX, grabY;

    HudLayoutScreen(Screen parent) {
        super(Component.translatable("sleepium.ui.hud.layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = height - 26;
        buttons.clear();
        buttons.add(addRenderableWidget(Button.builder(Component.translatable("sleepium.ui.hud.layout.reset"), b -> Hud.resetPositions())
                .bounds(width / 2 - 154, y, 100, 20).build()));
        buttons.add(addRenderableWidget(Button.builder(Component.translatable("sleepium.ui.hud.layout.size100"), b -> {
            Hud.scale = 1f;
            Hud.save();
        }).bounds(width / 2 - 50, y, 100, 20).build()));
        buttons.add(addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 + 54, y, 100, 20).build()));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (minecraft == null || minecraft.level == null) super.renderBackground(g, mouseX, mouseY, partialTick);   // the game itself behind the HUD
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        for (Button b : buttons) b.visible = dragging == null;   // a box dragged to the bottom is not hidden under them
        super.render(g, mouseX, mouseY, partialTick);
        boxes.clear();
        Hud.drawForLayout(g, boxes);
        for (Hud.Box b : boxes) {
            boolean on = b.id().equals(chosen) || b.id().equals(dragging), over = inside(b, mouseX, mouseY);
            outline(g, b, on ? 0xFFFFC857 : over ? 0xFFFFFFFF : 0x80FFFFFF);
        }
        if (dragging != null) return;
        int y = height - 52;
        g.fill(0, y - 3, width, y + 21, 0xA0000000);
        g.drawCenteredString(font, Component.translatable("sleepium.ui.hud.layout.hint"), width / 2, y, 0xFFFFFF);
        Hud.Box c = box(chosen);
        String line = String.format(Locale.ROOT, "%s %d %%", Component.translatable("sleepium.ui.hud.scale").getString(), Math.round(Hud.scale * 100));
        if (c != null)
            line = Component.translatable("sleepium.ui.hud.layout.box", name(c.id()), Math.round(c.x0()), Math.round(c.y0())).getString() + " · " + line;
        if (!SleepStats.hud) line += " · " + Component.translatable("sleepium.ui.hud.layout.off").getString();
        g.drawCenteredString(font, line, width / 2, y + 11, 0xAAAAAA);
    }

    private static void outline(GuiGraphics g, Hud.Box b, int color) {
        int x0 = (int) Math.floor(b.x0()), y0 = (int) Math.floor(b.y0()), x1 = (int) Math.ceil(b.x1()), y1 = (int) Math.ceil(b.y1());
        g.fill(x0 - 1, y0 - 1, x1 + 1, y0, color);
        g.fill(x0 - 1, y1, x1 + 1, y1 + 1, color);
        g.fill(x0 - 1, y0, x0, y1, color);
        g.fill(x1, y0, x1 + 1, y1, color);
    }

    private static String name(String id) {
        return Component.translatable(id.length() == 2 ? "sleepium.ui.hud.c." + id : "sleepium.ui.hud.w." + id).getString();
    }

    private static boolean inside(Hud.Box b, double x, double y) {
        return x >= b.x0() && x < b.x1() && y >= b.y0() && y < b.y1();
    }

    private Hud.Box box(String id) {
        if (id == null) return null;
        for (Hud.Box b : boxes) if (b.id().equals(id)) return b;
        return null;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button != 0) return false;
        for (int i = boxes.size() - 1; i >= 0; i--) {   // the box drawn last is on top
            Hud.Box b = boxes.get(i);
            if (inside(b, x, y)) {
                chosen = dragging = b.id();
                grabX = x - b.x0();
                grabY = y - b.y0();
                return true;
            }
        }
        chosen = null;
        return false;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        Hud.Box b = box(dragging);
        if (b == null) return super.mouseDragged(x, y, button, dx, dy);
        Hud.moveBox(width, height, b, x - grabX + 3 * Hud.scale, y - grabY + 2 * Hud.scale);   // the box's edge is 3 / 2 out of its text
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (dragging != null) {
            dragging = null;
            Hud.save();
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (dy == 0) return false;
        int step = hasShiftDown() ? 10 : 1, pct = Math.round(Hud.scale * 100) + (dy > 0 ? step : -step);
        Hud.scale = Hud.clampScale(pct / 100f);
        Hud.save();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        Hud.Box b = box(chosen);
        if (b != null) {
            int step = hasShiftDown() ? 10 : 1, mx = 0, my = 0;
            switch (key) {
                case GLFW.GLFW_KEY_LEFT -> mx = -step;
                case GLFW.GLFW_KEY_RIGHT -> mx = step;
                case GLFW.GLFW_KEY_UP -> my = -step;
                case GLFW.GLFW_KEY_DOWN -> my = step;
                default -> {
                }
            }
            if (mx != 0 || my != 0) {
                Hud.moveBox(width, height, b, b.x0() + (mx + 3) * Hud.scale, b.y0() + (my + 2) * Hud.scale);   // a step = a pixel of the HUD
                Hud.save();
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        Hud.save();
        minecraft.setScreen(parent);
    }

    /**
     * test (ClientAuto cmdAt): "!hudopen" opens the screen; "!huddrag:box,x,y" drags that box (tl, tr, bl, br or a widget)
     * by its middle to the screen's fraction x, y through the mouse handlers; "!hudwheel:n" turns the wheel n notches;
     * "!hudkey:right,n" presses an arrow n times on the chosen box; "!hudsize:p" sets the size (percent)
     */
    public static void test(String cmd) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        String[] a = cmd.contains(":") ? cmd.substring(cmd.indexOf(':') + 1).split(",") : new String[0];
        if (cmd.startsWith("!hudpage")) {   // the settings' HUD page
            mc.setScreen(new SleepScreen(null, 7));
            return;
        }
        if (cmd.startsWith("!hudopen")) {
            mc.setScreen(new HudLayoutScreen(null));
            return;
        }
        if (cmd.startsWith("!hudsize")) {
            Hud.scale = Hud.clampScale(Integer.parseInt(a[0]) / 100f);
            Hud.save();
            return;
        }
        if (!(mc.screen instanceof HudLayoutScreen s)) {
            System.out.println("[sleep/hud] test " + cmd + ": the layout screen is not open");
            return;
        }
        switch (cmd.substring(0, cmd.indexOf(':'))) {
            case "!huddrag" -> {
                Hud.Box b = s.box(a[0]);
                if (b == null) {
                    System.out.println("[sleep/hud] test: no box " + a[0]);
                    return;
                }
                double fx = (b.x0() + b.x1()) / 2, fy = (b.y0() + b.y1()) / 2, tx = Double.parseDouble(a[1]) * s.width, ty = Double.parseDouble(a[2]) * s.height;
                s.mouseClicked(fx, fy, 0);
                for (int i = 1; i <= 10; i++) s.mouseDragged(fx + (tx - fx) * i / 10, fy + (ty - fy) * i / 10, 0, (tx - fx) / 10, (ty - fy) / 10);
                s.mouseReleased(tx, ty, 0);
            }
            case "!hudwheel" -> {
                int n = Integer.parseInt(a[0]);
                for (int i = Math.abs(n); i > 0; i--) s.mouseScrolled(s.width / 2.0, s.height / 2.0, 0, Integer.signum(n));
            }
            case "!hudkey" -> {
                int key = switch (a[0]) {
                    case "left" -> GLFW.GLFW_KEY_LEFT;
                    case "up" -> GLFW.GLFW_KEY_UP;
                    case "down" -> GLFW.GLFW_KEY_DOWN;
                    default -> GLFW.GLFW_KEY_RIGHT;
                };
                for (int i = Integer.parseInt(a[1]); i > 0; i--) s.keyPressed(key, 0, 0);
            }
            default -> System.out.println("[sleep/hud] test: what is " + cmd);
        }
        Hud.Box c = s.box(s.chosen);
        System.out.println("[sleep/hud] test " + cmd + ": size " + Math.round(Hud.scale * 100) + " %, chosen " + s.chosen
                + (c == null ? "" : String.format(Locale.ROOT, " at %.1f, %.1f", c.x0(), c.y0())) + ", place " + (s.chosen == null ? "-" : java.util.Arrays.toString(Hud.pos(s.chosen))));
    }

    @Override
    public boolean isPauseScreen() {
        return false;   // the numbers keep moving while the boxes are placed
    }
}
