package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lab.runtime.LabRuntime;
import dev.lab.runtime.OsThreads;
import dev.lull.opt.EntityRenderDispatcherInvoker;
import dev.lull.opt.LevelRendererInvoker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * exp/parallel-render (client): entity vertices generated on worker threads, bit-exact.
 *
 * <p>LevelRenderer's entity loop no longer draws: each entity becomes a job in an ordered frame queue. An eligible one is
 * rendered at once by a worker — the same computation as renderEntity + EntityRenderDispatcher.render (same
 * camera-relative pose, light, offset, shadow) into the worker's own recording source — with its own full set of
 * entity renderers, item renderer and hand renderer (vanilla shares one model per entity type and setupAnim writes
 * into it). After the loop, the render thread walks the queue in order: a worker job's bytes are appended per render
 * type in the order they were asked for, the others are rendered there the vanilla way. Same bytes in the same order
 * as vanilla, so the frame hash holds; {@code -Dlab.prender.verify=true} also renders every worker job on the render
 * thread and compares the bytes.
 *
 * <p>No game logic runs meanwhile: the render thread waits for the workers, and the client tick runs on it. Kept on the
 * render thread: players, named entities (text may upload glyphs), burning ones (flame uses the camera), outline
 * rendering, hitboxes, render events with listeners (a mod's listener must not run on a worker), and — with the render
 * cache on — every entity the cache handles.
 */
public final class ParallelRender {
    public static final boolean VERIFY = Boolean.getBoolean("lab.prender.verify");
    /** renderer sets in use at once = the reactor's workers (+1 for the render thread helping) */
    private static final int THREADS = FrameReactor.threads();
    public static long jobs, parallel, sequential, failed, verified, mismatches, helped, refused, journaled, mergeNanos;

    // ------------------------------------------------------------------ the job as a unit of side effects

    /** the job this thread renders now (a worker, or the render thread helping) */
    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();

    public static boolean inJob() {
        return CURRENT.get() != null;
    }

    /**
     * RenderSystem.recordRenderCall inside a job (render-thread work a renderer defers): kept in the job and run on
     * the render thread when the job is merged, in the job order (vanilla ran it in place, during the entity loop).
     * Also for other side effects a mod renderer makes (GeckoLib keyframe sounds / particles). False outside jobs.
     */
    public static boolean journal(com.mojang.blaze3d.pipeline.RenderCall call) {
        Job j = CURRENT.get();
        if (j == null) return false;
        j.calls.add(call);
        return true;
    }

    /**
     * Per-job state for a mod's globals (GeckoLib's Molang actor and variables): empty at the start of each job, so the
     * result does not depend on which worker ran what before. Null outside jobs (the globals then as they are).
     */
    public static java.util.IdentityHashMap<Object, Object> jobLocals() {
        Job j = CURRENT.get();
        return j == null ? null : j.locals;
    }

    /** The current job's part records (null outside jobs, and in verify mode: the bytes are compared there). */
    public static PartBatch.JobParts jobParts() {
        Job j = CURRENT.get();
        return j == null || VERIFY ? null : j.parts;
    }

    /** true while ParallelRender builds its renderer sets: objects made now belong to a worker set */
    public static volatile boolean buildingSets;

    /**
     * Thrown inside a job to hand it to the render thread: transient (state not ready yet: a texture without GL id, a
     * cache miss whose fill needs the render thread) or permanent (the renderer shares mutable state with the render
     * thread, or posts an event somebody listens to): that entity type stays on the render thread.
     */
    public static final class Refusal extends RuntimeException {
        final boolean permanent;

        Refusal(String why, boolean permanent) {
            super(why, null, false, false);
            this.permanent = permanent;
        }
    }

    public static final Refusal REFUSE_NOT_READY = new Refusal("render-thread state not ready", false);
    public static final Refusal REFUSE_SHARED = new Refusal("renderer state shared with the render thread", true);
    public static final Refusal REFUSE_LISTENED = new Refusal("event with listeners posted from a worker", true);
    private static volatile int epoch;

    private ParallelRender() {}

    private static LabRuntime pool;

    private static LabRuntime pool() {
        return FrameReactor.pool();   // the frame's shared pool
    }

    /** From EntityRenderDispatcher.onResourceManagerReload: the worker renderer sets are built again. */
    public static void reloaded() {
        epoch++;
        CHECKS.clear();
    }

    // ------------------------------------------------------------------ per-worker renderer sets

    static final class Set {
        final int epoch;
        final PoseStack pose = new PoseStack();
        /** the set's recorder: a set serves one job at a time, and finish copies into the job's record */
        final RenderCache.Recorder recorder = new RenderCache.Recorder();
        final Map<EntityType<?>, EntityRenderer<?>> renderers;
        final Map<PlayerSkin.Model, EntityRenderer<? extends net.minecraft.world.entity.player.Player>> players;

        Set(int epoch) {
            this.epoch = epoch;
            Minecraft mc = Minecraft.getInstance();
            EntityRenderDispatcher d = mc.getEntityRenderDispatcher();
            var rm = mc.getResourceManager();
            BlockEntityWithoutLevelRenderer bewlr = new BlockEntityWithoutLevelRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
            bewlr.onResourceManagerReload(rm);
            ItemRenderer ir = new ItemRenderer(mc, mc.getTextureManager(), mc.getModelManager(), mc.getItemColors(), bewlr);
            ir.onResourceManagerReload(rm);
            ItemInHandRenderer hand = new ItemInHandRenderer(mc, d, ir);
            EntityRendererProvider.Context ctx = new EntityRendererProvider.Context(d, ir, mc.getBlockRenderer(), hand, rm, mc.getEntityModels(), mc.font);
            renderers = EntityRenderers.createEntityRenderers(ctx);
            players = EntityRenderers.createPlayerRenderers(ctx);
            net.neoforged.fml.ModLoader.postEvent(new net.neoforged.neoforge.client.event.EntityRenderersEvent.AddLayers(renderers, players, ctx));
        }

        @SuppressWarnings("unchecked")
        <T extends Entity> EntityRenderer<? super T> renderer(T e) {
            return (EntityRenderer<? super T>) renderers.get(e.getType());
        }
    }

    /** a bounded array queue: no node object per poll / offer (one per job with a linked queue) */
    private static final java.util.concurrent.ArrayBlockingQueue<Set> SETS = new java.util.concurrent.ArrayBlockingQueue<>(THREADS + 2);

    /** THREADS sets, built on the render thread (a mod renderer's constructor may touch GL). */
    private static void ensureSets() {
        int e = epoch;
        Set any = SETS.peek();
        if (any != null && any.epoch == e && SETS.size() == THREADS + 1) return;
        SETS.clear();
        long t0 = System.nanoTime();
        buildingSets = true;
        try {
            for (int i = 0; i < THREADS + 1; i++) SETS.add(new Set(e));   // one more: the render thread helps
        } finally {
            buildingSets = false;
        }
        System.out.printf("[sleep/opt] parallel render: %d renderer sets built in %.1f ms%n", THREADS, (System.nanoTime() - t0) / 1e6);
    }

    // ------------------------------------------------------------------ the frame queue

    static final class Job implements Runnable {
        Entity entity;
        double camX, camY, camZ;
        float pt;
        PoseStack pose;
        MultiBufferSource buffers;
        boolean parallel;
        volatile boolean done, ok;
        /** INNER: the renderer call's arguments and pose (the dispatcher's offset on the identity), compared at the merge */
        float yaw;
        int light;
        final org.joml.Matrix4f innerPose = new org.joml.Matrix4f();
        final org.joml.Matrix3f innerNormal = new org.joml.Matrix3f();
        final RenderCache.Rec out = new RenderCache.Rec();
        final List<com.mojang.blaze3d.pipeline.RenderCall> calls = new ArrayList<>();
        /** records of model parts drawn from VRAM (GeckoLib bones), queued at the merge */
        final PartBatch.JobParts parts = new PartBatch.JobParts();
        final java.util.IdentityHashMap<Object, Object> locals = new java.util.IdentityHashMap<>();

        /** worker or render thread: whoever claims the job first renders it */
        final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) return;   // the render thread took it while waiting
            if (PIN_WORKERS != null && !PINNED.get()) pinWorker();
            Set s = SETS.poll();
            calls.clear();
            locals.clear();
            parts.clear();
            CURRENT.set(this);
            try {
                ok = s != null && render(s, this);
            } catch (Refusal r) {
                ok = false;
                refused++;
                var type = entity == null ? null : entity.getType();   // (a job may have no entity: the handler must not die - 10-05, a worker thread lost)
                if (r.permanent && type != null && WORKER_REFUSED.add(type))
                    System.out.println("[sleep/opt] parallel render: " + type + " kept on the render thread: " + r.getMessage());
            } catch (Throwable t) {
                ok = false;
                var type = entity == null ? null : entity.getType();
                if (failed++ < 5) {
                    System.out.println("[sleep/opt] parallel render: " + type + " failed on a worker: " + t);
                    if (STACK) for (StackTraceElement el : java.util.Arrays.copyOf(t.getStackTrace(), Math.min(14, t.getStackTrace().length))) System.out.println("    at " + el);
                }
                if (type != null) learnFailure(type, t);
            } finally {
                CURRENT.remove();
                if (!ok) {   // the render thread renders it again, side effects included
                    calls.clear();
                    parts.clear();
                }
                if (s != null) SETS.add(s);
                done = true;
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        void await() {
            if (done) return;
            synchronized (this) {
                while (!done) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static final List<Job> QUEUE = new ArrayList<>();
    private static final List<Job> FREE = new ArrayList<>();
    private static int size;
    private static int listened;   // EventSites.renderListenedMask, once per frame
    /** -Dlab.prender.anyListener: the old rule - any listened entity render event keeps every entity on the render thread (not final: ClientAuto's in-frame A/B flips it) */
    static boolean anyListener = Boolean.getBoolean("lab.prender.anyListener");

    /**
     * -Dlab.prender.pin=render;w1,w2,...: the render thread pinned to one logical CPU and each worker, at its first
     * job, to the next of the list (experiment: separate physical cores; the default spreads over even numbers).
     */
    private static final String PIN = System.getProperty("lab.prender.pin");
    private static final long RENDER_PIN;
    private static final long[] PIN_WORKERS;
    private static final ThreadLocal<Boolean> PINNED = ThreadLocal.withInitial(() -> false);
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_PIN = new java.util.concurrent.atomic.AtomicInteger();
    private static boolean renderPinned;

    static {
        long rp = 0;
        long[] wp = null;
        if (PIN != null) {
            String spec = PIN.equals("true") ? "2;4,6,8,10" : PIN;
            String[] parts = spec.split(";");
            rp = 1L << Integer.parseInt(parts[0].trim());
            String[] ws = parts.length > 1 ? parts[1].split(",") : new String[0];
            wp = new long[ws.length];
            for (int i = 0; i < ws.length; i++) wp[i] = 1L << Integer.parseInt(ws[i].trim());
        }
        RENDER_PIN = rp;
        PIN_WORKERS = wp;
    }

    private static void pinWorker() {
        PINNED.set(true);
        if (PIN_WORKERS.length == 0) return;
        int i = NEXT_PIN.getAndIncrement() % PIN_WORKERS.length;
        boolean ok = dev.lab.runtime.OsThreads.pinCurrent(PIN_WORKERS[i]);
        System.out.println("[sleep/opt] parallel render: " + Thread.currentThread().getName() + " pinned to mask " + Long.toHexString(PIN_WORKERS[i]) + " " + ok);
    }

    /** Test only (-Dsleep.test.skipDraw=ItemEntity,ExperienceOrb): entities of these classes are not drawn (a ceiling). */
    private static final java.util.Set<String> TEST_SKIP = java.util.Set.of(System.getProperty("sleep.test.skipDraw", "").split(","));
    private static final boolean TEST_SKIP_ON = !TEST_SKIP.contains("");

    /** LevelRenderer's entity loop, per entity (in place of renderEntity). */
    public static void enqueue(LevelRenderer lr, Entity e, double camX, double camY, double camZ, float pt, PoseStack pose, MultiBufferSource buffers) {
        if (TEST_SKIP_ON && TEST_SKIP.contains(e.getClass().getSimpleName())) return;
        if (size == 0) {
            if (RENDER_PIN != 0 && !renderPinned) {
                renderPinned = true;
                System.out.println("[sleep/opt] parallel render: render thread pinned to mask " + Long.toHexString(RENDER_PIN) + " " + dev.lab.runtime.OsThreads.pinCurrent(RENDER_PIN));
            }
            setsChecked = false;
            listened = EventSites.renderListenedMask();   // once per frame
        }
        Job j = size < QUEUE.size() ? QUEUE.get(size) : null;
        if (j == null) {
            j = new Job();
            QUEUE.add(j);
        }
        size++;
        j.entity = e;
        j.camX = camX;
        j.camY = camY;
        j.camZ = camZ;
        j.pt = pt;
        j.pose = pose;
        j.buffers = buffers;
        j.done = false;
        j.ok = false;
        j.parallel = eligible(e, buffers);
        j.claimed.set(!j.parallel);   // set last: a stale pool entry of this reused job may run it only when it is a complete parallel job
        jobs++;
        if (j.parallel) {
            if (!setsChecked) {   // the renderer sets when an entity first goes to the workers: never, when none does
                ensureSets();
                setsChecked = true;
            }
            pool().execute(j);
        }
    }

    /**
     * Whether this frame checked the renderer sets: they are built for the first entity that goes to the workers, not
     * for the first frame - next to mods that keep every entity on the render thread (Oculus, EMF in the dispatcher)
     * they cost a pause when the world opens (16 s in the Biohazard pack: every renderer and model of 473 mods, four
     * times) and memory, for nothing.
     */
    private static boolean setsChecked;

    /**
     * (-Dsleep.parallel.stack=true: a worker's failure printed with where it happened)
     */
    private static final boolean STACK = Boolean.getBoolean("sleep.parallel.stack");

    /**
     * Entity types whose renderer failed on a worker FAIL_LIMIT times: rendered on the render thread from then on
     * (a renderer that touches render-thread-only state fails every time, after doing its work and its side effects
     * on the worker). Learned by observation, for any mod's renderer.
     */
    private static final int FAIL_LIMIT = Integer.getInteger("lab.prender.failLimit", 3);
    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.world.entity.EntityType<?>, java.util.concurrent.atomic.AtomicInteger> FAILS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<net.minecraft.world.entity.EntityType<?>> WORKER_REFUSED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void learnFailure(net.minecraft.world.entity.EntityType<?> type, Throwable t) {
        if (FAILS.computeIfAbsent(type, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet() == FAIL_LIMIT) {
            WORKER_REFUSED.add(type);
            System.out.println("[sleep/opt] parallel render: " + type + " kept on the render thread after " + FAIL_LIMIT + " failures (" + t + ")");
        }
    }

    /**
     * Renderer libraries known to keep per-render state in globals (static caches, shared model objects): their
     * renderers go to workers only when probe's adapter for them is applied (it sets the property). GeckoLib: bones of
     * the cached BakedGeoModel, Molang actor and variables (probe-geo mixins, -Dlab.opt.GeoParallelMixin=true).
     * Without this, a GeckoLib job that no longer fails on a worker races on those globals.
     */
    private static final String[][] SHARED_STATE_LIBS = {{"software.bernie.geckolib.renderer.GeoRenderer", "sleep.adapter.geckolib"}};

    private static final ClassValue<Boolean> LIB_UNSAFE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            for (String[] lib : SHARED_STATE_LIBS) if (!Boolean.getBoolean(lib[1]) && implementsNamed(c, lib[0])) return true;
            return false;
        }
    };

    static boolean implementsNamed(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals(name)) return true;
            for (Class<?> i : k.getInterfaces()) if (implementsNamed(i, name)) return true;
        }
        return false;
    }

    /**
     * A job renders the way renderEntity + EntityRenderDispatcher.render do, without calling them: what another mod's
     * mixin adds there (Iris: the entity id its shaders read, its per-entity render type wrap) would be lost on a
     * worker. Such a mod changes them: every entity stays on the render thread.
     */
    static final boolean DISPATCH_UNTOUCHED = !MixinTouch.touched("net/minecraft/client/renderer/entity/EntityRenderDispatcher", "render")
            && !MixinTouch.touched("net/minecraft/client/renderer/LevelRenderer", "renderEntity");

    /**
     * Another mod's mixin in EntityRenderDispatcher.render or LevelRenderer.renderEntity (Iris: the entity id a pack's
     * shaders read, set around the renderer): a job is merged through vanilla's renderEntity instead, one level down -
     * RenderCacheMixin's redirect of render's call to the renderer takes the job's bytes (innerMerge). The worker
     * records the renderer alone; renderEntity and the dispatcher - every injection in them, the flame, the shadow -
     * run on the render thread as vanilla's, and the renderer's bytes are written inside them, where those mods' state
     * is set. Not when either is overwritten or the calls the merge goes through are redirected by another mod.
     * Off unless -Dlab.prender.inner=true (11-06): the mods that wrap the dispatcher keep the entity being drawn in
     * globals that their own mixins in the renderer and its models read and write (Entity Model Features: the
     * animated entity's variables; ETF, Oculus with a shaderpack: the entity's textures and id) - on a worker those are
     * another entity's, a data race (EMF + Fresh Animations crashed the render cache's fingerprint with a
     * ConcurrentModificationException). Such entities stay on the render thread, as in 1.0.3.
     */
    static final boolean INNER = (!DISPATCH_UNTOUCHED || Boolean.getBoolean("lab.prender.forceInner"))
            && (Boolean.getBoolean("lab.prender.inner") || Boolean.getBoolean("lab.prender.forceInner") || autoInner() == null)
            && cacheHooked()
            && !MixinTouch.overwritten("net/minecraft/client/renderer/entity/EntityRenderDispatcher", "render")
            && !MixinTouch.redirected("net/minecraft/client/renderer/entity/EntityRenderDispatcher", "render", "render")
            && !MixinTouch.overwritten("net/minecraft/client/renderer/LevelRenderer", "renderEntity")
            && !MixinTouch.redirected("net/minecraft/client/renderer/LevelRenderer", "renderEntity", "render");


    /** the mods in the dispatcher (for the log) */
    static String dispatchMods;   // (no initialiser: set while INNER, above, is initialised)

    /**
     * The merge inside the dispatcher without a flag (-Dlab.prender.autoInner=false: never): null when no mod with a mixin
     * in EntityRenderDispatcher.render / LevelRenderer.renderEntity also has one in the code a worker runs (workerCode) -
     * then what their injections set around the renderer is not read inside it on a worker (Iris: the entity id is read by
     * its vertex mixins only with a shaderpack; those bytes are checked, CHECK_DENSE). Otherwise why not.
     */
    static String autoInner() {
        if ("false".equals(System.getProperty("lab.prender.autoInner"))) return "off by flag";
        java.util.Set<String> hookers = new java.util.TreeSet<>(MixinTouch.touchers("net/minecraft/client/renderer/entity/EntityRenderDispatcher", "render"));
        hookers.addAll(MixinTouch.touchers("net/minecraft/client/renderer/LevelRenderer", "renderEntity"));
        dispatchMods = hookers.toString();
        if (hookers.contains("?") || hookers.contains("index")) return "the mods in the dispatcher not known";
        // the code a worker job runs for an entity: model parts, models, renderers. A mod with mixins both in the dispatcher
        // and here keeps per-entity state between the two (Entity Model Features: the animated entity in globals its model
        // mixins read) - on a worker that is another entity's, a data race. (A local: INNER, above, is initialised first.)
        String[][] workerCode = {
                {"net/minecraft/client/model/geom/ModelPart", "render"}, {"net/minecraft/client/model/geom/ModelPart", "compile"},
                {"net/minecraft/client/model/geom/ModelPart", "translateAndRotate"},
                {"net/minecraft/client/model/HierarchicalModel", "renderToBuffer"}, {"net/minecraft/client/model/AgeableListModel", "renderToBuffer"},
                {"net/minecraft/client/model/EntityModel", "setupAnim"}, {"net/minecraft/client/model/EntityModel", "prepareMobModel"},
                {"net/minecraft/client/renderer/entity/EntityRenderer", "render"}, {"net/minecraft/client/renderer/entity/LivingEntityRenderer", "render"},
                {"net/minecraft/client/renderer/entity/layers/RenderLayer", "renderColoredCutoutModel"}};
        for (String[] w : workerCode)
            for (String c : MixinTouch.touchers(w[0], w[1])) {
                if (c.equals("?")) return "unknown mixins in " + w[0] + "." + w[1];
                if (hookers.contains(c)) return c + " is in the dispatcher and in " + w[0].substring(w[0].lastIndexOf('/') + 1) + "." + w[1];
            }
        return null;
    }

    /** -Dsleep.clientAuto.ab=dev.lull.optsupport.ParallelRender#innerOff@S: the frame with every entity on the render thread (A/B) */
    static boolean innerOff;

    /** RenderCacheMixin's redirect is in the dispatcher (not given way, its flag on): its handler is among the class' methods */
    private static boolean cacheHooked() {
        for (java.lang.reflect.Method m : EntityRenderDispatcher.class.getDeclaredMethods()) if (m.getName().endsWith("sleep$cached")) return true;
        return false;
    }

    static {
        if (!DISPATCH_UNTOUCHED)
            System.out.println("[sleep/opt] parallel render: another mod's mixin in EntityRenderDispatcher.render / LevelRenderer.renderEntity; merged inside them: "
                    + INNER + " (render cache " + cacheHooked() + "; mods there " + dispatchMods + (INNER ? "" : "; not merged inside: " + autoInner()) + ")");
    }

    /**
     * A burning entity's flame drawn by the worker, where render draws it (after the renderer, before the offset is
     * undone): renderFlame reads the atlas' fire sprites and the rotation made from the camera's orientation, both fixed
     * while the frame's entities are drawn. Burning mobs (zombies at noon, a fight with fire) went to the render thread
     * before - in a horde nearly all of them. A mod's mixin in renderFlame keeps them there (-Dlab.prender.flame=false: always).
     */
    // On by default (the user, 11-11: a few pixels are acceptable here). An in-frame A/B of a burning horde (hundreds of
    // zombies) shows 7-10 of 921 600 pixels where a flame and a body meet at equal depth (the burning entity's draws are
    // ordered against the flames' batch otherwise than vanilla's); next to Iris without shaders the horde runs 1012-1051
    // fps with them on workers, 804 without. -Dlab.prender.flame=false: burning entities on the render thread (exact).
    static final boolean FLAME_ON_WORKERS = !"false".equals(System.getProperty("lab.prender.flame"))
            && !MixinTouch.touched("net/minecraft/client/renderer/entity/EntityRenderDispatcher", "renderFlame");

    /** -Dlab.prender.why=true: why entities stayed on the render thread (counts in the report) */
    private static final boolean WHY = Boolean.getBoolean("lab.prender.why");
    static final long[] WHY_N = new long[8];
    private static final String[] WHY_NAMES = {"dispatch touched by a mod", "shaderpack", "type refused", "library renderer", "other buffers / player / name / fire / hitboxes",
            "render event listened", "excluded (Compat)", "cached by the render cache"};

    private static boolean no(int why) {
        if (WHY) WHY_N[why]++;
        return false;
    }

    private static boolean eligible(Entity e, MultiBufferSource buffers) {
        // a shaderpack: the ids its vertices carry come from render-thread state (ShaderIds)
        if (innerOff || !DISPATCH_UNTOUCHED && !INNER) return no(0);
        if (dev.lull.shaders.ShaderPipeline.active) return no(1);
        if (!WORKER_REFUSED.isEmpty() && WORKER_REFUSED.contains(e.getType())) return no(2);
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher d = mc.getEntityRenderDispatcher();
        if (LIB_UNSAFE.get(d.getRenderer(e).getClass())) return no(3);
        if (buffers != mc.renderBuffers().bufferSource() || e instanceof AbstractClientPlayer || e.hasCustomName() || e.displayFireAnimation() && !FLAME_ON_WORKERS && !INNER
                || d.shouldRenderHitBoxes()) return no(4);
        if (listened != 0 && (anyListener || EventSites.postsListened(d.getRenderer(e).getClass(), listened))) return no(5);   // its renderer posts a listened event
        if (dev.lull.api.Compat.parallelRenderExcluded(e)) return no(6);
        if (!(!Boolean.getBoolean("lab.opt.RenderCacheMixin") || RenderCache.animated(e) || dev.lull.api.Compat.renderCacheExcluded(e))) return no(7);
        // with the render cache on: what it draws the vanilla way (animated, or excluded from it by Compat)
        return true;
    }



    /** After the loop: every job in order. */
    /** the job whose bytes the dispatcher's renderer call takes (INNER), and whether it took them */
    private static Job innerArmed;
    private static boolean merged;
    public static long mergeFallbacks;

    /**
     * A job is rendered on a worker before the render thread reaches the call it is merged into; what another mod's
     * injection around that call sets for the renderer to read (Entity Model Features' animated entity in static fields,
     * a shaderpack's entity id in Iris / Oculus) is not set on the worker. So the first CHECK_FIRST jobs of each entity
     * type, and one in CHECK_EVERY after, are rendered once more at the merge, the vanilla way, inside that call, and
     * compared byte for byte: a difference puts the vanilla bytes in the frame and, the CHECK_LIMIT-th time, keeps the
     * type on the render thread. Learned by observation, for any mod. -Dlab.prender.check=false: off.
     */
    private static final boolean CHECK = !"false".equals(System.getProperty("lab.prender.check"));
    private static final int CHECK_FIRST = Integer.getInteger("lab.prender.checkFirst", 8);
    private static final int CHECK_EVERY = Integer.getInteger("lab.prender.checkEvery", 512);
    private static final int CHECK_LIMIT = 2;
    /** merged inside another mod's dispatcher hooks: one job in 16 checked (their per-entity state may reach the bytes) */
    private static final boolean CHECK_DENSE = !DISPATCH_UNTOUCHED;
    /** render thread: per entity type {jobs merged, mismatches}; counted again after a resource reload */
    private static final java.util.IdentityHashMap<net.minecraft.world.entity.EntityType<?>, int[]> CHECKS = new java.util.IdentityHashMap<>();
    private static long checked, checkMismatches;

    /** Is this merge one of the checked ones? (render thread) */
    private static boolean sampled(Job j) {
        if (!CHECK || j.parts.size() > 0) return false;   // parts: our own path for GeckoLib bones (verify mode checks it)
        int[] c = CHECKS.get(j.entity.getType());
        if (c == null) CHECKS.put(j.entity.getType(), c = new int[2]);
        int n = c[0]++;
        return n < CHECK_FIRST || n % (CHECK_DENSE ? 16 : CHECK_EVERY) == 0;
    }

    /**
     * VERIFY_REC holds the vanilla render made in place: the same bytes - false, the job is taken; others - the vanilla
     * bytes go into the frame instead (the job's side effects dropped: the vanilla render made its own), true.
     */
    private static boolean mismatch(Job j, MultiBufferSource buffers) {
        VERIFY_REC.finish(VERIFY_OUT);
        checked++;
        if (VERIFY_OUT.sameAs(j.out)) return false;
        checkMismatches++;
        net.minecraft.world.entity.EntityType<?> type = j.entity.getType();
        if (++CHECKS.get(type)[1] == CHECK_LIMIT) {
            WORKER_REFUSED.add(type);
            System.out.println("[sleep/opt] parallel render: " + type + " kept on the render thread: rendered inside the call another mod wraps, it differs from the worker's ("
                    + describe(VERIFY_OUT, j.out).split("\n")[0] + ")");
        }
        j.calls.clear();
        j.parts.clear();
        RenderCache.append(VERIFY_OUT, buffers);
        merged = true;
        return true;
    }

    /**
     * RenderCacheMixin, in place of EntityRenderDispatcher.render's renderer call: while a job is merged and the call is
     * the one the worker made (entity, yaw, partial tick, light, buffers and the pose with the offset - an injection
     * before it may have changed them), its parts, side effects and bytes go in here; false: the renderer renders.
     */
    public static boolean innerMerge(Entity e, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        Job j = innerArmed;
        if (j == null || j.entity != e) return false;
        innerArmed = null;
        if (yaw != j.yaw || pt != j.pt || light != j.light || buffers != j.buffers || !pose.last().pose().equals(j.innerPose)
                || !pose.last().normal().equals(j.innerNormal)) {
            mergeFallbacks++;
            j.calls.clear();
            j.parts.clear();
            return false;
        }
        if (sampled(j)) {   // rendered here once more, inside the dispatcher where other mods' state is set: the same bytes?
            VERIFY_REC.reset();
            Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e).render(e, yaw, pt, pose, VERIFY_REC, light);
            if (mismatch(j, buffers)) return true;
        }
        if (j.parts.size() > 0) {
            long t0 = System.nanoTime();
            boolean ok = PartBatch.mergeJob(j.parts, j.buffers);
            mergeNanos += System.nanoTime() - t0;
            if (!ok) {   // a record's type is not a batch here: the object the vanilla way, without the job's side effects
                mergeFallbacks++;
                j.calls.clear();
                return false;
            }
        }
        for (int c = 0; c < j.calls.size(); c++) j.calls.get(c).execute();   // the job's deferred side effects, in place
        journaled += j.calls.size();
        j.calls.clear();
        RenderCache.append(j.out, buffers);
        merged = true;
        return true;
    }

    public static void drain(LevelRenderer lr) {
        LevelRendererInvoker inv = (LevelRendererInvoker) lr;
        for (int i = 0; i < size; i++) {
            Job j = QUEUE.get(i);
            boolean clean = j.pose.clear();   // (PoseStack.clear: only the base pose left)
            if (j.parallel) {
                // helping instead of waiting: the queued jobs from here on that no worker has taken yet
                for (int k = i; !j.done && k < size; k++) {
                    Job h = QUEUE.get(k);
                    if (h.parallel && !h.done) {
                        h.run();
                        if (h.done) helped++;
                    }
                }
                j.await();
                if (j.ok && INNER) {   // through vanilla's renderEntity: the dispatcher's renderer call takes the job (innerMerge)
                    if (VERIFY) verify(inv, j);
                    innerArmed = j;
                    merged = false;
                    try {
                        inv.sleep$renderEntity(j.entity, j.camX, j.camY, j.camZ, j.pt, j.pose, j.buffers);
                    } finally {
                        innerArmed = null;
                    }
                    if (merged) parallel++;
                    else sequential++;
                    if (clean) leakCheck(j);
                    j.entity = null;
                    continue;
                }
                if (j.ok && j.parts.size() > 0) {
                    long t0 = System.nanoTime();
                    boolean merged = PartBatch.mergeJob(j.parts, j.buffers);
                    mergeNanos += System.nanoTime() - t0;
                    if (!merged) {
                        j.ok = false;   // a record's type is not a batch here: the object the vanilla way, without the job's side effects
                        j.calls.clear();
                    }
                }
                if (j.ok) {
                    if (VERIFY) verify(inv, j);
                    for (int c = 0; c < j.calls.size(); c++) j.calls.get(c).execute();   // the job's deferred side effects, in place
                    journaled += j.calls.size();
                    j.calls.clear();
                    RenderCache.append(j.out, j.buffers);
                    parallel++;
                    if (clean) leakCheck(j);
                    j.entity = null;
                    continue;
                }
            }
            inv.sleep$renderEntity(j.entity, j.camX, j.camY, j.camZ, j.pt, j.pose, j.buffers);
            sequential++;
            if (clean) leakCheck(j);
            j.entity = null;
        }
        size = 0;
    }

    /** entity types whose rendering left a pose pushed (each named once) */
    private static final java.util.Set<net.minecraft.world.entity.EntityType<?>> LEAKED = new java.util.HashSet<>();

    /**
     * The frame's pose stack was at its base before this entity and is not after it: its rendering pushed a pose and
     * did not pop it - vanilla's check after the entity loop then stops the game with "Pose stack not empty" (GitHub
     * #5), which does not say who. Named here, with the renderer's class and the module (mod) it comes from; nothing
     * is changed (the stack stays as the renderer left it, as without Sleepium).
     */
    private static void leakCheck(Job j) {
        if (j.pose.clear() || j.entity == null || !LEAKED.add(j.entity.getType())) return;
        Class<?> r = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(j.entity).getClass();
        Module m = r.getModule();
        System.out.println("[sleep/opt] pose stack: rendering " + net.minecraft.world.entity.EntityType.getKey(j.entity.getType()) + " (" + j.entity.getClass().getName()
                + ", renderer " + r.getName() + (m.isNamed() ? ", module " + m.getName() : "") + ") left a pose pushed - a following \"Pose stack not empty\" crash comes from it"
                + mixinsIn(r));
    }

    /**
     * Other mods' mixins in a renderer's class and its superclasses (where a mod's change to a vanilla renderer is): the
     * handler methods Mixin merged into them carry @MixinMerged with the mixin's name.
     */
    private static String mixinsIn(Class<?> c) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        try {
            for (; c != null && c != Object.class; c = c.getSuperclass())
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    var mm = m.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
                    if (mm != null && !mm.mixin().startsWith("dev.lull.")) found.add(mm.mixin());
                }
        } catch (Throwable t) {
            return "; (mixins: " + t + ")";
        }
        return found.isEmpty() ? "" : "; other mods' mixins in the renderer's classes: " + String.join(", ", found);
    }

    private static final RenderCache.Recorder VERIFY_REC = new RenderCache.Recorder();
    private static final RenderCache.Rec VERIFY_OUT = new RenderCache.Rec();
    /** verify mode: mismatches per entity type (in the report) */
    private static final java.util.Map<String, Long> MISMATCH_TYPES = new java.util.TreeMap<>();

    /** The same entity rendered on the render thread with the vanilla renderers, compared byte for byte. */
    private static void verify(LevelRendererInvoker inv, Job j) {
        VERIFY_REC.reset();
        if (INNER) {   // the job holds the renderer's output alone: the render thread's renderer at the same pose
            PoseStack ps = new PoseStack();
            ps.last().pose().set(j.innerPose);
            ps.last().normal().set(j.innerNormal);
            Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(j.entity).render(j.entity, j.yaw, j.pt, ps, VERIFY_REC, j.light);
        } else inv.sleep$renderEntity(j.entity, j.camX, j.camY, j.camZ, j.pt, new PoseStack(), VERIFY_REC);
        VERIFY_REC.finish(VERIFY_OUT);
        verified++;
        if (!VERIFY_OUT.sameAs(j.out)) {
            MISMATCH_TYPES.merge(j.entity.getType().toShortString(), 1L, Long::sum);
            if (mismatches++ < Integer.getInteger("lab.prender.verifyPrint", 5)) System.out.println("[sleep/opt] parallel render: mismatch for " + j.entity.getType() + ": " + describe(VERIFY_OUT, j.out));
        }
    }

    private static String describe(RenderCache.Rec a, RenderCache.Rec b) {
        StringBuilder sb = new StringBuilder("render thread " + a.types.size() + " types / " + a.size + " bytes, worker " + b.types.size() + " types / " + b.size + " bytes");
        for (int i = 0; i < Math.max(a.types.size(), b.types.size()); i++)
            sb.append("\n    ").append(i < a.types.size() ? a.types.get(i).toString().substring(0, Math.min(60, a.types.get(i).toString().length())) + " x" + a.counts[i] : "-")
                    .append(" | ").append(i < b.types.size() ? b.types.get(i).toString().substring(0, Math.min(60, b.types.get(i).toString().length())) + " x" + b.counts[i] : "-");
        int n = Math.min(a.size, b.size);
        for (int i = 0; i < n; i++) {
            byte x = org.lwjgl.system.MemoryUtil.memGetByte(a.mem + i), y = org.lwjgl.system.MemoryUtil.memGetByte(b.mem + i);
            if (x != y) {
                sb.append("\n    first difference at byte ").append(i).append(": ").append(x).append(" vs ").append(y);
                break;
            }
        }
        return sb.toString();
    }

    /** renderEntity + EntityRenderDispatcher.render (without hitboxes), with a worker's renderers. */
    private static boolean render(Set s, Job j) {
        Entity e = j.entity;
        long coat = Coatings.of(e);
        j.parts.coat = Coatings.word0(coat);
        j.parts.coat2 = Coatings.word1(coat);
        j.parts.height = Coatings.word2(e, coat);   // (the height and the blows' marks)
        float pt = j.pt;
        EntityRenderer<? super Entity> r = s.renderer(e);
        if (r == null) return false;
        double x = Mth.lerp((double) pt, e.xOld, e.getX()) - j.camX;
        double y = Mth.lerp((double) pt, e.yOld, e.getY()) - j.camY;
        double z = Mth.lerp((double) pt, e.zOld, e.getZ()) - j.camZ;
        float yaw = Mth.lerp(pt, e.yRotO, e.getYRot());
        int light = r.getPackedLightCoords(e, pt);
        RenderCache.Recorder rec = s.recorder;
        rec.reset();
        PoseStack pose = s.pose;   // one per renderer set (a set serves one job at a time): its pose pool persists
        pose.setIdentity();
        Vec3 off = r.getRenderOffset(e, pt);
        pose.pushPose();
        pose.translate(x + off.x(), y + off.y(), z + off.z());
        if (INNER) {   // the renderer alone: the dispatcher's flame and shadow are drawn by the dispatcher at the merge
            j.yaw = yaw;
            j.light = light;
            j.innerPose.set(pose.last().pose());
            j.innerNormal.set(pose.last().normal());
            r.render(e, yaw, pt, pose, rec, light);
            pose.popPose();
            if (rec.unsupported) return false;
            rec.finish(j.out);
            return true;
        }
        r.render(e, yaw, pt, pose, rec, light);
        EntityRenderDispatcher d = Minecraft.getInstance().getEntityRenderDispatcher();
        if (e.displayFireAnimation()) {
            EntityRenderDispatcherInvoker di = (EntityRenderDispatcherInvoker) d;
            di.sleep$renderFlame(pose, rec, e, Mth.rotationAroundAxis(Mth.Y_AXIS, di.sleep$cameraOrientation(), new org.joml.Quaternionf()));
        }
        pose.translate(-off.x(), -off.y(), -off.z());
        if (d.options.entityShadows().get() && ((EntityRenderDispatcherInvoker) d).sleep$shouldRenderShadow() && !e.isInvisible()) {
            float f = ((dev.lull.opt.EntityRendererAccessor) r).sleep$shadowRadius(e);
            if (f > 0.0F) {
                double d1 = d.distanceToSqr(e.getX(), e.getY(), e.getZ());
                float f1 = (float) ((1.0 - d1 / 256.0) * (double) ((dev.lull.opt.EntityRendererAccessor) r).sleep$shadowStrength());
                if (f1 > 0.0F) EntityRenderDispatcherInvoker.sleep$renderShadow(pose, rec, e, f1, pt, e.level(), Math.min(f, 32.0F));
            }
        }
        pose.popPose();
        if (rec.unsupported) return false;
        rec.finish(j.out);
        return true;
    }

    public static String report() {
        if (WHY) {
            StringBuilder w = new StringBuilder("parallel render, why on the render thread:");
            for (int i = 0; i < WHY_N.length; i++) if (WHY_N[i] > 0) w.append(' ').append(WHY_NAMES[i]).append(' ').append(WHY_N[i]).append(';');
            System.out.println(w);
        }
        return String.format(java.util.Locale.ROOT, "parallel render: %d workers, jobs %d, on workers %d (render thread helped %d), on the render thread %d, failed %d, refused %d, journaled calls %d, part records merged in %.0f ms%s", THREADS, jobs, parallel,
                helped, sequential, failed, refused, journaled, mergeNanos / 1e6, (WORKER_REFUSED.isEmpty() ? "" : ", kept on the render thread " + WORKER_REFUSED) + (CHECK ? ", checked in place " + checked + (checkMismatches > 0 ? " (differed " + checkMismatches + ")" : "") : "") + (VERIFY ? ", verified " + verified + ", mismatches " + mismatches + (MISMATCH_TYPES.isEmpty() ? "" : " " + MISMATCH_TYPES) : ""));
    }
}
