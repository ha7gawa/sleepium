package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.function.ToIntFunction;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** -Dlab.tintBlend: a miss fills its whole layer by running sums (TintBlend). */
@Mixin(BlockTintCache.class)
public abstract class TintBlendCacheMixin implements dev.lull.optsupport.TintBlend.Bound {
    @Unique
    private ClientLevel sleep$level;
    @Unique
    private ColorResolver sleep$resolver;

    @Override
    public void sleep$bind(ClientLevel level, ColorResolver resolver) {
        sleep$level = level;
        sleep$resolver = resolver;
    }

    @Override
    public ClientLevel sleep$level() {
        return sleep$level;
    }

    @Override
    public ColorResolver sleep$resolver() {
        return sleep$resolver;
    }

    @WrapOperation(method = "getColor", at = @At(value = "INVOKE", target = "Ljava/util/function/ToIntFunction;applyAsInt(Ljava/lang/Object;)I"))
    private int sleep$fillLayer(ToIntFunction<Object> source, Object pos, Operation<Integer> original, @Local int[] layer) {
        int v = dev.lull.optsupport.TintBlend.fill(this, layer, (BlockPos) pos);
        return v != Integer.MIN_VALUE ? v : original.call(source, pos);
    }
}
