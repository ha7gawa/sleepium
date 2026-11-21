package dev.lull.opt;

import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SaveStreams (optsupport/SaveStreams): a compound's map, walked without a key set and a lookup per key. */
@Mixin(CompoundTag.class)
public interface SaveStreamsCompoundAccessor {
    @Accessor("tags")
    Map<String, Tag> sleep$tags();
}
