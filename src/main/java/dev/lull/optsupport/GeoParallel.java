package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * GeckoLib on ParallelRender's workers (the probe-geo mixins, loaded only with GeckoLib): what GeckoLib keeps in
 * globals, made per worker renderer set or per job.
 * <ul>
 *   <li>bones: GeckoLib animates a model by writing into the GeoBones of the one BakedGeoModel in GeckoLibCache, shared
 *       by every renderer of that model. A GeoModel built for a worker set gets its own copy ({@link #copy}): same
 *       geometry (cubes shared, immutable), own bones in their rest pose;</li>
 *   <li>the Molang actor and variables: per job ({@link ParallelRender#jobLocals}, key {@link #ACTOR_KEY} for the actor).</li>
 * </ul>
 */
public final class GeoParallel {
    public static final Object ACTOR_KEY = new Object();
    private static final Field[] BONE_STATE;   // non-static, non-final GeoBone fields
    private static final Field[] BONE_MATRICES;   // final Matrix4f fields: values copied
    private static final Field MIRROR, INFLATE, DONT_RENDER, RESET, SNAP_BONE;
    private static final Field[] SNAP_FIELDS;   // BoneSnapshot fields other than its bone
    public static long copies;

    static {
        try {
            List<Field> state = new ArrayList<>(), matrices = new ArrayList<>();
            for (Field f : GeoBone.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (!Modifier.isFinal(f.getModifiers())) state.add(f);
                else if (f.getType() == Matrix4f.class) matrices.add(f);
            }
            BONE_STATE = state.toArray(Field[]::new);
            BONE_MATRICES = matrices.toArray(Field[]::new);
            MIRROR = bf("mirror");
            INFLATE = bf("inflate");
            DONT_RENDER = bf("dontRender");
            RESET = bf("reset");
            List<Field> snap = new ArrayList<>();
            Field sb = null;
            for (Field f : BoneSnapshot.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (dev.lull.Names.officialMember(f.getName()).equals("bone")) sb = f;
                else snap.add(f);
            }
            SNAP_BONE = sb;
            SNAP_FIELDS = snap.toArray(Field[]::new);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("sleep: GeckoLib's GeoBone / BoneSnapshot are not what GeoParallel expects", e);
        }
    }

    private static Field bf(String name) throws NoSuchFieldException {
        Field f = dev.lull.Names.fieldOf(GeoBone.class, name);
        f.setAccessible(true);
        return f;
    }

    private GeoParallel() {}

    /** A copy with its own bones; a bone class other than GeoBone itself (a mod's subclass) is refused. */
    public static BakedGeoModel copy(BakedGeoModel model) {
        List<GeoBone> top = new ArrayList<>(model.topLevelBones().size());
        try {
            for (GeoBone b : model.topLevelBones()) top.add(copy(b, null));
        } catch (ReflectiveOperationException e) {
            throw ParallelRender.REFUSE_SHARED;
        }
        copies++;
        return new BakedGeoModel(top, model.properties());
    }

    private static GeoBone copy(GeoBone b, GeoBone parent) throws ReflectiveOperationException {
        if (b.getClass() != GeoBone.class) throw ParallelRender.REFUSE_SHARED;
        GeoBone c = new GeoBone(parent, b.getName(), (Boolean) MIRROR.get(b), (Double) INFLATE.get(b), (Boolean) DONT_RENDER.get(b), (Boolean) RESET.get(b));
        for (Field f : BONE_STATE) {
            Object v = f.get(b);
            f.set(c, v instanceof Matrix3f m ? new Matrix3f(m) : v);
        }
        for (Field f : BONE_MATRICES) ((Matrix4f) f.get(c)).set((Matrix4f) f.get(b));
        BoneSnapshot init = b.getInitialSnapshot();
        if (init != null) {   // own initial snapshot bound to the copy, and the copy in that rest pose
            BoneSnapshot s = BoneSnapshot.copy(init);
            SNAP_BONE.set(s, c);
            for (Field f : BONE_STATE) if (f.getType() == BoneSnapshot.class) f.set(c, s);
            c.updateRotation(init.getRotX(), init.getRotY(), init.getRotZ());
            c.updateScale(init.getScaleX(), init.getScaleY(), init.getScaleZ());
            c.updatePosition(snapFloat(init, "offsetPosX"), snapFloat(init, "offsetPosY"), snapFloat(init, "offsetPosZ"));
        }
        c.getCubes().addAll(b.getCubes());
        for (GeoBone child : b.getChildBones()) c.getChildBones().add(copy(child, c));
        return c;
    }

    private static float snapFloat(BoneSnapshot s, String name) throws ReflectiveOperationException {
        for (Field f : SNAP_FIELDS) if (dev.lull.Names.officialMember(f.getName()).equals(name)) return f.getFloat(s);
        throw new NoSuchFieldException(name);
    }
}
