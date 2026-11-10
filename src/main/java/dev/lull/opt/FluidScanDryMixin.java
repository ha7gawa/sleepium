package dev.lull.opt;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity.updateFluidHeightAndDoFluidPushing (every entity, every tick): getFluidState at every block of the box. When
 * every chunk section the box meets holds no fluid at all (its palette: CollisionMask.Holder.sleep$fluidFree, kept per
 * modification count) each answer is the empty fluid state - the same object, without the chunk and section lookups.
 */
@Mixin(Entity.class)
public abstract class FluidScanDryMixin {
    @Shadow public abstract AABB getBoundingBox();
    @Shadow public abstract Level level();

    @Unique private boolean sleep$dry;

    @Inject(method = "updateFluidHeightAndDoFluidPushing(Ljava/util/function/Predicate;)V", at = @At("HEAD"), remap = false)
    private void sleep$dryHead(CallbackInfo ci) {
        sleep$dry = sleep$allDry(level(), getBoundingBox().deflate(0.001));
    }

    @Redirect(method = "updateFluidHeightAndDoFluidPushing(Ljava/util/function/Predicate;)V", remap = false,
            at = @At(value = "INVOKE", remap = true, target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState sleep$fluid(Level level, BlockPos pos) {
        return sleep$dry ? Fluids.EMPTY.defaultFluidState() : level.getFluidState(pos);
    }

    /** the box's blocks (the scan's bounds: floor..ceil, exclusive) lie in loaded sections with no fluid in their palettes */
    @Unique
    private static boolean sleep$allDry(Level level, AABB bb) {
        int x0 = Mth.floor(bb.minX), x1 = Mth.ceil(bb.maxX) - 1, y0 = Mth.floor(bb.minY), y1 = Mth.ceil(bb.maxY) - 1;
        int z0 = Mth.floor(bb.minZ), z1 = Mth.ceil(bb.maxZ) - 1;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c == null) return false;
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int idx = c.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= c.getSections().length) continue;   // outside the build height: empty, as getFluidState answers
                    LevelChunkSection s = c.getSection(idx);
                    if (!(s instanceof dev.lull.optsupport.CollisionMask.Holder h) || !h.sleep$fluidFree()) return false;
                }
            }
        return true;
    }
}
