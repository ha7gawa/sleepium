package dev.lull.optsupport;

import java.util.Optional;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * KeyframeLookupMixin: KeyframeAnimations.animate looks every animated bone up by name, every frame, for every mob;
 * vanilla's HierarchicalModel.getAnyDescendantWithName streams the whole part tree (Stream.concat / flatMap at every
 * part) to find the first part, in pre-order, with a child of that name - 10 % of all the garbage in a flight over the
 * zoo (11-18). Here the same pre-order walk over the children maps, no streams. Vanilla's way whenever the model
 * overrides the lookup or a part of its tree is not a plain ModelPart (models of mods that change parts' behaviour).
 */
public final class KeyframeLookup {
    private KeyframeLookup() {}

    private static final ClassValue<Boolean> PLAIN = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            try {
                return dev.lull.Names.publicMethod(c, "getAnyDescendantWithName", String.class).getDeclaringClass() == HierarchicalModel.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };

    /** a marker: the tree holds a part that is not a plain ModelPart */
    private static final ModelPart NOT_PLAIN = new ModelPart(java.util.List.of(), java.util.Map.of());

    public static Optional<ModelPart> lookup(HierarchicalModel<?> model, String name) {
        if (name.equals("root") || !PLAIN.get(model.getClass())) return model.getAnyDescendantWithName(name);
        ModelPart found = find(model.root(), name);
        if (found == NOT_PLAIN) return model.getAnyDescendantWithName(name);
        return found == null ? Optional.empty() : Optional.of(found);
    }

    /** getAllParts' order: the part, then each child's subtree in the children map's order; the first with the child wins */
    private static ModelPart find(ModelPart part, String name) {
        if (part.getClass() != ModelPart.class) return NOT_PLAIN;
        if (part.hasChild(name)) return part.getChild(name);
        for (ModelPart c : ((dev.lull.opt.ModelPartChildrenAccessor) (Object) part).sleep$children().values()) {
            ModelPart r = find(c, name);
            if (r != null) return r;
        }
        return null;
    }
}
