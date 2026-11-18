package dev.lull.optsupport;

import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * -Dlab.enclosedSkip (11-18): a block whose six neighbours are all solid full cubes draws no face - each of its faces
 * is culled (Block.shouldRenderFace: the neighbour occludes and the block's face shape lies within the neighbour's full
 * face) - yet vanilla's tesselateWithAO still reseeds, asks the model for each side's quads and tests each side. Most
 * solid blocks of the ground are such; their meshing is skipped here at once.
 *
 * <p>Exact under these conditions, decided once a state (and again if its model object changes): the block is a solid
 * full cube with a position-independent shape (no dynamic shape: its face shapes are full, never empty), its model is
 * vanilla's SimpleBakedModel or a WeightedBakedModel of them (getQuads has no effect but drawing from the random, which
 * vanilla reseeds before every use) with no unculled quads; and per call: checkSides (chunk meshing), every neighbour a
 * solid full cube with a position-independent shape. Then vanilla renders nothing and changes nothing observable.
 */
public final class Enclosed {
    private Enclosed() {}

    public static final boolean ON = Boolean.getBoolean("lab.enclosedSkip");
    private static final Direction[] DIRS = Direction.values();
    public static long skipped, asked;

    private record Verdict(BakedModel model, boolean plain) {}

    private static volatile Verdict[] table = new Verdict[0];

    private static boolean plain(BlockState state, BakedModel model) {
        int id = AllocTrim.id(state);
        Verdict[] t = table;
        if (id < t.length) {
            Verdict v = t[id];
            if (v != null && v.model == model) return v.plain;
        }
        boolean p = classify(state, model);
        synchronized (Enclosed.class) {
            t = table;
            if (id >= t.length) t = table = java.util.Arrays.copyOf(t, Math.max(id + 1, Math.max(1024, t.length * 2)));
            t[id] = new Verdict(model, p);
        }
        return p;
    }

    private static boolean classify(BlockState state, BakedModel model) {
        if (state.getBlock().hasDynamicShape() || !state.canOcclude()) return false;
        if (!state.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return false;
        if (model.getClass() == SimpleBakedModel.class) return noUnculled(state, model);
        if (model.getClass() == WeightedBakedModel.class) {
            for (var w : ((dev.lull.opt.WeightedBakedModelAccessor) model).sleep$list()) {
                BakedModel m = w.data();
                if (m.getClass() != SimpleBakedModel.class || !noUnculled(state, m)) return false;
            }
            return true;
        }
        return false;
    }

    private static boolean noUnculled(BlockState state, BakedModel m) {
        List<BakedQuad> q = m.getQuads(state, null, RandomSource.create(0));
        return q.isEmpty();
    }

    /** tesselateWithAO (checkSides): true when the block draws nothing - skip it */
    public static boolean skip(BlockGetter level, BakedModel model, BlockState state, BlockPos pos, BlockPos.MutableBlockPos m) {
        asked++;
        if (!plain(state, model)) return false;
        for (Direction d : DIRS) {
            m.setWithOffset(pos, d);
            BlockState n = level.getBlockState(m);
            if (n.getBlock().hasDynamicShape() || !n.isSolidRender(level, m)) return false;
        }
        skipped++;
        return true;
    }

    private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /** a position of the thread for the neighbour reads (our pool's threads: the PosRing slot's own) */
    public static BlockPos.MutableBlockPos scratch() {
        Object[] s = ThreadSlots.mine();
        if (s == null) return SCRATCH.get();
        Object m = s[ThreadSlots.ENCLOSED];
        if (m == null) s[ThreadSlots.ENCLOSED] = m = new BlockPos.MutableBlockPos();
        return (BlockPos.MutableBlockPos) m;
    }

    public static String report() {
        return "enclosed: skipped " + skipped + " of " + asked + " AO blocks; region boxes filled " + RegionStates.filled + ", not " + RegionStates.notFilled;
    }
}
