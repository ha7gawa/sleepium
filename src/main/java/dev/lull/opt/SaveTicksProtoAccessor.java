package dev.lull.opt;

import java.util.List;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SaveTicks (optsupport/SaveWrite): a proto chunk's saved ticks, taken for the pool. */
@Mixin(ProtoChunkTicks.class)
public interface SaveTicksProtoAccessor {
    @Accessor("ticks")
    List<?> sleep$ticks();
}
