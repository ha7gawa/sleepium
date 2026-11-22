package dev.lull.optsupport;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;

/**
 * ChunkMap.move visits only the tracked entities whose check can change something ({@code -Dlab.opt.TrackIndex=true},
 * with TrackFast; opt/TrackIndexMixin). Vanilla's move(player) calls updatePlayer(player) on EVERY tracked entity for
 * every movement packet. With TrackFast a pair farther than the player's view distance x 16 always gets "not seen";
 * when the entity is not in the player's seenBy either, that call changes nothing. So the loop needs only
 * <ul>
 *   <li>the entities within view distance x 16 of the player (a grid of 64-block cells: the square around the player);</li>
 *   <li>the entities that see the player now (a reverse set per player, kept where seenBy changes: TrackFast's
 *       updatePlayer, TrackedEntity.removePlayer);</li>
 *   <li>every player's own tracked entity (players move between ticks without the grid knowing: always visited; the
 *       moving player's own one runs vanilla's updatePlayers over all players as before).</li>
 * </ul>
 * The visit order is the entity map's own order (the map is walked by key, the candidates picked out), so the
 * pairing packets go out in vanilla's order. The grid holds non-player entities' positions at its build; any position
 * change of such an entity (Entity.setPosRaw), any entity added to or removed from a tracker marks it stale, and the
 * next move rebuilds it (once a tick when mobs move, then it serves every movement packet until the next tick).
 * {@code -Dlab.opt.TrackIndexVerify=true}: every entity left out is checked to be a no-op (vanilla's decision "not
 * seen" and not in seenBy), mismatches counted (expected 0).
 */
public final class TrackIndex {
    public static final boolean ON = Boolean.getBoolean("lab.opt.TrackIndex");
    public static final boolean VERIFY = Boolean.getBoolean("lab.opt.TrackIndexVerify");
    /** /sleep bots ab trackindex on|off */
    public static volatile boolean active = true;
    /** a tracked non-player entity moved / an entity entered or left a tracker since the grid was built (any thread) */
    public static volatile boolean stale = true;
    private static final int CELL_SHIFT = 6;   // 64 blocks

    /** TrackedEntity's parts TrackIndex needs (TrackFastMixin implements it) */
    public interface Tracked {
        Entity sleep$entity();

        Set<ServerPlayerConnection> sleep$seenBy();
    }

    /** marks an entity as one whose position the grid holds (EntityMixin field) */
    public interface Indexed {
        void sleep$setIndexed(boolean on);
    }

    private static Object gridOwner;   // the entity map the grid was built from (one ChunkMap per level)
    private static final Long2ObjectOpenHashMap<IntArrayList> GRID = new Long2ObjectOpenHashMap<>();
    private static final IntOpenHashSet PLAYER_IDS = new IntOpenHashSet();
    private static final IdentityHashMap<ServerPlayerConnection, IntOpenHashSet> SEEN = new IdentityHashMap<>();
    private static final IntOpenHashSet CAND = new IntOpenHashSet();
    private static boolean busy;

    public static long moves, visited, skipped, rebuilds, checked, mismatches;

    private TrackIndex() {}

    public static void register() {
        dev.lull.bots.BotLoad.AB.put("trackindex", v -> active = v);
        dev.lull.bots.BotLoad.WINDOWS.add(new dev.lull.bots.BotLoad.Window() {
            public void start() {
                moves = visited = skipped = rebuilds = 0;
            }

            public String end(long ticks) {
                return String.format(Locale.ROOT, "trackindex: %d moves, %.0f entities visited and %.0f left out a move, %d grid builds%s", moves,
                        moves == 0 ? 0.0 : visited / (double) moves, moves == 0 ? 0.0 : skipped / (double) moves, rebuilds,
                        VERIFY ? ", verified " + checked + " left out, mismatches " + mismatches : "");
            }
        });
    }

    // ---------------------------------------------------------------- the reverse sets (server thread)

    public static void seenAdd(ServerPlayerConnection c, int entityId) {
        SEEN.computeIfAbsent(c, k -> new IntOpenHashSet()).add(entityId);
    }

    public static void seenRemove(ServerPlayerConnection c, int entityId) {
        IntOpenHashSet s = SEEN.get(c);
        if (s != null) s.remove(entityId);
    }

    /** a tracked entity leaves its tracker: its id out of every set it is in; a player leaves: its set goes */
    public static void removed(Object tracked, Entity entity) {
        stale = true;
        if (tracked instanceof Tracked t) for (ServerPlayerConnection c : t.sleep$seenBy()) seenRemove(c, entity.getId());
        if (entity instanceof ServerPlayer p && p.connection != null) SEEN.remove(p.connection);
    }

    // ---------------------------------------------------------------- the candidates of one move

    private static long cell(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xFFFFFFFFL);
    }

    private static void rebuild(Int2ObjectMap<?> map) {
        GRID.clear();
        PLAYER_IDS.clear();
        for (Int2ObjectMap.Entry<?> e : Int2ObjectMaps_fast(map)) {
            Entity en = ((Tracked) e.getValue()).sleep$entity();
            if (en instanceof ServerPlayer) {
                PLAYER_IDS.add(e.getIntKey());
                continue;
            }
            ((Indexed) en).sleep$setIndexed(true);
            long k = cell((int) Math.floor(en.getX()) >> CELL_SHIFT, (int) Math.floor(en.getZ()) >> CELL_SHIFT);
            IntArrayList l = GRID.get(k);
            if (l == null) GRID.put(k, l = new IntArrayList(8));
            l.add(e.getIntKey());
        }
        gridOwner = map;
        stale = false;
        rebuilds++;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Iterable<Int2ObjectMap.Entry<?>> Int2ObjectMaps_fast(Int2ObjectMap<?> map) {
        return (Iterable) it.unimi.dsi.fastutil.ints.Int2ObjectMaps.fastIterable((Int2ObjectMap) map);
    }

    /**
     * ChunkMap.move's loop collection: the tracked entities to visit for this movement packet, in the map's order. With
     * the index off, or re-entered, the map's own values.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static it.unimi.dsi.fastutil.objects.ObjectCollection candidates(Int2ObjectMap map, ServerPlayer player, int viewDistance) {
        if (!active || !TrackFast.active || busy) return map.values();
        if (stale || gridOwner != map) rebuild(map);
        busy = true;
        try {
            CAND.clear();
            CAND.addAll(PLAYER_IDS);
            IntOpenHashSet seen = SEEN.get(player.connection);
            if (seen != null) CAND.addAll(seen);
            double r = viewDistance * 16;
            int x0 = (int) Math.floor(player.getX() - r) >> CELL_SHIFT, x1 = (int) Math.floor(player.getX() + r) >> CELL_SHIFT;
            int z0 = (int) Math.floor(player.getZ() - r) >> CELL_SHIFT, z1 = (int) Math.floor(player.getZ() + r) >> CELL_SHIFT;
            for (int cx = x0; cx <= x1; cx++)
                for (int cz = z0; cz <= z1; cz++) {
                    IntArrayList l = GRID.get(cell(cx, cz));
                    if (l != null) CAND.addAll(l);
                }
            ObjectArrayList<Object> out = new ObjectArrayList<>(Math.max(4, Math.min(CAND.size(), map.size())));
            int n = 0;
            for (Int2ObjectMap.Entry<?> e : Int2ObjectMaps_fast(map)) {
                if (CAND.contains(e.getIntKey())) out.add(e.getValue());
                else {
                    n++;
                    if (VERIFY) verifyLeftOut(e.getValue(), player, viewDistance);
                }
            }
            moves++;
            visited += out.size();
            skipped += n;
            return out;
        } finally {
            busy = false;
        }
    }

    /** an entity left out: vanilla's check would decide "not seen" and the player is not in its seenBy */
    private static void verifyLeftOut(Object tracked, ServerPlayer player, int viewDistance) {
        Tracked t = (Tracked) tracked;
        Entity en = t.sleep$entity();
        checked++;
        if (en == player) {
            mismatches++;
            return;
        }
        double dx = player.getX() - en.getX(), dz = player.getZ() - en.getZ();
        double cap = viewDistance * 16;
        if (dx * dx + dz * dz <= cap * cap || t.sleep$seenBy().contains(player.connection)) mismatches++;
    }
}
