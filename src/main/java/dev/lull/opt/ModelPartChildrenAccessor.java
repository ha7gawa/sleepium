package dev.lull.opt;

import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** KeyframeLookupMixin: a part's children, walked in their map's order. */
@Mixin(ModelPart.class)
public interface ModelPartChildrenAccessor {
    @Accessor("children")
    Map<String, ModelPart> sleep$children();
}
