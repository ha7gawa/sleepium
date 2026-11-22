package dev.lull.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** ServeFeel (notes/92): the sequence number of the block prediction just sent */
@Mixin(ClientLevel.class)
public interface ServeFeelLevelAccessor {
    @Invoker("getBlockStatePredictionHandler")
    BlockStatePredictionHandler sleep$predictions();
}
