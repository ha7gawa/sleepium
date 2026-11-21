package dev.lull.opt;

import dev.lull.optsupport.RegionBlockVerify;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * RegionBlock (-Dlab.opt.WorldgenRegionBlockMixin): WorldGenRegion.getBlockState of the FEATURES step remembers the
 * last section it read from. Vanilla: getChunk(x >> 4, z >> 4).getBlockState(pos); for a ProtoChunk that is "outside
 * the build height -> VOID_AIR, else section (getSectionIndex(y)): hasOnlyAir ? AIR : section.getBlockState(x & 15,
 * y & 15, z & 15)". Features read block after block in the same section (trees, leaves' distance walk, shape updates,
 * geodes, patches); each read paid the region's chunk lookup, the build height test and the section index (the
 * dimension type's minY through three objects, twice).
 *
 * <p>Why the remembered section is vanilla's: in the FEATURES step the region's chunk for (x, z) does not change while
 * the step runs (WorldgenShareRegionMixin: no chunk within the region's radius can reach FULL meanwhile, and nothing
 * else replaces a holder's completed future), a ProtoChunk's sections array is final and its elements are never
 * replaced, and min build height and height are multiples of 16 (DimensionType's codec checks it), so a position in
 * the same section (x >> 4, y >> 4, z >> 4) as a position inside the build height is inside it too and has the same
 * section index. The answer is then computed from that section exactly as ProtoChunk does it (hasOnlyAir read each
 * time: sections change). Only filled from a chunk whose class is ProtoChunk (not ImposterProtoChunk / LevelChunk /
 * a mod's subclass) and a y inside its build height; anything else is answered by vanilla's lines. Other steps go the
 * vanilla way. A region is used by the one thread that runs its step (as the memo of WorldgenShareRegionMixin).
 *
 * <p>-Dlab.featcpu.verify=true: every remembered answer is compared with vanilla's lookup.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldgenRegionBlockMixin {
    @Shadow @Final private ChunkStep generatingStep;

    @Shadow
    public abstract ChunkAccess getChunk(int chunkX, int chunkZ);

    /** 0 unknown, 1 FEATURES (memo on), 2 other step */
    @Unique private byte sleep$blockState;
    @Unique private long sleep$lastKey;
    @Unique private LevelChunkSection sleep$lastSection;

    /**
     * @author probe
     * @reason the last section remembered in the FEATURES step (see class doc); otherwise vanilla
     */
    @Overwrite
    public BlockState getBlockState(BlockPos pos) {
        byte st = sleep$blockState;
        if (st == 0) sleep$blockState = st = (byte) (this.generatingStep.targetStatus() == ChunkStatus.FEATURES ? 1 : 2);
        if (st == 1) {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            LevelChunkSection s = sleep$lastSection;
            if (s != null && key == sleep$lastKey) {
                BlockState r = s.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : s.getBlockState(x & 15, y & 15, z & 15);
                if (RegionBlockVerify.ON) RegionBlockVerify.check(r, this.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)).getBlockState(pos), pos);
                return r;
            }
            ChunkAccess c = this.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
            if (c.getClass() == ProtoChunk.class && !c.isOutsideBuildHeight(y)) {
                LevelChunkSection sec = c.getSection(c.getSectionIndex(y));
                sleep$lastSection = sec;
                sleep$lastKey = key;
                return sec.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : sec.getBlockState(x & 15, y & 15, z & 15);
            }
            return c.getBlockState(pos);
        }
        return this.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())).getBlockState(pos);
    }
}
