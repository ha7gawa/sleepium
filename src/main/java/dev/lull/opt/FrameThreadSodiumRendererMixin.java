package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * FrameThread with Embeddium / Sodium: no frame drawn while their world renderer is given a level or rebuilt
 * (setWorld, reload - its section manager is null in between) whoever calls them. Biohazard 11-17 crashed on leaving
 * the world in an extra frame (SodiumWorldRenderer.processChunkEvents: the section manager gone) with LevelRenderer's
 * setLevel and allChanged already held: another mod reaches these directly (Oculus reloads the world renderer).
 */
@Pseudo
@Mixin(targets = {"me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer", "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer"}, remap = false)
public abstract class FrameThreadSodiumRendererMixin {
    private static int sleep$logged;

    @WrapMethod(method = "setWorld", require = 0)
    private void sleep$setWorldHeld(ClientLevel level, Operation<Void> original) {
        sleep$note("setWorld(" + (level == null ? "null" : "a level") + ")");
        FrameThread.holdForMain();
        try {
            if (level == null) FrameThread.levelGone = true;
            original.call(level);
            if (level != null) FrameThread.levelGone = false;
        } finally {
            FrameThread.releaseForMain();
        }
    }

    private static volatile boolean sleep$reloadQueued;

    /**
     * A reload Sodium starts itself from setupTerrain runs on the frame thread there - and Embeddium's chunk builder
     * insists on the game thread (GlobalChunkBuildContext: "captured wrong thread"), so the reload broke off with no
     * section manager (Biohazard 11-17, then NPEs in processChunkEvents). It is handed to the main thread instead (once),
     * which runs it with the frame thread held; this frame goes on with the old renderer, still whole.
     */
    @WrapMethod(method = "reload", require = 0)
    private void sleep$reloadHeld(Operation<Void> original) {
        sleep$note("reload()");
        if (FrameThread.onFrameThread()) {
            if (sleep$reloadQueued) return;
            sleep$reloadQueued = true;
            net.minecraft.client.Minecraft.getInstance().tell(() -> {   // (tell: execute runs it at once on the frame thread, which Minecraft takes for its own)
                sleep$reloadQueued = false;
                FrameThread.holdForMain();
                try {
                    original.call();
                } finally {
                    FrameThread.releaseForMain();
                }
            });
            return;
        }
        FrameThread.holdForMain();
        try {
            original.call();
        } finally {
            FrameThread.releaseForMain();
        }
    }

    private static void sleep$note(String what) {
        if (!FrameThread.active || sleep$logged >= 6) return;
        sleep$logged++;
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder b = new StringBuilder();
        for (int i = 2; i < Math.min(st.length, 7); i++) b.append(" < ").append(st[i].getClassName().replaceAll(".*\\.", "")).append('.').append(st[i].getMethodName());
        System.out.println("[sleep/opt] frame thread: Sodium world renderer " + what + " on " + Thread.currentThread().getName() + b);
    }
}
