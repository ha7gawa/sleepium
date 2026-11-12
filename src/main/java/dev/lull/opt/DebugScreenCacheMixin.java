package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * The F3 screen's lines gathered at most every sleep.f3.refreshMs (100 ms; its FPS line changes once a second
 * anyway) instead of every frame. Gathering them was the screen's cost: the chunk statistics walk every section in
 * the frustum, and the world generator's noise and biome lines evaluate the noise router at the player (building an
 * OverworldBiomeBuilder each call) - 20 % of the render thread with F3 open (desktop 1101 -> 638 fps; on a phone the
 * game drops to a fraction). Between refreshes the same lines are shown; the caller gets its own copy each frame
 * (it appends to it). Mods' lines (Forge's debug text event) are untouched.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenCacheMixin {
    @Unique private static final long sleep$REFRESH = Math.max(0L, Long.getLong("sleep.f3.refreshMs", 100L)) * 1_000_000L;
    @Unique private List<String> sleep$game, sleep$system;
    @Unique private long sleep$gameAt, sleep$systemAt;

    @WrapMethod(method = "getGameInformation")
    private List<String> sleep$game(Operation<List<String>> original) {
        long now = System.nanoTime();
        if (sleep$game == null || now - sleep$gameAt >= sleep$REFRESH) {
            sleep$game = new ArrayList<>(original.call());
            sleep$gameAt = now;
        }
        return new ArrayList<>(sleep$game);
    }

    @WrapMethod(method = "getSystemInformation")
    private List<String> sleep$system(Operation<List<String>> original) {
        long now = System.nanoTime();
        if (sleep$system == null || now - sleep$systemAt >= sleep$REFRESH) {
            sleep$system = new ArrayList<>(original.call());
            sleep$systemAt = now;
        }
        return new ArrayList<>(sleep$system);
    }
}
