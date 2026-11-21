package dev.lull.opt;

import dev.lull.optsupport.FeatScratch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.feature.MonsterRoomFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * WorldgenFeatureAlloc (dungeons): MonsterRoomFeature.place first scans its whole box (up to 7 x 6 x 7) for solid
 * floor / ceiling and openings, making a BlockPos per block with {@code origin.offset(x, y, z)} - 14 tries per chunk.
 * In that scan (the first offset call) the position is only read: getBlockState(p).isSolid(), isEmptyBlock(p) and
 * isEmptyBlock(p.above()) (above() makes its own BlockPos from the coordinates). A per-thread MutableBlockPos set to
 * the same coordinates serves; it is set again before every use, and nothing in the scan places a feature. The
 * second loop (it writes blocks: setBlock may keep the position) keeps vanilla's offset.
 */
@Mixin(MonsterRoomFeature.class)
public abstract class WorldgenFeatureDungeonMixin {
    @Redirect(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;offset(III)Lnet/minecraft/core/BlockPos;", ordinal = 0))
    private BlockPos sleep$scanPos(BlockPos origin, int dx, int dy, int dz) {
        return FeatScratch.pos().set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
    }
}
