package dev.lull.optsupport;

import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;

/**
 * WorldgenFeatureAlloc (placement): PlacedFeature.placeWithContext without streams.
 *
 * <p>Vanilla chains one flatMap per placement modifier over Stream.of(origin) and runs the feature in forEach. A
 * sequential flatMap takes one element, calls the mapper (getPositions) for it and pushes every element of the
 * returned stream downstream before it takes the next one - depth first. This is the same traversal as recursion over
 * the modifier index: modifier i's getPositions runs when an element reaches it, the feature places when an element
 * passes the last modifier, in the same order, and the result is whether any place returned true (all are run).
 *
 * <p>For the vanilla modifiers whose getPositions is a fixed small computation the positions are produced here, by
 * the same code in the same order (the same random draws, the same reads of the world, the same BlockPos objects -
 * EnvironmentScan's MutableBlockPos included), without the Stream objects (a Stream.of / IntStream.range pipeline,
 * a spliterator and a ReferencePipeline head per element per modifier, ~15 % of the workers' allocation):
 * <ul>
 *   <li>every PlacementFilter (its getPositions is final: shouldPlace ? Stream.of(pos) : Stream.of()) - biome,
 *       rarity, block predicate, surface water depth, surface relative threshold, and modded filters alike;</li>
 *   <li>CountPlacement, NoiseBasedCountPlacement, NoiseThresholdCountPlacement (exact classes: RepeatingPlacement's
 *       getPositions is IntStream.range(0, count(random, pos)).mapToObj(i -> pos) - count drawn once, when the element
 *       arrives, then pos count times);</li>
 *   <li>InSquarePlacement, HeightRangePlacement, RandomOffsetPlacement, HeightmapPlacement, EnvironmentScanPlacement
 *       (exact classes: their bodies copied below, in vanilla's statement order).</li>
 * </ul>
 * Any other modifier (or a subclass that may override getPositions) runs vanilla's getPositions and its stream is
 * consumed as flatMap does: sequential().forEach, then closed.
 */
public final class PlacementWalk {
    /** PlacementFilter.shouldPlace (protected) */
    public interface Filter { boolean sleep$shouldPlace(PlacementContext context, RandomSource random, BlockPos pos); }
    /** RepeatingPlacement.count (protected) */
    public interface Repeating { int sleep$count(RandomSource random, BlockPos pos); }
    public interface HeightRange { HeightProvider sleep$height(); }
    public interface RandomOffset { IntProvider sleep$xzSpread(); IntProvider sleep$ySpread(); }
    public interface Heightmap_ { Heightmap.Types sleep$heightmap(); }
    public interface EnvironmentScan {
        Direction sleep$directionOfSearch();
        BlockPredicate sleep$targetCondition();
        BlockPredicate sleep$allowedSearchCondition();
        int sleep$maxSteps();
    }

    private PlacementWalk() {}

    public static boolean walk(List<PlacementModifier> mods, ConfiguredFeature<?, ?> feature, PlacementContext ctx, RandomSource r, BlockPos pos) {
        return step(mods, 0, mods.size(), feature, ctx, r, pos);
    }

    private static boolean step(List<PlacementModifier> mods, int i, int n, ConfiguredFeature<?, ?> f, PlacementContext ctx, RandomSource r, BlockPos pos) {
        if (i == n) return f.place(ctx.getLevel(), ctx.generator(), r, pos);
        PlacementModifier m = mods.get(i);
        Class<?> c = m.getClass();
        int j = i + 1;
        boolean filter = m instanceof PlacementFilter;
        boolean count = !filter && (c == CountPlacement.class || c == NoiseBasedCountPlacement.class || c == NoiseThresholdCountPlacement.class);
        if (!filter && !count && c != InSquarePlacement.class && c != HeightRangePlacement.class && c != RandomOffsetPlacement.class
                && c != HeightmapPlacement.class && c != EnvironmentScanPlacement.class)
            return generic(m, mods, j, n, f, ctx, r, pos);
        PlacementVerify v = PlacementVerify.ON ? PlacementVerify.before(m, ctx, r, pos) : null;
        if (count) {
            // IntStream.range(0, count(random, pos)).mapToObj(i -> pos): the count drawn once, then pos count times
            int k = ((Repeating) m).sleep$count(r, pos);
            if (v != null) v.produced(r, pos, k);
            boolean placed = false;
            for (int e = 0; e < k; e++)
                if (step(mods, j, n, f, ctx, r, pos)) placed = true;
            return placed;
        }
        // the others give one position or none: nothing follows it in the stream, so the && below skips no call
        BlockPos out;
        if (filter) {
            out = ((Filter) m).sleep$shouldPlace(ctx, r, pos) ? pos : null;
        } else if (c == InSquarePlacement.class) {
            int x = r.nextInt(16) + pos.getX();
            int z = r.nextInt(16) + pos.getZ();
            out = new BlockPos(x, pos.getY(), z);
        } else if (c == HeightRangePlacement.class) {
            out = pos.atY(((HeightRange) m).sleep$height().sample(r, ctx));
        } else if (c == RandomOffsetPlacement.class) {
            RandomOffset o = (RandomOffset) m;
            int x = pos.getX() + o.sleep$xzSpread().sample(r);
            int y = pos.getY() + o.sleep$ySpread().sample(r);
            int z = pos.getZ() + o.sleep$xzSpread().sample(r);
            out = new BlockPos(x, y, z);
        } else if (c == HeightmapPlacement.class) {
            int x = pos.getX();
            int z = pos.getZ();
            int y = ctx.getHeight(((Heightmap_) m).sleep$heightmap(), x, z);
            out = y > ctx.getMinBuildHeight() ? new BlockPos(x, y, z) : null;
        } else {
            out = scan((EnvironmentScan) m, ctx.getLevel(), pos);
        }
        if (v != null) v.produced(r, out, 1);
        return out != null && step(mods, j, n, f, ctx, r, out);
    }

    /** EnvironmentScanPlacement.getPositions: its MutableBlockPos, or null for Stream.of() */
    private static BlockPos scan(EnvironmentScan s, WorldGenLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mp = pos.mutable();
        if (!s.sleep$allowedSearchCondition().test(level, mp)) return null;
        for (int e = 0; e < s.sleep$maxSteps(); e++) {
            if (s.sleep$targetCondition().test(level, mp)) return mp;
            mp.move(s.sleep$directionOfSearch());
            if (level.isOutsideBuildHeight(mp.getY())) return null;
            if (!s.sleep$allowedSearchCondition().test(level, mp)) break;
        }
        return s.sleep$targetCondition().test(level, mp) ? mp : null;
    }

    /** any other modifier: vanilla's stream, consumed as a sequential flatMap consumes it */
    private static boolean generic(PlacementModifier m, List<PlacementModifier> mods, int j, int n, ConfiguredFeature<?, ?> f, PlacementContext ctx, RandomSource r, BlockPos pos) {
        try (Stream<BlockPos> s = m.getPositions(ctx, r, pos)) {
            if (s == null) return false;
            boolean[] placed = new boolean[1];
            s.sequential().forEach(p -> {
                if (step(mods, j, n, f, ctx, r, p)) placed[0] = true;
            });
            return placed[0];
        }
    }
}
