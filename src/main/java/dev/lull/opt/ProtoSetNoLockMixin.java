package dev.lull.opt;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * -Dlab.opt.ProtoSetNoLockMixin (11-21, lab): a chunk being generated sets its blocks without vanilla's threading
 * detector. LevelChunkSection.setBlockState(x, y, z, state) takes the locked path: PalettedContainer.getAndSet acquires a
 * Semaphore and a ReentrantLock around every single block (ThreadingDetector - a debug guard that throws when two threads
 * write one container at once). In generation that was ~3 % of the workers' CPU (JFR 11-21: AQS signalNext / release
 * under PalettedContainer.acquire / release). The unlocked path writes the same block the same way (SectionModCountMixin
 * counts both); generation never writes one chunk from two threads at once (vanilla's steps, ParallelGen's 3 x 3
 * windows) - the guard would only have thrown.
 */
@Mixin(ProtoChunk.class)
public abstract class ProtoSetNoLockMixin {
    @Redirect(method = "setBlockState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState sleep$setUnlocked(LevelChunkSection section, int x, int y, int z, BlockState state) {
        return section.setBlockState(x, y, z, state, false);
    }
}
