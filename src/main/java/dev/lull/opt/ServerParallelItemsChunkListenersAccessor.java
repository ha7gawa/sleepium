package dev.lull.opt;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * part of SpecTicks (lab): the chunk's game event listener registries read as they are - getListenerRegistry creates a
 * missing one (computeIfAbsent), a write to the chunk's map that run ticks on several threads must not make
 */
@Mixin(LevelChunk.class)
public interface ServerParallelItemsChunkListenersAccessor {
    @Accessor("gameEventListenerRegistrySections")
    Int2ObjectMap<GameEventListenerRegistry> sleep$listenerSections();
}
