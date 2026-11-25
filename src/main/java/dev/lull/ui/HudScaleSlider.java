package dev.lull.ui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The HUD's size, any percent from 25 to 400 (players asked for sizes such as 87 % or 172 %, 11-25): the slider snaps to a percent, the arrows step one. */
final class HudScaleSlider extends AbstractSliderButton {
    private static final int MIN = Math.round(Hud.SCALE_MIN * 100), MAX = Math.round(Hud.SCALE_MAX * 100);

    HudScaleSlider(int width) {
        super(0, 0, width, 20, Component.empty(), toValue(Math.round(Hud.scale * 100)));
        updateMessage();
    }

    private static double toValue(int pct) {
        return (double) (pct - MIN) / (MAX - MIN);
    }

    private int pct() {
        return (int) Math.round(MIN + value * (MAX - MIN));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(pct() + " %"));
    }

    @Override
    protected void applyValue() {
        int p = pct();
        value = toValue(p);
        Hud.scale = Hud.clampScale(p / 100f);
        Hud.changed();
    }

    @Override
    public void onRelease(double x, double y) {
        super.onRelease(x, y);
        Hud.save();   // the file once a drag ends, not at each step
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            int p = Math.max(MIN, Math.min(MAX, pct() + (key == GLFW.GLFW_KEY_RIGHT ? 1 : -1)));
            value = toValue(p);
            applyValue();
            updateMessage();
            Hud.save();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
