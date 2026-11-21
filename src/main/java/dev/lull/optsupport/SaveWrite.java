package dev.lull.optsupport;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.SerializableTickContainer;

/**
 * -Dlab.opt.SaveWriteMixin: the main thread's part of ChunkSerializer.write without its repeated garbage.
 * <ul>
 *   <li>The biome palette codec: made once per biome registry (vanilla builds the whole RecordCodecBuilder codec for
 *   every chunk written; it is a function of the registry only).</li>
 *   <li>Scheduled ticks' ids: the block's / fluid's registry name as a string, made once per block or fluid (vanilla
 *   concatenates namespace, ':' and path for every tick: thousands of fluid ticks in a generated chunk).</li>
 *   <li>PostProcessing (packOffsets): each list read by index (vanilla iterates with boxed Shorts), the ShortTags of
 *   offsets 0..4095 shared (immutable; vanilla's own cache ends at 1024).</li>
 * </ul>
 * The same tags with the same values in the same order: the same bytes.
 */
public final class SaveWrite {
    private SaveWrite() {}

    /**
     * AsyncChunkSave's whole-chunk verification (-Dlab.asyncsave.verifyChunk) writes its reference chunks with this set:
     * then nothing here applies (vanilla's codec, ids and offsets), and the reference is vanilla's write throughout.
     */
    private static final ThreadLocal<Boolean> VANILLA = new ThreadLocal<>();

    public static boolean vanillaNow() {
        return AsyncChunkSave.VERIFY_CHUNK && VANILLA.get() != null;
    }

    public static void vanilla(boolean on) {
        if (on) VANILLA.set(Boolean.TRUE);
        else VANILLA.remove();
    }

    private record BiomeCodec(Object registry, Codec<?> codec) {}

    private static volatile BiomeCodec biomeCodec;

    public static Codec<?> biomeCodec(Object registry) {
        BiomeCodec b = biomeCodec;
        return b != null && b.registry() == registry ? b.codec() : null;
    }

    public static void biomeCodec(Object registry, Codec<?> codec) {
        biomeCodec = new BiomeCodec(registry, codec);
    }

    private static final Map<Block, String> BLOCK_IDS = new ConcurrentHashMap<>();
    private static final Map<Fluid, String> FLUID_IDS = new ConcurrentHashMap<>();

    /** ChunkSerializer.saveTicks' block id getter: BuiltInRegistries.BLOCK.getKey(b).toString(), once per block */
    public static final Function<Block, String> BLOCK_ID = b -> {
        String s = BLOCK_IDS.get(b);
        if (s == null) {
            s = BuiltInRegistries.BLOCK.getKey(b).toString();
            BLOCK_IDS.put(b, s);
        }
        return s;
    };

    public static final Function<Fluid, String> FLUID_ID = f -> {
        String s = FLUID_IDS.get(f);
        if (s == null) {
            s = BuiltInRegistries.FLUID.getKey(f).toString();
            FLUID_IDS.put(f, s);
        }
        return s;
    };

    /**
     * -Dlab.opt.SaveTicks (with SaveWriteMixin): a deferred save's scheduled ticks made into tags on the pool. The main
     * thread keeps only the ticks themselves (immutable records: a ProtoChunkTicks' list, a LevelChunkTicks' pending
     * list and queue, each copied into an array in its iteration order) and the game time; the chunk tag gets an empty
     * ListTag that the pool fills - the same elements in the same order as the container's save would add them.
     */
    public static final boolean TICKS = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.SaveTicks");

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static net.minecraft.nbt.Tag ticks(SerializableTickContainer c, long gameTime, Function ids) {
        if (TICKS && !vanillaNow() && AsyncChunkSave.collecting()) {
            TickJob job = null;
            if (c.getClass() == ProtoChunkTicks.class) {
                job = new TickJob(((dev.lull.opt.SaveTicksProtoAccessor) c).sleep$ticks().toArray(), null, gameTime, ids);
            } else if (c.getClass() == LevelChunkTicks.class) {
                dev.lull.opt.SaveTicksLevelAccessor a = (dev.lull.opt.SaveTicksLevelAccessor) c;
                java.util.List<?> pending = a.sleep$pendingTicks();
                job = new TickJob(pending == null ? null : pending.toArray(), a.sleep$tickQueue().toArray(), gameTime, ids);
            }
            if (job != null) {
                ListTag placeholder = new ListTag();
                AsyncChunkSave.defer(placeholder, job);
                return placeholder;
            }
        }
        return c.save(gameTime, ids);
    }

    /** a tick container's save, from its ticks taken on the main thread */
    public static final class TickJob {
        /** saved ticks (ProtoChunkTicks.ticks, LevelChunkTicks.pendingTicks) */
        final Object[] saved;
        /** LevelChunkTicks.tickQueue's ticks, null for a ProtoChunkTicks */
        final Object[] scheduled;
        final long gameTime;
        @SuppressWarnings("rawtypes")
        final Function ids;

        @SuppressWarnings("rawtypes")
        TickJob(Object[] saved, Object[] scheduled, long gameTime, Function ids) {
            this.saved = saved;
            this.scheduled = scheduled;
            this.gameTime = gameTime;
            this.ids = ids;
        }

        /** ProtoChunkTicks.save / LevelChunkTicks.save into the placeholder */
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void fill(ListTag out) {
            if (saved != null) for (Object t : saved) out.add(((SavedTick) t).save(ids));
            if (scheduled != null) for (Object t : scheduled) out.add(SavedTick.saveTick((ScheduledTick) t, ids, gameTime));
        }
    }

    private static final ShortTag[] SHORTS = new ShortTag[4096];

    static {
        for (int i = 0; i < SHORTS.length; i++) SHORTS[i] = ShortTag.valueOf((short) i);
    }

    /** ChunkSerializer.packOffsets */
    public static ListTag packOffsets(ShortList[] lists) {
        ListTag out = new ListTag();
        for (ShortList l : lists) {
            ListTag inner = new ListTag();
            if (l != null) {
                for (int i = 0, n = l.size(); i < n; i++) {
                    short s = l.getShort(i);
                    inner.add(s >= 0 && s < SHORTS.length ? SHORTS[s] : ShortTag.valueOf(s));
                }
            }
            out.add(inner);
        }
        return out;
    }
}
