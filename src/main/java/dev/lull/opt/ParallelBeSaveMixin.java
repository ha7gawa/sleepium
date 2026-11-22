package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.ParallelBeSave;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ParallelBeSave (notes/88): ChunkSerializer.write's block-entity loop - the positions planned and the trusted tags made
 * on the pool before the loop, each loop step takes its tag (or makes it the vanilla way). Also the per-class save
 * timing of -Dlab.save.stats.
 */
@Mixin(ChunkSerializer.class)
public abstract class ParallelBeSaveMixin {
    @WrapOperation(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockEntitiesPos()Ljava/util/Set;"))
    private static Set<BlockPos> sleep$plan(ChunkAccess chunk, Operation<Set<BlockPos>> original) {
        return ParallelBeSave.prepare(chunk, original.call(chunk));
    }

    @WrapOperation(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockEntityNbtForSaving(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"))
    private static CompoundTag sleep$tag(ChunkAccess chunk, BlockPos pos, HolderLookup.Provider registries, Operation<CompoundTag> original) {
        return ParallelBeSave.nbt(chunk, pos, () -> original.call(chunk, pos, registries));
    }
}
