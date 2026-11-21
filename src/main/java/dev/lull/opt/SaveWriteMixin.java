package dev.lull.opt;

import com.mojang.serialization.Codec;
import dev.lull.optsupport.SaveWrite;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ticks.SerializableTickContainer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** SaveWrite (see dev.lull.optsupport.SaveWrite): ChunkSerializer.write's biome codec, tick ids and post-processing lists; with -Dlab.opt.SaveTicks the tick lists made on the pool. */
@Mixin(ChunkSerializer.class)
public abstract class SaveWriteMixin {
    @Shadow
    private static Codec<PalettedContainerRO<Holder<Biome>>> makeBiomeCodec(Registry<Biome> biomeRegistry) {
        throw new AssertionError();
    }

    @SuppressWarnings("unchecked")
    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;makeBiomeCodec(Lnet/minecraft/core/Registry;)Lcom/mojang/serialization/Codec;"))
    private static Codec<PalettedContainerRO<Holder<Biome>>> sleep$biomeCodec(Registry<Biome> registry) {
        if (SaveWrite.vanillaNow()) return makeBiomeCodec(registry);
        Codec<?> c = SaveWrite.biomeCodec(registry);
        if (c == null) {
            c = makeBiomeCodec(registry);
            SaveWrite.biomeCodec(registry, c);
        }
        return (Codec<PalettedContainerRO<Holder<Biome>>>) c;
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;packOffsets([Lit/unimi/dsi/fastutil/shorts/ShortList;)Lnet/minecraft/nbt/ListTag;"))
    private static ListTag sleep$packOffsets(ShortList[] lists) {
        return SaveWrite.vanillaNow() ? ChunkSerializer.packOffsets(lists) : SaveWrite.packOffsets(lists);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "saveTicks", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/ticks/SerializableTickContainer;save(JLjava/util/function/Function;)Lnet/minecraft/nbt/Tag;"))
    private static Tag sleep$blockTicks(SerializableTickContainer container, long gameTime, Function idGetter) {
        return SaveWrite.ticks(container, gameTime, SaveWrite.vanillaNow() ? idGetter : SaveWrite.BLOCK_ID);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "saveTicks", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/world/ticks/SerializableTickContainer;save(JLjava/util/function/Function;)Lnet/minecraft/nbt/Tag;"))
    private static Tag sleep$fluidTicks(SerializableTickContainer container, long gameTime, Function idGetter) {
        return SaveWrite.ticks(container, gameTime, SaveWrite.vanillaNow() ? idGetter : SaveWrite.FLUID_ID);
    }
}
