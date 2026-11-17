package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FrameThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * FrameThread with Embeddium / Rubidium (1.20.1) and Sodium (1.21.1): their ChunkTracker takes the client's chunk
 * events from the main thread (packets: a chunk loaded, dropped, the view centre and distance) and hands them to the
 * frame's terrain setup (processChunkEvents) - plain sets, one thread assumed. With the frame thread both ran at once
 * (Biohazard 11-17: an NPE in processChunkEvents). On the main thread the events go to the frame thread's queue, run
 * at its next frame's start in their order - as the vanilla renderer's calls do (FrameThreadCallsMixin).
 */
@Pseudo
@Mixin(targets = {"me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker", "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker"}, remap = false)
public abstract class FrameThreadSodiumChunksMixin {
    @WrapMethod(method = "onChunkStatusAdded", require = 0)
    private void sleep$added(int x, int z, int flags, Operation<Void> original) {
        if (!FrameThread.deferCall(original, x, z, flags)) original.call(x, z, flags);
    }

    @WrapMethod(method = "onChunkStatusRemoved", require = 0)
    private void sleep$removed(int x, int z, int flags, Operation<Void> original) {
        if (!FrameThread.deferCall(original, x, z, flags)) original.call(x, z, flags);
    }

    @WrapMethod(method = "updateMapCenter", require = 0)
    private void sleep$center(int x, int z, Operation<Void> original) {
        if (!FrameThread.deferCall(original, x, z)) original.call(x, z);
    }

    @WrapMethod(method = "updateLoadDistance", require = 0)
    private void sleep$distance(int d, Operation<Void> original) {
        if (!FrameThread.deferCall(original, d)) original.call(d);
    }
}
