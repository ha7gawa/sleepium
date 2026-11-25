package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.KineticDebugger;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Stage B of Create on our renderer (opt-in {@code -Dlab.opt.KineticResidentMixin}, with KineticMemoMixin): a kinetic
 * block entity whose memo replays becomes resident - its parts are written once to VRAM (PartBatch's persistent records:
 * the pose relative to an anchor, the spin as axis, speed and angle offset), and from then on renderLevel's section loop
 * only queues their draw commands: no dispatcher, no light lookup, no frustum test, no matrices on the CPU. The shader
 * moves the anchor to the camera and turns each part for Create's render time (the angle as getAngleForBe computes it).
 *
 * <p>It stays resident while what the memo is valid for stays the same: the block state, the speed, the overstress tint
 * (compared every frame) and the light, which changes only with the section's blocks or light - every such change marks
 * the render section dirty, and its epoch (ChunkSectionMixin) is compared instead of looking the light up. Anything else
 * (another section, removal, the kinetic debugger, a far camera) drops it: the block entity goes the usual way, through
 * the memo, and becomes resident again. Out of the renderer's view distance it is skipped as shouldRender does; a block
 * entity being broken (crumbling) is drawn the usual way that frame. Off-screen parts of visible sections are drawn
 * (the GPU clips them) where vanilla culls by the block entity's box: the same pixels.
 */
public final class KineticResident implements NoAllocChecks.Resident {
    public static final boolean ON = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.KineticResidentMixin");
    public static final KineticResident INSTANCE = new KineticResident();
    public static long made, dropped, drawnBe, notMade, relit;
    /** drops by cause: removed / debugger, state, speed, overstress tint, data read, other section, section dirtied */
    public static final long[] DROP = new long[8];
    /** offers refused by cause: pose / section, renderer, light section, type not batched */
    public static final long[] NOT_MADE = new long[4];

    private KineticResident() {}

    public static void install() {
        NoAllocChecks.RESIDENT = INSTANCE;
        System.out.println("[sleep/opt] kinetic residents installed (Create's rotating parts kept in VRAM, turned on the GPU)");
    }

    static final class Res {
        KineticBlockEntity be;
        BlockState state;
        float speed, effect;
        Object section;
        int epoch, version, light;
        double cx, cy, cz, vd2;
        RenderType[] types;
        Object[] queues;
        /** per part: level of detail (KineticMemo.Part.lod) and the point its distance is taken to */
        byte[] lod;
        double[] mid;
        /** sections whose light is in the geometry besides the block entity's own, and their epochs */
        Object[] xSections;
        int[] xEpochs;
        int[] first, quads, recs;
        int idx;   // in all
    }

    private final List<Res> all = new ArrayList<>();
    private Level level;
    private double camX, camY, camZ;
    private int ax, ay, az;
    private boolean anchored;
    private Object section;
    private Object viewArea;   // LevelRenderer's (ViewAreaInvoker)
    private BlockEntity candidate;
    private Object candSection;
    private int sweep;

    // ------------------------------------------------------------------ frame (renderLevel's head, its section loop)

    /** renderLevel's head: the camera, the anchor's offset to it and Create's render time for the shader. */
    public static void beginFrame(Camera camera, Object viewArea) {
        KineticResident k = INSTANCE;
        k.viewArea = viewArea;
        Level lv = Minecraft.getInstance().level;
        if (lv != k.level) {
            k.dropAll();
            k.level = lv;
        }
        var p = camera.getPosition();
        k.camX = p.x;
        k.camY = p.y;
        k.camZ = p.z;
        if (k.anchored && (Math.abs(k.camX - k.ax) > 4096 || Math.abs(k.camY - k.ay) > 4096 || Math.abs(k.camZ - k.az) > 4096)) k.dropAll();
        if (!k.anchored) {   // the next residents' poses are relative to this point (float precision near the camera)
            k.ax = (int) Math.floor(k.camX);
            k.ay = (int) Math.floor(k.camY);
            k.az = (int) Math.floor(k.camZ);
        }
        PartBatch.persistCamX = (float) (k.ax - k.camX);
        PartBatch.persistCamY = (float) (k.ay - k.camY);
        PartBatch.persistCamZ = (float) (k.az - k.camZ);
        if (lv != null) PartBatch.persistTime = AnimationTickHolder.getRenderTime(lv);
        k.section = null;
        k.candidate = null;
        if (++k.sweep >= 600) {   // residents of block entities removed out of sight (unloaded chunks)
            k.sweep = 0;
            for (int i = k.all.size() - 1; i >= 0; i--) if (k.all.get(i).be.isRemoved()) k.drop(k.all.get(i));
        }
    }

    /** renderLevel's section loop: the section whose block entities follow. */
    public static void section(SectionRenderDispatcher.RenderSection s) {
        INSTANCE.section = s;
    }

    /** The view area's section at a position (null if none). */
    private Object sectionAt(BlockPos p) {
        Object va = viewArea;
        return va == null ? null : ((dev.lull.catmixin.ViewAreaInvoker) va).sleep$sectionAt(p);
    }

    /** The loop's section when it holds the block entity, else (a global block entity) the view area's. */
    private Object sectionOf(BlockEntity be) {
        BlockPos p = be.getBlockPos();
        if (section != null) {
            BlockPos o = ((SectionRenderDispatcher.RenderSection) section).getOrigin();
            if (p.getX() >> 4 == o.getX() >> 4 && p.getY() >> 4 == o.getY() >> 4 && p.getZ() >> 4 == o.getZ() >> 4) return section;
        }
        Object va = viewArea;
        return va == null ? null : ((dev.lull.catmixin.ViewAreaInvoker) va).sleep$sectionAt(p);
    }

    private static int epoch(Object s) {
        return ((ChunkMultiDraw.ChunkEntries) s).sleep$epoch();
    }

    @Override
    public boolean drawn(BlockEntity be) {
        candidate = null;
        if (!(be instanceof KineticBlockEntity k)) return false;
        Res r = (Res) ((KineticMemo.Effects) k).sleep$resident();
        Object sec = sectionOf(be);
        if (r == null) {
            candidate = be;
            candSection = sec;
            return false;
        }
        int why = KineticDebugger.isActive() || k.isRemoved() ? 0 : k.getBlockState() != r.state ? 1 : k.getSpeed() != r.speed ? 2
                : ((KineticMemo.Effects) k).sleep$overStressed() != r.effect ? 3 : ((KineticMemo.Effects) k).sleep$version() != r.version ? 4
                : r.section != sec ? 5 : epoch(sec) != r.epoch ? 6 : xChanged(r) ? 7 : -1;
        if (why == 6 && net.minecraft.client.renderer.LevelRenderer.getLightColor(k.getLevel(), k.getBlockPos()) == r.light) {
            r.epoch = epoch(sec);   // the section changed, the light the memo is valid for did not (the state was compared)
            relit++;
            why = -1;
        }
        if (why >= 0) {
            DROP[why]++;
            drop(r);
            candidate = be;
            candSection = sec;
            return false;
        }
        if (Crumbling.at(be.getBlockPos())) return false;   // the usual way (the crumbling overlay)
        double dx = r.cx - camX, dy = r.cy - camY, dz = r.cz - camZ;
        if (dx * dx + dy * dy + dz * dz >= r.vd2) return true;   // shouldRender: not drawn
        emit(r);
        drawnBe++;
        return true;
    }

    private static boolean xChanged(Res r) {
        if (r.xSections == null) return false;
        for (int i = 0; i < r.xSections.length; i++) if (epoch(r.xSections[i]) != r.xEpochs[i]) return true;
        return false;
    }

    private static final double MIP2 = 48.0 * 48.0;   // ChainConveyorRenderer.MIP_DISTANCE

    /** The resident's draw commands into its types' queues for this frame. */
    private static void emit(Res r) {
        KineticResident k = INSTANCE;
        for (int i = 0; i < r.recs.length; i++) {
            if (r.lod != null && r.lod[i] != 0) {   // near (closerThan the middle by MIP_DISTANCE) or far
                double dx = r.mid[3 * i] - k.camX, dy = r.mid[3 * i + 1] - k.camY, dz = r.mid[3 * i + 2] - k.camZ;
                boolean near = dx * dx + dy * dy + dz * dz < MIP2;
                if (near != (r.lod[i] == 1)) continue;
            }
            Object q = r.queues[i];
            if (PartBatch.persistFirst(q)) {   // the type's batch so far has no command: it must be drawn
                BufferBuilder bb = PartBatch.sbbBuilder(Minecraft.getInstance().renderBuffers().bufferSource(), r.types[i]);
                if (bb == null) continue;
                PartBatch.persistBatch(bb);
            }
            PartBatch.queuePersistent(q, r.first[i], r.quads[i], r.recs[i]);
        }
    }

    // ------------------------------------------------------------------ making and dropping

    private static final ClassValue<Boolean> PLAIN_SHOULD_RENDER = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            try {
                return dev.lull.Names.publicMethod(c, "shouldRender", BlockEntity.class, net.minecraft.world.phys.Vec3.class).getDeclaringClass() == BlockEntityRenderer.class;
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
    };

    private static final Matrix4f M = new Matrix4f();
    private static final Matrix3f N = new Matrix3f();

    /**
     * KineticMemo with a valid memo: the block entity renderLevel's loop just offered becomes resident when its renderer
     * keeps the default shouldRender and the dispatcher's pose is the loop's plain translation to it; true: it is, and
     * drawn this frame.
     */
    static boolean offer(KineticMemo.Memo m, KineticBlockEntity be, PoseStack.Pose disp, Class<?> rendererClass) {
        KineticResident k = INSTANCE;
        if (be != k.candidate || !(k.candSection instanceof ChunkMultiDraw.ChunkEntries)) return false;
        k.candidate = null;
        BlockPos pos = be.getBlockPos();
        BlockPos o = ((SectionRenderDispatcher.RenderSection) k.candSection).getOrigin();
        Matrix4f d = disp.pose();
        if (pos.getX() >> 4 != o.getX() >> 4 || pos.getY() >> 4 != o.getY() >> 4 || pos.getZ() >> 4 != o.getZ() >> 4
                || (d.properties() & Matrix4f.PROPERTY_TRANSLATION) == 0
                || Math.abs(d.m30() - (float) (pos.getX() - k.camX)) > 1e-3f || Math.abs(d.m31() - (float) (pos.getY() - k.camY)) > 1e-3f
                || Math.abs(d.m32() - (float) (pos.getZ() - k.camZ)) > 1e-3f || !PLAIN_SHOULD_RENDER.get(rendererClass)) {
            notMade++;
            NOT_MADE[0]++;
            return false;
        }
        BlockEntityRenderer<?> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer == null || renderer.getClass() != rendererClass) {
            notMade++;
            NOT_MADE[1]++;
            return false;
        }
        k.anchored = true;
        int n = m.parts.size();
        Res r = new Res();
        r.be = be;
        r.state = m.state;
        r.speed = m.speed;
        r.effect = m.effect;
        r.version = m.version;
        r.light = m.light;
        r.section = k.candSection;
        r.epoch = epoch(k.candSection);
        r.cx = pos.getX() + 0.5;
        r.cy = pos.getY() + 0.5;
        r.cz = pos.getZ() + 0.5;
        double vd = renderer.getViewDistance();
        r.vd2 = vd * vd;
        r.types = new RenderType[n];
        r.queues = new Object[n];
        java.util.ArrayList<Object> xs = null;
        for (int i = 0; i < n; i++) {
            KineticMemo.Part p = m.parts.get(i);
            if (p.lod != 0) {
                if (r.lod == null) {
                    r.lod = new byte[n];
                    r.mid = new double[3 * n];
                }
                r.lod[i] = p.lod;
                r.mid[3 * i] = p.mx;
                r.mid[3 * i + 1] = p.my;
                r.mid[3 * i + 2] = p.mz;
            }
            if (p.lightAt != null) {
                Object s = k.sectionAt(p.lightAt);
                if (!(s instanceof ChunkMultiDraw.ChunkEntries)) {
                    notMade++;
            NOT_MADE[2]++;
                    return false;
                }
                if (xs == null) xs = new java.util.ArrayList<>();
                if (!xs.contains(s)) xs.add(s);
            }
            if (PartBatch.sbbBuilder(Minecraft.getInstance().renderBuffers().bufferSource(), p.type) == null) {   // drawn after a batch of its type
                notMade++;
            NOT_MADE[3]++;
                return false;
            }
        }
        if (xs != null) {
            r.xSections = xs.toArray();
            r.xEpochs = new int[r.xSections.length];
            for (int i = 0; i < r.xEpochs.length; i++) r.xEpochs[i] = epoch(r.xSections[i]);
        }
        r.first = new int[n];
        r.quads = new int[n];
        r.recs = new int[n];
        for (int i = 0; i < n; i++) {
            KineticMemo.Part p = m.parts.get(i);
            M.translation(pos.getX() - k.ax, pos.getY() - k.ay, pos.getZ() - k.az).mul(p.rel).mul(p.local);
            N.set(p.nRel).mul(p.nLocal);
            int rec = PartBatch.persistAlloc();
            int axis = p.axis == null ? 0 : p.axis.getAxis().ordinal() + 1;
            PartBatch.persistWrite(rec, M, N, p.r, p.g, p.b, p.a, p.diffuse, p.fakeY, p.light, p.overlay, axis, p.speed, p.offsetDeg,
                    p.sprites, p.scrollU, p.scrollSpeed, p.scrollPhase, p.scrollScale);
            r.types[i] = p.type;
            r.queues[i] = PartBatch.persistQueue(p.type);
            r.first[i] = p.geo[0];
            r.quads[i] = p.geo[1];
            r.recs[i] = rec;
        }
        ((KineticMemo.Effects) be).sleep$resident(r);
        r.idx = k.all.size();
        k.all.add(r);
        made++;
        emit(r);
        drawnBe++;
        return true;
    }

    private void drop(Res r) {
        for (int rec : r.recs) PartBatch.persistFree(rec);
        ((KineticMemo.Effects) r.be).sleep$resident(null);
        Res tail = all.remove(all.size() - 1);
        if (tail != r) {
            all.set(r.idx, tail);
            tail.idx = r.idx;
        }
        dropped++;
    }

    private void dropAll() {
        for (int i = all.size() - 1; i >= 0; i--) drop(all.get(i));
        anchored = false;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "kinetic residents: %d (made %d, dropped %d %s, not made %d %s, kept after a section change %d), drawn %d", INSTANCE.all.size(), made, dropped,
                java.util.Arrays.toString(DROP), notMade, java.util.Arrays.toString(NOT_MADE), relit, drawnBe);
    }

    /** renderLevel's destructionProgress (a block being broken draws its crumbling overlay through the usual path). */
    public static final class Crumbling {
        public static it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> progress;

        static boolean at(BlockPos pos) {
            var p = progress;
            return p != null && !p.isEmpty() && p.containsKey(pos.asLong());
        }
    }
}
