package dev.lull.opt;

import dev.lull.optsupport.LazyInterp;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** NoiseLazyInterp (lab): NoiseChunk's step methods keep the fraction and a counter; the interpolators read lazily (see LazyInterp). */
@Mixin(NoiseChunk.class)
public abstract class NoiseLazyInterpMixin implements LazyInterp.Host {
    @Shadow int inCellX;
    @Shadow int inCellY;
    @Shadow int inCellZ;
    @Shadow private int cellStartBlockX;
    @Shadow private int cellStartBlockY;
    @Shadow private int cellStartBlockZ;
    @Shadow long interpolationCounter;

    @Unique private double sleep$fy, sleep$fx, sleep$fz;
    @Unique private long sleep$sy, sleep$sx, sleep$sz;

    /**
     * @author probe
     * @reason NoiseLazyInterp: the interpolators' y lerps happen when they are read
     */
    @Overwrite
    public void updateForY(int cellEndBlockY, double y) {
        this.inCellY = cellEndBlockY - this.cellStartBlockY;
        this.sleep$fy = y;
        this.sleep$sy++;
    }

    /**
     * @author probe
     * @reason NoiseLazyInterp: the interpolators' x lerps happen when they are read
     */
    @Overwrite
    public void updateForX(int cellEndBlockX, double x) {
        this.inCellX = cellEndBlockX - this.cellStartBlockX;
        this.sleep$fx = x;
        this.sleep$sx++;
    }

    /**
     * @author probe
     * @reason NoiseLazyInterp: the interpolators' z lerp happens when they are read
     */
    @Overwrite
    public void updateForZ(int cellEndBlockZ, double z) {
        this.inCellZ = cellEndBlockZ - this.cellStartBlockZ;
        this.interpolationCounter++;
        this.sleep$fz = z;
        this.sleep$sz++;
    }

    @Override
    public double sleep$fy() {
        return sleep$fy;
    }

    @Override
    public double sleep$fx() {
        return sleep$fx;
    }

    @Override
    public double sleep$fz() {
        return sleep$fz;
    }

    @Override
    public long sleep$sy() {
        return sleep$sy;
    }

    @Override
    public long sleep$sx() {
        return sleep$sx;
    }

    @Override
    public long sleep$sz() {
        return sleep$sz;
    }
}
