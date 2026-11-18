package dev.lull.opt;

import java.util.List;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.util.random.WeightedEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Enclosed: a weighted model's variants. */
@Mixin(WeightedBakedModel.class)
public interface WeightedBakedModelAccessor {
    @Accessor("list")
    List<WeightedEntry.Wrapper<BakedModel>> sleep$list();
}
