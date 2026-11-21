package dev.lull.opt;

import java.util.Optional;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** KeyframeLookup: an animated bone found by name without streaming the part tree (see there). */
@Mixin(KeyframeAnimations.class)
public abstract class KeyframeLookupMixin {
    @Redirect(method = "animate", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/HierarchicalModel;getAnyDescendantWithName(Ljava/lang/String;)Ljava/util/Optional;"))
    private static Optional<ModelPart> sleep$lookup(HierarchicalModel<?> model, String name) {
        return dev.lull.optsupport.KeyframeLookup.lookup(model, name);
    }
}
