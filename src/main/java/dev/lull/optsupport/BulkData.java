package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;

/** Which VertexConsumer classes keep the default short putBulkData overloads (BulkDataArraysMixin redirects only those). */
public final class BulkData {
    /** the 8-argument overload (VertexConsumer default) */
    public static final ClassValue<Boolean> SHORT8_DEFAULT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return declaredBy(c, new Class<?>[] {PoseStack.Pose.class, BakedQuad.class, float.class, float.class, float.class, float.class, int.class, int.class},
                    VertexConsumer.class);
        }
    };
    /** the 9-argument NeoForge overload (IVertexConsumerExtension default) */
    public static final ClassValue<Boolean> SHORT9_DEFAULT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return false;   // (Fabric: no NeoForge overload)
        }
    };

    private BulkData() {}

    private static boolean declaredBy(Class<?> c, Class<?>[] params, Class<?> expected) {
        try {
            return dev.lull.Names.publicMethod(c, "putBulkData", params).getDeclaringClass() == expected;
        } catch (NoSuchMethodException | RuntimeException e) {
            return false;
        }
    }
}
