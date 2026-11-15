package dev.lull.opt;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * exp/modelpart-quat (client): ModelPart.render's loop over its children and compile's loop over its cubes use one
 * reusable array iterator per part instead of a new iterator per call (the map's values iterator, the list iterator).
 * Same elements, same order: the arrays are taken from the map / list (and taken again if their size changes). A part
 * never renders itself recursively and is used by one thread at a time, so its iterator is never shared.
 * (1.20.1: the render overload with the colour as four floats.)
 */
@Mixin(ModelPart.class)
public abstract class ModelPartIterMixin {
    @Shadow @Final private List<ModelPart.Cube> cubes;
    @Shadow @Final private Map<String, ModelPart> children;

    @Unique private final dev.lull.optsupport.MixinHelpers.ArrayIter sleep$childIter = new dev.lull.optsupport.MixinHelpers.ArrayIter(),
            sleep$cubeIter = new dev.lull.optsupport.MixinHelpers.ArrayIter();
    @Unique private Object[] sleep$children, sleep$cubes;

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V", at = @At(value = "INVOKE",
            target = "Ljava/util/Collection;iterator()Ljava/util/Iterator;"))
    private Iterator<?> sleep$children(java.util.Collection<?> values) {
        Object[] a = sleep$children;
        if (a == null || a.length != children.size()) sleep$children = a = children.values().toArray();
        return sleep$childIter.reset(a);
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<?> sleep$cubes(List<?> list) {
        Thread g = dev.lull.optsupport.PartBatch.GEO_THREAD;
        if (g != null && g == Thread.currentThread()) return list.iterator();   // (a geometry compile from a job's journal: the part may be rendering on a worker)
        Object[] a = sleep$cubes;
        if (a == null || a.length != cubes.size()) sleep$cubes = a = cubes.toArray();
        return sleep$cubeIter.reset(a);
    }
}
