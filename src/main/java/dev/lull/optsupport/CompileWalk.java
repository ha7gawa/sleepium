package dev.lull.optsupport;

import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * -Dlab.compileWalk (11-18, with lab.regionStates.fill): SectionCompiler.compile's walk over the section's 4096
 * positions (BlockPos.betweenClosed) yields only the positions whose block can do something in the loop's body. An
 * inert state - not a solid render, no block entity, no fluid, no model render shape, its shape position-independent -
 * makes vanilla's body do nothing (no opaque mark, no block entity, no liquid, no model); its position is skipped.
 * The others come in vanilla's order (x fastest, then y, then z) through one mutable cursor, as vanilla's iterator
 * gives them. The states are the box's (RegionStates fill: the same as the body's own read). Not final: flippable.
 */
public final class CompileWalk implements Iterable<BlockPos>, Iterator<BlockPos> {
    public static boolean ON = Boolean.getBoolean("lab.compileWalk");
    public static long walks, skipped, yielded;

    /** state id -> 1 inert, 2 not (0 not decided yet); racy writes of the same value */
    private static volatile byte[] inert = new byte[0];

    private static boolean inert(BlockState s) {
        int id = AllocTrim.id(s);
        byte[] t = inert;
        if (id < t.length && t[id] != 0) return t[id] == 1;
        boolean v = !s.getBlock().hasDynamicShape() && !s.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) && !s.hasBlockEntity()
                && s.getFluidState().isEmpty() && s.getRenderShape() != RenderShape.MODEL;
        synchronized (CompileWalk.class) {
            t = inert;
            if (id >= t.length) t = inert = java.util.Arrays.copyOf(t, Math.max(id + 1, Math.max(1024, t.length * 2)));
            t[id] = (byte) (v ? 1 : 2);
        }
        return v;
    }

    private RegionStates rs;
    private int x0, y0, z0, i, next;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** compile's walk from a to a + 15: ours when this thread's box holds the section, else vanilla's */
    public static Iterable<BlockPos> walk(BlockPos a, BlockPos b) {
        if (ON) {
            RegionStates rs = RegionStates.currentFilled();
            if (rs != null && a.getX() == rs.minX() && a.getY() == rs.minY() && a.getZ() == rs.minZ()
                    && b.getX() == a.getX() + 15 && b.getY() == a.getY() + 15 && b.getZ() == a.getZ() + 15) {
                Object[] s = ThreadSlots.mine();
                CompileWalk w = (CompileWalk) s[ThreadSlots.WALK];
                if (w == null) s[ThreadSlots.WALK] = w = new CompileWalk();
                w.rs = rs;
                w.x0 = a.getX();
                w.y0 = a.getY();
                w.z0 = a.getZ();
                w.i = 0;
                w.next = -1;
                walks++;
                return w;
            }
        }
        return BlockPos.betweenClosed(a, b);
    }

    @Override
    public Iterator<BlockPos> iterator() {
        return this;
    }

    @Override
    public boolean hasNext() {
        if (next >= 0) return true;
        while (i < 4096) {
            int k = i++;
            BlockState st = rs.inner(k & 15, (k >> 4) & 15, k >> 8);   // x, y, z of index k (x fastest, then y, then z)
            if (st != null && inert(st)) {
                skipped++;
                continue;
            }
            next = k;
            return true;
        }
        return false;
    }

    @Override
    public BlockPos next() {
        if (!hasNext()) throw new NoSuchElementException();
        int k = next;
        next = -1;
        yielded++;
        // index k: x = k & 15 fastest, then y, then z - vanilla's betweenClosed order (index % 16, / 16 % 16, / 256)
        return cursor.set(x0 + (k & 15), y0 + ((k >> 4) & 15), z0 + (k >> 8));
    }

    public static String report() {
        return "compile walk: " + walks + " walks, " + skipped + " inert positions skipped, " + yielded + " yielded";
    }
}
