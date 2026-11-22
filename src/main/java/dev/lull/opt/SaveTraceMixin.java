package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.SaveStats;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Measurement only (-Dlab.save.stats, notes/88): where ChunkMap's chunk_unload section spends the main thread. */
@Mixin(ChunkMap.class)
public abstract class SaveTraceMixin {
    @WrapMethod(method = "processUnloads")
    private void sleep$unloads(BooleanSupplier hasMoreTime, Operation<Void> original) {
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            original.call(hasMoreTime);
        } finally {
            SaveStats.add(SaveStats.UNLOADS, b, t);
        }
    }

    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void sleep$task(Runnable r, Operation<Void> original) {
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            original.call(r);
        } finally {
            SaveStats.add(SaveStats.UNLOAD_TASK, b, t);
        }
    }

    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;scheduleUnload(JLnet/minecraft/server/level/ChunkHolder;)V"))
    private void sleep$drop(ChunkMap self, long pos, ChunkHolder holder, Operation<Void> original) {
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            original.call(self, pos, holder);
        } finally {
            SaveStats.add(SaveStats.DROP, b, t);
        }
    }

    @WrapMethod(method = "saveChunkIfNeeded")
    private boolean sleep$eager(ChunkHolder holder, Operation<Boolean> original) {
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            return original.call(holder);
        } finally {
            SaveStats.add(SaveStats.EAGER, b, t);
        }
    }

    @WrapMethod(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z")
    private boolean sleep$save(ChunkAccess chunk, Operation<Boolean> original) {
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            return original.call(chunk);
        } finally {
            SaveStats.add(SaveStats.SAVE, b, t);
        }
    }

    @WrapOperation(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"))
    private Event sleep$event(IEventBus bus, Event e, Operation<Event> original) {
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            return original.call(bus, e);
        } finally {
            SaveStats.add(SaveStats.SAVE_EVENT, b, t);
        }
    }

    @WrapOperation(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;flush(Lnet/minecraft/world/level/ChunkPos;)V"))
    private void sleep$poi(PoiManager poi, ChunkPos pos, Operation<Void> original) {
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            original.call(poi, pos);
        } finally {
            SaveStats.add(SaveStats.POI_FLUSH, b, t);
        }
    }
}
