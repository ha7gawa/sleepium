package dev.lull.opt;

import dev.lull.optsupport.GenGraph;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** GenGraph: a task's holder cache, center and claim release. */
@Mixin(ChunkGenerationTask.class)
public interface GenGraphTaskAccessor extends GenGraph.Task {
    @Accessor("cache")
    @Override
    StaticCache2D<GenerationChunkHolder> sleep$cache();

    @Accessor("pos")
    @Override
    ChunkPos sleep$pos();

    @Invoker("releaseClaim")
    @Override
    void sleep$releaseClaim();
}
