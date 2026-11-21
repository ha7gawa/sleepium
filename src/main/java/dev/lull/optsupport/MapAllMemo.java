package dev.lull.optsupport;

import java.util.IdentityHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * WorldgenShare (mapAll): NoiseChunk builds its density tree with router.mapAll(this::wrap). Named functions
 * (continents, erosion, ridges, …) are referenced through holders from many spline points, and HolderHolder.mapAll /
 * Spline.Coordinate.mapAll re-map the whole referenced tree every time (~20k wrap calls per NoiseChunk, each hashing a
 * record subtree). For one NoiseChunk visitor, mapping the same function object always gives an equal result (wrap
 * canonicalises by equality, nodes are immutable), so the first result is reused for the rest of that traversal.
 * Visitors that are not NoiseChunk's own are never memoised.
 *
 * <p>lab.opt.WorldgenMapMemoMixin (11-21) memoises every child a vanilla node maps (Ap2, RangeChoice, Clamp, Mapped,
 * MulOrAdd, BlendDensity, ShiftedNoise, WeirdScaledSampler, the router's fields), not only holders and coordinates:
 * NoiseOnce's router and the second traversal (the mapped final density mapped again into the cell cache) share
 * subtrees by instance, without holders, and each occurrence was mapped again - the overworld's splines (offset, factor,
 * jaggedness: hundreds of Multipoints each, every one rebuilt with a stream, a list and a new coordinate) several
 * times per NoiseChunk. Same argument: a node instance mapped by one NoiseChunk visitor gives the canonical instance
 * from wrap (identical to what vanilla's repeated mapping returns) or, for the three records whose mapAll does not end
 * in visitor.apply (Clamp, Mapped, MulOrAdd), an equal record of stateless parts. The first occurrence is mapped
 * exactly as vanilla maps it, so NoiseChunk's wrappers (interpolators, caches) are created in vanilla's order; later
 * occurrences only skip work whose every wrap lookup would have hit. {@link #end} drops the session after a
 * NoiseChunk's traversals: the memo must not keep a finished chunk's wrappers (and so the NoiseChunk) alive.
 */
public final class MapAllMemo {
    private static final class Session {
        Object visitor;
        final IdentityHashMap<DensityFunction, DensityFunction> map = new IdentityHashMap<>(512);
    }

    private static final ThreadLocal<Session> SESSION = ThreadLocal.withInitial(Session::new);

    private MapAllMemo() {}

    public static DensityFunction map(DensityFunction f, DensityFunction.Visitor visitor) {
        if (visitor.getClass().getNestHost() != NoiseChunk.class) return f.mapAll(visitor);
        Session s = SESSION.get();
        if (s.visitor != visitor) {
            s.visitor = visitor;
            s.map.clear();
        }
        DensityFunction r = s.map.get(f);
        if (r == null) {
            r = f.mapAll(visitor);
            if (s.visitor != visitor) {   // a traversal of another visitor ran inside this one (not seen; kept correct)
                s.visitor = visitor;
                s.map.clear();
            }
            s.map.put(f, r);
        }
        return r;
    }

    /** the end of a NoiseChunk's traversals on this thread: nothing of it stays reachable from here */
    public static void end() {
        Session s = SESSION.get();
        if (s.visitor != null) {
            s.visitor = null;
            s.map.clear();
        }
    }
}
