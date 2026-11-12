package dev.lull.optsupport;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Entity Model Features / Entity Texture Features on the render workers (1.20.1 Forge; notes/69). Both keep the
 * entity being drawn and its phase in statics (ETFState's stacks, its model-part depth and flags; EMFState's phase
 * flags; EMFManager's render counter) and fill caches while drawing: with both on the render thread only, Sleepium
 * kept every entity there next to them. With the classes in exactly the shape read here (EMF 3.3.12, ETF 7.2.x):
 * <ul>
 * <li>those fields are localized (ModLocals: a job's renderer set has its own; LocalizeTransformer rewrites every
 * access, in their classes and in the vanilla classes their mixins went into);</li>
 * <li>their collections in fields are synchronized ones (ETF's LRU caches - access-ordered, a get relinks - and the lazy
 * texture / directory / variator / NBT / global-variable maps); ETFState's render-thread checks answer true in a job;</li>
 * <li>a job mounts the entity on its own ETF stack around the renderer, as ETF's dispatcher hook does on the render
 * thread (forEntity, mount; stackVerify, unMount);</li>
 * <li>EMFModelPart's texture-override fallback, which takes Minecraft's shared buffer source, refuses in a job (the
 * render thread draws that entity: a rare path);</li>
 * <li>ParallelRender's autoInner trusts their mixins in the dispatcher: the job is merged inside it, where ETF and EMF's
 * dispatcher injections run on the render thread as before.</li>
 * </ul>
 * Off with -Dlab.opt.EmfWorkers=false, without Mixin's extension list (LateEventSites), or on any other shape (the log
 * says which). Not pixel-exact by design (11-11: no crash, no flicker, no wrong poses required).
 */
public final class EmfAdapter {
    private EmfAdapter() {}

    static final String ETF_STATE = "traben/entity_texture_features/features/state/ETFState";
    static final String ETF_RENDER_STATE = "traben/entity_texture_features/features/state/ETFEntityRenderState";
    static final String EMF_STATE = "traben/entity_model_features/models/animation/state/EMFState";
    static final String EMF_MANAGER = "traben/entity_model_features/EMFManager";
    static final String EMF_LOD = "traben/entity_model_features/utils/EMFLODHandler";

    /** owner, name, desc, how (s: per job, c: own container per set, k: own counter per set, instance field) */
    private static final String[][] FIELDS = {
            {ETF_STATE, "stateStack", "Ljava/util/Deque;", "c"},
            {ETF_STATE, "specialPhaseStack", "Ljava/util/Deque;", "c"},
            {ETF_STATE, "renderLayerModifyStack", "Ljava/util/Deque;", "c"},
            {ETF_STATE, "currentModelPartDepth", "I", "s"},
            {ETF_STATE, "isRenderingFeatures", "Z", "s"},
            {ETF_STATE, "onlyRandomizeViaPropertiesFiles", "Z", "s"},
            {ETF_STATE, "allowTexturePatching", "Z", "s"},
            {ETF_STATE, "stackVerifyHasFailed", "Z", "s"},
            {EMF_STATE, "isLayerPhase", "Z", "s"},
            {EMF_STATE, "isMainPhase", "Z", "s"},
            {EMF_STATE, "isInShoulderMethod", "Z", "s"},
            {EMF_STATE, "isLeftShoulder", "Z", "s"},
            {EMF_STATE, "isInGui", "Z", "s"},
            {EMF_STATE, "isInGroundOverride", "Z", "s"},
            {EMF_STATE, "isOnHead", "Z", "s"},
            {EMF_STATE, "isInHand", "Z", "s"},
            {EMF_STATE, "isInLeftHand", "Ljava/lang/Boolean;", "s"},
            {EMF_STATE, "isInHandItemLayerTransform", "Z", "s"},
            {EMF_STATE, "hasDoneArmOverride", "Ljava/lang/Boolean;", "s"},
            {EMF_STATE, "isInItemFrame", "Z", "s"},
            {EMF_STATE, "modelVariationIgnoresVisibility", "Z", "s"},
            {EMF_MANAGER, "entityRenderCount", "J", "k"},
            {EMF_LOD, "lodFrameSkipping", "Ljava/lang/Boolean;", "s"},   // the entity's LOD answer, reset per entity render
    };

    /** their mixin configs (MixinTouch's names): what autoInner may trust once the adapter is on */
    private static final Set<String> CONFIGS = Set.of("entity_model_features.mixins.json", "entity_texture_features.mixins.json");

    static String why = "not decided";
    public static final boolean ON = decide();

    /** -Dlab.opt.EmfWorkers=false: off */
    private static boolean decide() {
        GameDefaults.ensure();   // a built jar's flags before they are read
        if ("false".equals(System.getProperty("lab.opt.EmfWorkers")) || "false".equals(System.getProperty("lab.opt.defaults")) && !Boolean.getBoolean("lab.opt.EmfWorkers")) {
            why = "off by flag";
            return false;
        }
        try {
            var mods = net.minecraftforge.fml.loading.LoadingModList.get();
            var emf = mods == null ? null : mods.getModFileById("entity_model_features");
            var etf = mods == null ? null : mods.getModFileById("entity_texture_features");
            if (emf == null || etf == null) {
                why = "no EMF / ETF";
                return false;
            }
            String ve = emf.getMods().get(0).getVersion().toString(), vt = etf.getMods().get(0).getVersion().toString();
            if (!ve.equals("3.3.12") || !vt.startsWith("7.2.")) {
                why = "EMF " + ve + " / ETF " + vt + " (adapted: EMF 3.3.12 with ETF 7.2.x)";
                return false;
            }
            Map<String, ClassNode> classes = new HashMap<>();
            for (String c : new String[] {ETF_STATE, EMF_STATE, EMF_MANAGER, ETF_RENDER_STATE, EMF_LOD}) {
                var file = c.startsWith("traben/entity_texture") ? etf : emf;
                Path p = file.getFile().findResource(c + ".class");
                if (p == null || !Files.exists(p)) {
                    why = c + " missing";
                    return false;
                }
                ClassNode cn = new ClassNode();
                try (InputStream in = Files.newInputStream(p)) {
                    new ClassReader(in).accept(cn, ClassReader.SKIP_CODE);
                }
                classes.put(c, cn);
            }
            for (String[] f : FIELDS) {
                boolean found = false;
                for (FieldNode fn : classes.get(f[0]).fields)
                    if (fn.name.equals(f[1]) && fn.desc.equals(f[2]) && ((fn.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) == !f[3].equals("k")) found = true;
                if (!found) {
                    why = "no field " + f[0] + "." + f[1] + " " + f[2];
                    return false;
                }
            }
            String rs = "L" + ETF_RENDER_STATE + ";";
            if (!has(classes.get(ETF_STATE), "mount", "(" + rs + ")V") || !has(classes.get(ETF_STATE), "unMount", "()V")
                    || !has(classes.get(ETF_STATE), "stackVerify", "(" + rs + ")V")
                    || !has(classes.get(ETF_RENDER_STATE), "forEntity", "(Ltraben/entity_texture_features/utils/ETFEntity;)" + rs)) {
                why = "ETFState's mount / unMount / stackVerify or ETFEntityRenderState.forEntity not as adapted";
                return false;
            }
            for (String[] f : FIELDS) {
                boolean container = f[3].equals("c"), keep = f[3].equals("k");
                if (!ModLocals.register(f[0], f[1], f[2], keep, keep, container)) {
                    why = "field kind not carried: " + f[1];
                    return false;
                }
            }
            why = "EMF " + ve + " / ETF " + vt;
            return true;
        } catch (Throwable t) {
            why = "shape not read: " + t;
            return false;
        }
    }

    private static boolean has(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
        return false;
    }

    /** the adapter is on and Mixin's late extension carries the rewrite (else nothing is localized: off) */
    public static boolean active() {
        return ON && LateEventSites.active();
    }

    /** autoInner: a mixin config of theirs in the dispatcher and in the worker's code is safe with the adapter on */
    public static boolean covers(String config) {
        return active() && CONFIGS.contains(config);
    }

    private static Map<String, String> redirects;

    /** LateEventSites.postApply: every class at the end of its transformation */
    public static void transform(ClassNode cn) {
        if (!ON) return;
        if (cn.name.startsWith("traben/entity_model_features/") || cn.name.startsWith("traben/entity_texture_features/")) {
            if (redirects == null) {
                String bs = dev.lull.Names.method("net/minecraft/client/renderer/RenderBuffers", "bufferSource", "()Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;");
                redirects = Map.of("net/minecraft/client/renderer/RenderBuffers." + bs + "()Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;",
                        "dev/lull/optsupport/EmfAdapter.bufferSource");
            }
            LocalizeTransformer.own(cn, redirects);
            if (cn.name.equals("traben/entity_model_features/models/animation/state/EMFEntityRenderState")) rootsHere(cn);
            if (cn.name.equals("traben/entity_model_features/models/parts/EMFModelPartRoot")) rootOwner(cn);
            lodCalls(cn);
        } else LocalizeTransformer.fields(cn);
    }

    /** activate(): rootPartsPerEntityTypeForVariation.get(...) -> rootsHere(map, key) */
    private static void rootsHere(ClassNode cn) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            boolean armed = false;
            for (org.objectweb.asm.tree.AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof org.objectweb.asm.tree.FieldInsnNode fi && fi.owner.equals(EMF_MANAGER) && fi.name.equals("rootPartsPerEntityTypeForVariation")) armed = true;
                else if (armed && in instanceof org.objectweb.asm.tree.MethodInsnNode mi && mi.owner.equals("java/util/Map") && mi.name.equals("get")) {
                    mi.setOpcode(org.objectweb.asm.Opcodes.INVOKESTATIC);
                    mi.owner = "dev/lull/optsupport/EmfAdapter";
                    mi.name = "rootsHere";
                    mi.desc = "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;";
                    mi.itf = false;
                    armed = false;
                    n++;
                }
            }
        }
        rootSites = n;
    }

    static int rootSites;

    /** EMFModelPartRoot.<init>: rootCreated(this) before each return */
    private static void rootOwner(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("<init>")) continue;
            for (org.objectweb.asm.tree.AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext())
                if (in.getOpcode() == org.objectweb.asm.Opcodes.RETURN) {
                    org.objectweb.asm.tree.InsnList call = new org.objectweb.asm.tree.InsnList();
                    call.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                    call.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "dev/lull/optsupport/EmfAdapter", "rootCreated", "(Ljava/lang/Object;)V", false));
                    m.instructions.insertBefore(in, call);
                    m.maxStack = Math.max(m.maxStack, 1);
                    rootCtors++;
                }
        }
    }

    static int rootCtors;

    // ------------------------------------------------------------------ LOD frame skips

    /**
     * EMF's LOD skips an entity's animation on some frames and replays its last results, kept per animation handler -
     * per model root, so per renderer set. An entity drawn by another set than last time would get that set's older
     * results (or another pose's visibility: a pupil's blink, 576 bytes). In a job, and when the render thread checks a
     * job in place, nothing is skipped: the animation runs (on a worker: off the render thread anyway). Entities the
     * render thread keeps skip as before.
     */
    public static volatile boolean checking;
    private static MethodHandle lodSkip;

    public static boolean lodSkip(String modelId) throws Throwable {
        if (checking || ModLocals.inJob()) return false;
        if (lodSkip == null) {
            Class<?> c = Class.forName(EMF_LOD.replace('/', '.'), true, EmfAdapter.class.getClassLoader());
            lodSkip = MethodHandles.publicLookup().findStatic(c, "isLODSkippingThisFrame", MethodType.methodType(boolean.class, String.class));
        }
        return (boolean) lodSkip.invokeExact(modelId);
    }

    static int lodSites;

    /** EMFLODHandler.isLODSkippingThisFrame(String) calls -> lodSkip */
    private static void lodCalls(ClassNode cn) {
        for (MethodNode m : cn.methods)
            for (org.objectweb.asm.tree.AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext())
                if (in instanceof org.objectweb.asm.tree.MethodInsnNode mi && mi.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                        && mi.owner.equals(EMF_LOD) && mi.name.equals("isLODSkippingThisFrame") && mi.desc.equals("(Ljava/lang/String;)Z")) {
                    mi.owner = "dev/lull/optsupport/EmfAdapter";
                    mi.name = "lodSkip";
                    lodSites++;
                }
    }

    /** EMFModelPart's texture-override fallback: Minecraft's shared buffer source - not from a job */
    public static net.minecraft.client.renderer.MultiBufferSource.BufferSource bufferSource(net.minecraft.client.renderer.RenderBuffers rb) {
        if (ModLocals.inJob()) throw ParallelRender.REFUSE_NOT_READY;
        return rb.bufferSource();
    }

    // ------------------------------------------------------------------ model roots per renderer set

    /**
     * EMF registers every model root it bakes in EMFManager.rootPartsPerEntityTypeForVariation, and an entity's
     * activate() runs the variant check on every root of its type - those of the other renderer sets too, which another
     * worker may be drawing with at that moment (a sheep's wool layer came and went: 10512 vs 11088 bytes). A root is
     * registered there lazily, at its first draw; it is owned from its constructor (EMFModelPartRoot.<init> rewritten to
     * call rootCreated): the set being built, else the job's set, else the render thread. activate() sees only the roots
     * of where it runs.
     */
    private static final Object RENDER_THREAD = new Object();
    private static final Map<Object, Object> OWNER = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** the set being built (render thread, ParallelRender.ensureSets), a marker until its copy is known */
    private static volatile Object building;

    /** from EMFModelPartRoot's constructor */
    public static void rootCreated(Object root) {
        Object b = building;
        Object o = b != null && com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread() ? b : ModLocals.current();
        OWNER.put(root, o != null ? o : RENDER_THREAD);
    }

    /** ParallelRender, before a renderer set is built */
    public static void beginSet() {
        building = new Object();
    }

    /** after it is built: its roots are the set's (token: its ModLocals copy) */
    public static void endSet(Object token) {
        Object b = building;
        building = null;
        synchronized (OWNER) {
            for (Map.Entry<Object, Object> e : OWNER.entrySet()) if (e.getValue() == b) e.setValue(token);
        }
    }

    /** in place of rootPartsPerEntityTypeForVariation.get(type) (EMFEntityRenderState.activate): this thread's roots */
    public static Object rootsHere(Map<?, ?> reg, Object key) {
        Object all = reg.get(key);
        if (!(all instanceof java.util.Set<?> roots) || OWNER.isEmpty()) return all;
        Object me = ModLocals.current();
        if (me == null) me = RENDER_THREAD;
        java.util.Set<Object> mine = new java.util.HashSet<>();
        for (Object r : roots) {
            Object o = OWNER.get(r);
            if (o == me || o == null && me == RENDER_THREAD) mine.add(r);
        }
        return mine;
    }

    // ------------------------------------------------------------------ the job's mount (ETF's dispatcher hook, on the worker)

    private static MethodHandle forEntity, mount, stackVerify, unMount;

    private static void handles() throws ReflectiveOperationException {
        ClassLoader cl = EmfAdapter.class.getClassLoader();
        Class<?> state = Class.forName(ETF_STATE.replace('/', '.'), true, cl);
        Class<?> rs = Class.forName(ETF_RENDER_STATE.replace('/', '.'), true, cl);
        Class<?> ent = Class.forName("traben.entity_texture_features.utils.ETFEntity", true, cl);
        MethodHandles.Lookup lk = MethodHandles.publicLookup();
        forEntity = lk.findStatic(rs, "forEntity", MethodType.methodType(rs, ent)).asType(MethodType.methodType(Object.class, Object.class));
        mount = lk.findStatic(state, "mount", MethodType.methodType(void.class, rs)).asType(MethodType.methodType(void.class, Object.class));
        stackVerify = lk.findStatic(state, "stackVerify", MethodType.methodType(void.class, rs)).asType(MethodType.methodType(void.class, Object.class));
        unMount = lk.findStatic(state, "unMount", MethodType.methodType(void.class));
    }

    /** In a job, before the renderer: the entity's ETF state mounted on the job's stack; null when ETF has none for it. */
    public static Object mount(net.minecraft.world.entity.Entity e) {
        try {
            if (forEntity == null) handles();
            Object st = (Object) forEntity.invokeExact((Object) e);
            if (st != null) mount.invokeExact(st);
            return st;
        } catch (RuntimeException | Error x) {
            throw x;
        } catch (Throwable x) {
            throw new IllegalStateException(x);
        }
    }

    /** After the renderer (also when it threw): as ETF's hook at the dispatcher's return. */
    public static void unmount(Object st) {
        try {
            stackVerify.invokeExact(st);
            unMount.invokeExact();
        } catch (RuntimeException | Error x) {
            throw x;
        } catch (Throwable x) {
            throw new IllegalStateException(x);
        }
    }

    public static String report() {
        return "EMF / ETF on render workers: " + (active() ? "on (" + why + "), " + LocalizeTransformer.report() + ", variant checks per set " + rootSites + " (roots owned " + OWNER.size() + ", constructors " + rootCtors + "), LOD skip sites " + lodSites + "; localized " + ModLocals.describe()
                : ON ? "off: Mixin's extension list out of reach" : "off: " + why);
    }
}
