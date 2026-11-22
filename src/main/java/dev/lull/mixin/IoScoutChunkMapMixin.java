package dev.lull.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.IoScout;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;

/** Measurement only (-Dsleep.ioScout.pos=true): who calls ChunkMap.save, with the chunk's class, status and the result. */
@Mixin(ChunkMap.class)
public abstract class IoScoutChunkMapMixin {
    @WrapMethod(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z")
    private boolean sleep$save(ChunkAccess chunk, Operation<Boolean> original) {
        if (!IoScout.POS) return original.call(chunk);
        boolean unsaved = chunk.isUnsaved();
        boolean r = original.call(chunk);
        IoScout.save(IoScout.caller("save"), chunk.getPersistedStatus().getName() + (unsaved ? "" : " (saved)"), chunk.getClass().getSimpleName(), r);
        return r;
    }
}
