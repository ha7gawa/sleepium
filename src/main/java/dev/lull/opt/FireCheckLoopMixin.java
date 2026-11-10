package dev.lull.opt;

import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Entity.move's fire check (every move of every entity): getBlockStatesIfLoaded(box).noneMatch(fire or lava) built a
 * stream of positions and states each time. The same blocks in the same order, the same loaded check, the same
 * predicate (vanilla's lambda, or whatever another mod made of it) - in a loop: the box is kept, no stream is made.
 */
@Mixin(Entity.class)
public abstract class FireCheckLoopMixin {
    @Unique private AABB sleep$fireBox;
    @Unique private Level sleep$fireLevel;

    @Redirect(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockStatesIfLoaded(Lnet/minecraft/world/phys/AABB;)Ljava/util/stream/Stream;"))
    private Stream<BlockState> sleep$states(Level level, AABB box) {
        sleep$fireBox = box;
        sleep$fireLevel = level;
        return null;
    }

    @Redirect(method = "move", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;noneMatch(Ljava/util/function/Predicate;)Z"))
    private boolean sleep$noneMatch(Stream<BlockState> unused, Predicate<? super BlockState> test) {
        AABB box = sleep$fireBox;
        Level level = sleep$fireLevel;
        sleep$fireBox = null;
        sleep$fireLevel = null;
        int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX), y0 = Mth.floor(box.minY), y1 = Mth.floor(box.maxY), z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ);
        if (!level.hasChunksAt(x0, y0, z0, x1, y1, z1)) return true;   // LevelReader.getBlockStatesIfLoaded: an empty stream
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int z = z0; z <= z1; z++)   // BlockPos.betweenClosed's order: x fastest, then y, then z
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++)
                    if (test.test(level.getBlockState(p.set(x, y, z)))) return false;
        return true;
    }
}
