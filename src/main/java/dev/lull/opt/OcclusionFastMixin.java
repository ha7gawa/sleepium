package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.OcclusionFast;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * OcclusionFast: vanilla's runUpdates, line for line, with the ray test's pass and the neighbour chunks' check kept for
 * the walk (see OcclusionFast). Off: vanilla's own method.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class OcclusionFastMixin {
    @Shadow @Final private static Direction[] DIRECTIONS;
    @Shadow @Final private static double CEILED_SECTION_DIAGONAL;
    @Shadow private ViewArea viewArea;

    @Shadow
    private SectionRenderDispatcher.RenderSection getRelativeFrom(BlockPos pos, SectionRenderDispatcher.RenderSection section, Direction direction) {
        throw new AssertionError();
    }

    @Shadow
    private boolean isInViewDistance(BlockPos pos, BlockPos origin) {
        throw new AssertionError();
    }

    @org.spongepowered.asm.mixin.Unique
    private long sleep$nextVerifyWalk;

    @Shadow
    private void initializeQueueForFullUpdate(net.minecraft.client.Camera camera, Queue<SectionOcclusionGraph.Node> nodeQueue) {
        throw new AssertionError();
    }

    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<Boolean> sleep$vanilla = new ThreadLocal<>();

    /**
     * -Dlab.occlusionFast.verifyEvery=S: every S seconds, on the render thread (where chunks arrive and meshes are put in
     * place: nothing changes between the two), a full graph from the camera made both ways and compared - race-free.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "update", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void sleep$verifyWalks(boolean smartCull, net.minecraft.client.Camera camera, net.minecraft.client.renderer.culling.Frustum frustum,
                                   List<SectionRenderDispatcher.RenderSection> list, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (OcclusionFast.VERIFY_EVERY <= 0 || !OcclusionFast.ON || viewArea == null) return;
        long now = System.nanoTime();
        if (now < sleep$nextVerifyWalk) return;
        sleep$nextVerifyWalk = now + OcclusionFast.VERIFY_EVERY * 1_000_000_000L;
        Vec3 cam = camera.getPosition();
        SectionOcclusionGraph.GraphStorage a = new SectionOcclusionGraph.GraphStorage(viewArea.sections.length), b = new SectionOcclusionGraph.GraphStorage(viewArea.sections.length);
        java.util.ArrayDeque<SectionOcclusionGraph.Node> qa = new java.util.ArrayDeque<>(), qb = new java.util.ArrayDeque<>();
        initializeQueueForFullUpdate(camera, qa);
        initializeQueueForFullUpdate(camera, qb);
        qa.forEach(n -> a.sectionToNodeMap.put(n.section, n));
        qb.forEach(n -> b.sectionToNodeMap.put(n.section, n));
        long t0 = System.nanoTime();
        if (OcclusionFast.VERIFY_SELF) {   // the control: vanilla's walk against itself (what changes between two walks)
            sleep$vanilla.set(Boolean.TRUE);
            try {
                runUpdates(a, cam, qa, smartCull, s -> {});
            } finally {
                sleep$vanilla.remove();
            }
        } else {
            walk(a, cam, qa, smartCull, s -> {});
        }
        long t1 = System.nanoTime();
        sleep$vanilla.set(Boolean.TRUE);
        try {
            runUpdates(b, cam, qb, smartCull, s -> {});
        } finally {
            sleep$vanilla.remove();
        }
        long t2 = System.nanoTime();
        OcclusionFast.SYNC.incrementAndGet();
        OcclusionFast.SYNC_OURS.addAndGet(t1 - t0);
        OcclusionFast.SYNC_VANILLA.addAndGet(t2 - t1);
        OcclusionFast.SYNC_SECTIONS.addAndGet(a.renderSections.size());
        String diff = compare(a, b);
        if (diff != null && OcclusionFast.SYNC_DIFF.incrementAndGet() <= 4) OcclusionFast.DIFFS.add("sync: " + diff);
    }

    @Shadow
    private void runUpdates(SectionOcclusionGraph.GraphStorage storage, Vec3 camera, Queue<SectionOcclusionGraph.Node> queue, boolean smartCull,
                            Consumer<SectionRenderDispatcher.RenderSection> sections) {
        throw new AssertionError();
    }

    @WrapMethod(method = "runUpdates")
    private void sleep$runUpdates(SectionOcclusionGraph.GraphStorage storage, Vec3 camera, Queue<SectionOcclusionGraph.Node> queue, boolean smartCull,
                                  Consumer<SectionRenderDispatcher.RenderSection> sections, Operation<Void> original) {
        if (!OcclusionFast.ON || viewArea == null || sleep$vanilla.get() != null) {
            original.call(storage, camera, queue, smartCull, sections);
            return;
        }
        boolean full = storage.renderSections.isEmpty();
        long t0 = System.nanoTime();
        // verify: the full walk's start (the queue's nodes, already in the map) copied for vanilla's walk on a twin storage
        List<SectionOcclusionGraph.Node> start = OcclusionFast.VERIFY && full ? new ArrayList<>(queue) : null;
        List<byte[]> startState = null;
        if (start != null) {
            startState = new ArrayList<>();
            for (SectionOcclusionGraph.Node n : start) startState.add(new byte[] {n.sourceDirections, n.directions});
        }
        walk(storage, camera, queue, smartCull, sections);
        long dt = System.nanoTime() - t0;
        OcclusionFast.WALKS.incrementAndGet();
        OcclusionFast.NANOS.addAndGet(dt);
        if (full) {
            OcclusionFast.FULL.incrementAndGet();
            OcclusionFast.FULL_NANOS.addAndGet(dt);
        }
        if (start != null) {
            SectionOcclusionGraph.GraphStorage twin = new SectionOcclusionGraph.GraphStorage(viewArea.sections.length);
            java.util.ArrayDeque<SectionOcclusionGraph.Node> q2 = new java.util.ArrayDeque<>();
            for (int i = 0; i < start.size(); i++) {
                SectionOcclusionGraph.Node o = start.get(i);
                SectionOcclusionGraph.Node c = new SectionOcclusionGraph.Node(o.section, null, o.step);
                c.sourceDirections = startState.get(i)[0];
                c.directions = startState.get(i)[1];
                q2.add(c);
                twin.sectionToNodeMap.put(c.section, c);
            }
            long t1 = System.nanoTime();
            original.call(twin, camera, q2, smartCull, (Consumer<SectionRenderDispatcher.RenderSection>) s -> {});
            OcclusionFast.VANILLA_NANOS.addAndGet(System.nanoTime() - t1);
            OcclusionFast.VERIFIED.incrementAndGet();
            String diff = compare(storage, twin);
            if (diff != null) {
                if (OcclusionFast.MISMATCHES.incrementAndGet() <= 4) OcclusionFast.DIFFS.add(diff);
            }
        }
    }

    private String compare(SectionOcclusionGraph.GraphStorage a, SectionOcclusionGraph.GraphStorage b) {
        if (a.renderSections.size() != b.renderSections.size()) return "set sizes " + a.renderSections.size() + " / " + b.renderSections.size();
        Iterator<SectionOcclusionGraph.Node> ia = a.renderSections.iterator(), ib = b.renderSections.iterator();
        int i = 0;
        while (ia.hasNext()) {
            SectionOcclusionGraph.Node x = ia.next(), y = ib.next();
            if (x.section != y.section) return "set order at " + i;
            if (x.sourceDirections != y.sourceDirections || x.directions != y.directions || x.step != y.step)
                return "node " + i + " (" + x.section.getOrigin() + "): source " + x.sourceDirections + "/" + y.sourceDirections + ", dirs " + x.directions + "/" + y.directions + ", step " + x.step + "/" + y.step;
            i++;
        }
        for (SectionRenderDispatcher.RenderSection s : viewArea.sections) {
            SectionOcclusionGraph.Node x = a.sectionToNodeMap.get(s), y = b.sectionToNodeMap.get(s);
            if ((x == null) != (y == null)) return "map entry of " + s.getOrigin() + ": " + (x != null) + "/" + (y != null);
            if (x != null && (x.sourceDirections != y.sourceDirections || x.directions != y.directions || x.step != y.step)) return "map node " + s.getOrigin();
        }
        if (!a.chunksWaitingForNeighbors.keySet().equals(b.chunksWaitingForNeighbors.keySet())) return "chunks waiting " + a.chunksWaitingForNeighbors.size() + "/" + b.chunksWaitingForNeighbors.size();
        for (var e : a.chunksWaitingForNeighbors.long2ObjectEntrySet())
            if (!e.getValue().equals(b.chunksWaitingForNeighbors.get(e.getLongKey()))) return "chunk waiting " + ChunkPos.getX(e.getLongKey()) + "," + ChunkPos.getZ(e.getLongKey());
        return null;
    }

    /** vanilla's runUpdates (1.21.1), with the two kept answers */
    private void walk(SectionOcclusionGraph.GraphStorage graphStorage, Vec3 cameraPosition, Queue<SectionOcclusionGraph.Node> nodeQueue, boolean smartCull,
                      Consumer<SectionRenderDispatcher.RenderSection> sections) {
        OcclusionFast.Memo memo = OcclusionFast.MEMO.get();
        OcclusionViewAreaAccessor va = (OcclusionViewAreaAccessor) viewArea;
        int gx = va.sleep$gridX(), gxy = gx * va.sleep$gridY();
        memo.begin(viewArea.sections.length, gx * va.sleep$gridZ());
        int gen = memo.gen;
        int[] ray = memo.ray;
        long rays = 0, raysKept = 0, cols = 0, colsKept = 0;
        BlockPos blockpos = new BlockPos(Mth.floor(cameraPosition.x / 16.0) * 16, Mth.floor(cameraPosition.y / 16.0) * 16, Mth.floor(cameraPosition.z / 16.0) * 16);
        BlockPos blockpos1 = blockpos.offset(8, 8, 8);
        double camX = cameraPosition.x, camY = cameraPosition.y, camZ = cameraPosition.z;
        LevelHeightAccessor lh = this.viewArea.getLevelHeightAccessor();
        double maxY = lh.getMaxBuildHeight(), minY = lh.getMinBuildHeight();
        int minBuild = lh.getMinBuildHeight(), gy = va.sleep$gridY(), gz = va.sleep$gridZ();
        SectionRenderDispatcher.RenderSection[] secArr = viewArea.sections;
        SectionOcclusionGraph.Node[] nodes = graphStorage.sectionToNodeMap.nodes;
        // a section's index is its place in the view area's array (createSections): the ray reads nodes by it - checked
        boolean direct = secArr.length > 0 && secArr[0].index == 0 && secArr[secArr.length - 1].index == secArr.length - 1
                && secArr[secArr.length / 2].index == secArr.length / 2;
        int viewDist = this.viewArea.getViewDistance();
        int camCX = blockpos.getX() >> 4, camCZ = blockpos.getZ() >> 4;

        while (!nodeQueue.isEmpty()) {
            SectionOcclusionGraph.Node node = nodeQueue.poll();
            SectionRenderDispatcher.RenderSection section = node.section;
            if (graphStorage.renderSections.add(node)) {
                sections.accept(node.section);
            }

            BlockPos so = section.getOrigin();
            int sox = so.getX(), soy = so.getY(), soz = so.getZ();
            boolean flag = Math.abs(section.getOrigin().getX() - blockpos.getX()) > 60
                    || Math.abs(section.getOrigin().getY() - blockpos.getY()) > 60
                    || Math.abs(section.getOrigin().getZ() - blockpos.getZ()) > 60;

            for (Direction direction : DIRECTIONS) {
                // getRelativeFrom inlined: the relative origin is the origin moved 16 along the direction (setOrigin keeps
                // them so), the view distance test is ChunkTrackingView's, the section by the view area's index arithmetic
                int rx = sox + direction.getStepX() * 16, ry = soy + direction.getStepY() * 16, rz = soz + direction.getStepZ() * 16;
                SectionRenderDispatcher.RenderSection section1;
                int ddx = Math.max(0, Math.abs((rx >> 4) - camCX) - 1), ddz = Math.max(0, Math.abs((rz >> 4) - camCZ) - 1);
                long dk = Math.max(0, Math.max(ddx, ddz)), dl = Math.min(ddx, ddz);
                if (!(dl * dl + dk * dk < (long) viewDist * viewDist)) {
                    section1 = null;
                } else if (Mth.abs(blockpos.getY() - ry) > viewDist * 16) {
                    section1 = null;
                } else {
                    int gyi = Math.floorDiv(ry - minBuild, 16);
                    section1 = gyi >= 0 && gyi < gy ? secArr[(Math.floorMod(Math.floorDiv(rz, 16), gz) * gy + gyi) * gx + Math.floorMod(Math.floorDiv(rx, 16), gx)] : null;
                }
                if (section1 != null && (!smartCull || !node.hasDirection(direction.getOpposite()))) {
                    if (smartCull && node.hasSourceDirections()) {
                        SectionRenderDispatcher.CompiledSection compiled = section.getCompiled();
                        boolean flag1 = false;
                        for (int j = 0; j < DIRECTIONS.length; j++) {
                            if (node.hasSourceDirection(j) && compiled.facesCanSeeEachother(DIRECTIONS[j].getOpposite(), direction)) {
                                flag1 = true;
                                break;
                            }
                        }
                        if (!flag1) continue;
                    }

                    if (smartCull && flag) {
                        int key = section1.index >= 0 ? section1.index * 3 + direction.getAxis().ordinal() : -1;
                        if (key >= 0 && key < ray.length && ray[key] == gen) {
                            raysKept++;
                        } else {
                            rays++;
                            BlockPos blockpos2 = section1.getOrigin();
                            BlockPos blockpos3 = blockpos2.offset(
                                    (direction.getAxis() == Direction.Axis.X ? blockpos1.getX() <= blockpos2.getX() : blockpos1.getX() >= blockpos2.getX()) ? 0 : 16,
                                    (direction.getAxis() == Direction.Axis.Y ? blockpos1.getY() <= blockpos2.getY() : blockpos1.getY() >= blockpos2.getY()) ? 0 : 16,
                                    (direction.getAxis() == Direction.Axis.Z ? blockpos1.getZ() <= blockpos2.getZ() : blockpos1.getZ() >= blockpos2.getZ()) ? 0 : 16);
                            // vanilla's Vec3 steps in doubles (subtract = add of the negation: the same bits), the section at
                            // each point by the view area's own index arithmetic, its node by the section's index
                            double px = blockpos3.getX(), py = blockpos3.getY(), pz = blockpos3.getZ();
                            double dx = camX + -px, dy = camY + -py, dz = camZ + -pz;
                            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            double sx, sy, sz;
                            if (len < 1.0E-4) {
                                sx = 0.0;
                                sy = 0.0;
                                sz = 0.0;
                            } else {
                                sx = dx / len * CEILED_SECTION_DIAGONAL;
                                sy = dy / len * CEILED_SECTION_DIAGONAL;
                                sz = dz / len * CEILED_SECTION_DIAGONAL;
                            }
                            boolean flag2 = true;
                            while (true) {
                                double ex = camX + -px, ey = camY + -py, ez = camZ + -pz;
                                if (!(ex * ex + ey * ey + ez * ez > 3600.0)) break;
                                px = px + sx;
                                py = py + sy;
                                pz = pz + sz;
                                if (py > maxY || py < minY) break;
                                int bx = Mth.floor(px), by = Mth.floor(py), bz = Mth.floor(pz);
                                int gyi = Math.floorDiv(by - minBuild, 16);
                                boolean has;
                                if (gyi >= 0 && gyi < gy) {
                                    int gi = (Math.floorMod(Math.floorDiv(bz, 16), gz) * gy + gyi) * gx + Math.floorMod(Math.floorDiv(bx, 16), gx);
                                    has = direct ? gi < nodes.length && nodes[gi] != null : graphStorage.sectionToNodeMap.get(secArr[gi]) != null;
                                } else {
                                    has = false;
                                }
                                if (!has) {
                                    flag2 = false;
                                    break;
                                }
                            }
                            if (!flag2) continue;
                            if (key >= 0 && key < ray.length) ray[key] = gen;   // (nodes are only added during a walk: it passes again)
                        }
                    }

                    SectionOcclusionGraph.Node node1 = graphStorage.sectionToNodeMap.get(section1);
                    if (node1 != null) {
                        node1.addSourceDirection(direction);
                    } else {
                        SectionOcclusionGraph.Node node2 = new SectionOcclusionGraph.Node(section1, direction, node.step + 1);
                        node2.setDirections(node.directions, direction);
                        boolean all;
                        if (!(section1.getDistToPlayerSqr() > 576.0)) {
                            all = true;
                        } else {   // the four neighbour chunks: one check a column for the walk
                            int idx = section1.index;
                            int col = idx >= 0 ? idx % gx + (idx / gxy) * gx : -1;
                            BlockPos o = section1.getOrigin();
                            long ck = ChunkPos.asLong(o.getX() >> 4, o.getZ() >> 4);
                            // (kept across walks while none of the four neighbour chunks came or went and the view did not move)
                            int ep = OcclusionFast.viewEpoch(), st = OcclusionFast.neighbourStamp(o.getX() >> 4, o.getZ() >> 4);
                            if (col >= 0 && col < memo.colGen.length && memo.colGen[col] == ep && memo.colStamp[col] == st && memo.colKey[col] == ck) {
                                all = memo.colOk[col];
                                colsKept++;
                            } else {
                                all = section1.doesChunkExistAt(section1.relativeOrigins[Direction.WEST.ordinal()])
                                        && section1.doesChunkExistAt(section1.relativeOrigins[Direction.NORTH.ordinal()])
                                        && section1.doesChunkExistAt(section1.relativeOrigins[Direction.EAST.ordinal()])
                                        && section1.doesChunkExistAt(section1.relativeOrigins[Direction.SOUTH.ordinal()]);
                                cols++;
                                if (col >= 0 && col < memo.colGen.length) {
                                    memo.colGen[col] = ep;
                                    memo.colStamp[col] = st;
                                    memo.colKey[col] = ck;
                                    memo.colOk[col] = all;
                                }
                            }
                        }
                        if (all) {
                            nodeQueue.add(node2);
                            graphStorage.sectionToNodeMap.put(section1, node2);
                        } else if (this.isInViewDistance(blockpos, section1.getOrigin())) {
                            graphStorage.sectionToNodeMap.put(section1, node2);
                            graphStorage.chunksWaitingForNeighbors.computeIfAbsent(ChunkPos.asLong(section1.getOrigin()), p -> new ArrayList<>()).add(section1);
                        }
                    }
                }
            }
        }
        OcclusionFast.RAYS.addAndGet(rays);
        OcclusionFast.RAYS_KEPT.addAndGet(raysKept);
        OcclusionFast.COLS.addAndGet(cols);
        OcclusionFast.COLS_KEPT.addAndGet(colsKept);
    }
}
