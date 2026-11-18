package dev.lull.opt;

import java.util.Collections;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A weighted block model's quads (grass, stone, dirt - every face of every such block meshed) without the Optional and
 * the lambda vanilla wraps each pick in (WeightedRandom.getWeightedItem(...).map(...).orElse(...): 3.6 % of the garbage
 * in a flight over the zoo, 11-18). The same single nextLong, the same walk down the weights, the same model asked.
 */
@Mixin(WeightedBakedModel.class)
public abstract class WeightedQuadsMixin {
    @Shadow @Final private int totalWeight;
    @Shadow @Final private List<WeightedEntry.Wrapper<BakedModel>> list;

    /**
     * @author Sleepium
     * @reason no Optional / lambda per pick - the same pick
     */
    @Overwrite
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random, ModelData modelData,
                                    @Nullable RenderType renderType) {
        int weightedIndex = Math.abs((int) random.nextLong()) % this.totalWeight;
        List<WeightedEntry.Wrapper<BakedModel>> l = this.list;
        for (int i = 0, n = l.size(); i < n; i++) {
            WeightedEntry.Wrapper<BakedModel> t = l.get(i);
            weightedIndex -= t.getWeight().asInt();
            if (weightedIndex < 0) return t.data().getQuads(state, direction, random, modelData, renderType);
        }
        return Collections.emptyList();
    }
}
