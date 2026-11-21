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
