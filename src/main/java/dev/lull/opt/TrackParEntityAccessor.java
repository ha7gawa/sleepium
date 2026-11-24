package dev.lull.opt;

import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** part of TrackPar (lab): ChunkMap.TrackedEntity (package-private) as the tracker's loop uses it. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackParEntityAccessor {
    @Accessor("lastSectionPos")
    SectionPos sleep$lastSection();

    @Accessor("lastSectionPos")
    void sleep$setLastSection(SectionPos p);

    @Accessor("entity")
    Entity sleep$entity();

    @Accessor("serverEntity")
    ServerEntity sleep$serverEntity();

    @Accessor("seenBy")
    java.util.Set<net.minecraft.server.network.ServerPlayerConnection> sleep$seenBy();

    @Invoker("updatePlayers")
    void sleep$updatePlayers(List<ServerPlayer> players);
}
