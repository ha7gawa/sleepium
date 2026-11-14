package dev.lull.optsupport;

import net.minecraft.client.Minecraft;

/**
 * -Dlab.opt.GlInbox (1.3.0, the GL role's inbox): work that must run on the GL thread but need not run inside the client
 * tick is taken out of it and run right after the frame is shown. First: the texture animations
 * (TextureManager.tick - frame copies and the interpolated frames' uploads, 2.5-3.5 ms a tick on the N100's horde scene,
 * the largest part of its 7 ms client tick). In the tick they made the frame holding the tick twice the others; after
 * the swap the CPU does them while the GPU still draws the frames queued before - in a GPU-bound scene that time is
 * hidden. Each tick's animation step still runs once, in order (two ticks before a frame: two steps); a step shows one
 * frame later than in vanilla.
 */
public final class GlInbox {
    private GlInbox() {}

    public static final boolean ON = Boolean.getBoolean("lab.opt.GlInbox");
    private static int textureTicks;
    public static long deferred, run, frames;

    /** Minecraft.tick's textureManager.tick(): owed to the next frame's end */
    public static void textureTick() {
        textureTicks++;
        deferred++;
    }

    /** runTick returned (the frame is shown): the GL work owed */
    public static void frameEnd(Minecraft mc) {
        int n = textureTicks;
        if (n == 0) return;
        textureTicks = 0;
        frames++;
        for (int i = 0; i < n; i++) {
            mc.getTextureManager().tick();
            run++;
        }
    }

    public static String report() {
        return "gl inbox: texture ticks deferred " + deferred + ", run " + run + " after " + frames + " frames";
    }
}
