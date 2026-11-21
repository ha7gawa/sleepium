package dev.lull.opt;

import java.util.List;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SaveStreams (optsupport/SaveStreams): a list's elements, and its type as ListTag.write sets it. */
@Mixin(ListTag.class)
public interface SaveStreamsListAccessor {
    @Accessor("list")
    List<Tag> sleep$list();

    @Accessor("type")
    void sleep$setType(byte type);
}
