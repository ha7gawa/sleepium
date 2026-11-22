package dev.lull.optsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

/**
 * GenGraph (-Dlab.opt.GenGraph): chunk generation as a dependency graph instead of vanilla's layered tasks.
 *
 * <p>Vanilla: a ChunkGenerationTask brings one chunk to a target status layer by layer - it applies status S to every
 * chunk in S's radius and waits for the WHOLE layer before it starts S+1 (a barrier per status per task), and every
 * continuation goes through the priority sorter into one `worldgen` mailbox (one logical thread for the world).
 *
 * <p>Here a node is (chunk, status). It depends on (chunk, status - 1) and on the neighbours named by that status'
 * ChunkStep.directDependencies (status by chessboard distance) - the same radii WorldGenRegion checks on every neighbour
 * access. When the last dependency completes, the node is queued on the server's background pool (GenReactor's queues
 * by ticket level) and runs vanilla's GenerationChunkHolder.applyStep - the same step bodies, the same CAS that runs a
 * (chunk, status) step once, the same futures the rest of the game waits on.
 *
 * <p>Why the results are vanilla's: a node runs only when its direct dependencies are complete, which is exactly the
 * order vanilla produces for a task targeting that status at that chunk (its accumulated radii are the closure of these
 * direct dependencies) - a schedule vanilla itself can produce. Which pyramid a step comes from (generation or loading)
 * is decided per chunk from its persisted status, as ChunkGenerationTask.scheduleChunkInLayer does. FEATURES' 3x3
 * writes stay apart through ParallelGen's claims (ParallelWorldgenMixin is turned on with this flag).
 *
 * <p>Ownership: the task that creates a node owns it - the node resolves neighbours through that task's StaticCache2D,
 * and the task keeps its claim (generation ref counts) until every node it created has finished, even if the task itself
 * finished or failed earlier. Finished or failed nodes are dropped from the holder: a later request looks at the
 * holder's futures again (a status refused because of a lowered ticket is retried by the next task, never cached).
 *
 * <p>Graph structure changes (create, link, complete) happen under one lock - a few hash-free array operations per
 * node, ~10 nodes per chunk; step bodies run outside it.
 */
public final class GenGraph {
    public static final boolean ENABLED = Boolean.getBoolean("lab.opt.GenGraph");
    static final boolean LOCAL = Boolean.getBoolean("lab.gen.graphLocal");
    private static final Object LOCK = new Object();
    private static final List<ChunkStatus> STATUSES = ChunkStatus.getStatusList();

    public static final LongAdder ROOTS = new LongAdder(), NODES = new LongAdder(), RUN = new LongAdder(), FAILED = new LongAdder(),
            GATED = new LongAdder(), WAIT_NANOS = new LongAdder();

    private static final byte WAIT_PARENT = 1, WAIT_DEPS = 2, QUEUED = 3, RUNNING = 4, DONE = 5, FAILED_STATE = 6, DROPPED = 7;
    public static final LongAdder CANCELLED = new LongAdder(), PRUNED = new LongAdder();
    /** live roots by task (a cancellation finds its root); cancellations queued until the end of runGenerationTasks */
    private static final java.util.concurrent.ConcurrentHashMap<ChunkGenerationTask, Root> ROOT_OF = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentLinkedQueue<ChunkGenerationTask> CANCELS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** the per-holder node slots (GenGraphHolderMixin) and its applyStep */
    public interface Holder {
        Node[] sleep$graphSlots();
        CompletableFuture<ChunkResult<ChunkAccess>> sleep$applyStep(ChunkStep step, ChunkMap map, StaticCache2D<GenerationChunkHolder> cache);
    }

    /** ChunkGenerationTask's private parts (GenGraphTaskAccessor) */
    public interface Task {
        StaticCache2D<GenerationChunkHolder> sleep$cache();
        ChunkPos sleep$pos();
        void sleep$releaseClaim();
    }

    /** ChunkMap's gate data: futures that pool work joins (the old-chunk scan of a region, stronghold rings) */
    public interface Gate {
        CompletableFuture<?> sleep$gateFor(ChunkPos pos);
    }

    static final class Root {
        final ChunkGenerationTask task;
        final StaticCache2D<GenerationChunkHolder> cache;
        final ChunkMap map;
        int owned;            // nodes created by this root and not finished (LOCK)
        boolean finished;     // its own node completed or failed, or the task was cancelled (LOCK)
        boolean released;
        Node node;            // the node it waits for

        Root(ChunkGenerationTask task, ChunkMap map) {
            this.task = task;
            this.cache = ((Task) task).sleep$cache();
            this.map = map;
        }
    }

    public static final class Node {
        final GenerationChunkHolder holder;
        final ChunkStatus status;
        final Root owner;
        byte state;
        int pending;
        ChunkStep step;
        ArrayList<Node> waiters;
        ArrayList<Node> deps;   // the nodes this one waits for (unlinked when it is pruned)
        ArrayList<Root> roots;
        long readyNanos;

        Node(GenerationChunkHolder holder, ChunkStatus status, Root owner) {
            this.holder = holder;
            this.status = status;
            this.owner = owner;
        }
    }

    private static final ThreadLocal<ArrayList<Node>> TO_RUN = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<ArrayList<Root>> TO_RELEASE = ThreadLocal.withInitial(ArrayList::new);

    private GenGraph() {}

    // ------------------------------------------------------------------ entry (main thread: ChunkMap.runGenerationTask)

    public static void start(ChunkMap map, ChunkGenerationTask task) {
        ROOTS.increment();
        Root root = new Root(task, map);
        GenerationChunkHolder center = task.getCenter();
        ArrayList<Node> run = TO_RUN.get();
        ArrayList<Root> rel = TO_RELEASE.get();
        synchronized (LOCK) {
            Node n = want(root, center, task.targetStatus, run);
            if (n == null) {
                finishRoot(root, rel);            // already there
            } else {
                if (n.roots == null) n.roots = new ArrayList<>(2);
                n.roots.add(root);
                root.node = n;
                ROOT_OF.put(task, root);
            }
        }
        flush(run, rel);
    }

    // ------------------------------------------------------------------ graph (under LOCK)

    /** the live node for (holder, status), created (with its parent chain) if needed; null when the status is already there */
    private static Node want(Root owner, GenerationChunkHolder holder, ChunkStatus status, ArrayList<Node> run) {
        Node[] slots = ((Holder) holder).sleep$graphSlots();
        int i = status.getIndex();
        Node n = slots[i];
        if (n != null) return n;
        if (holder.getChunkIfPresentUnchecked(status) != null) return null;
        n = new Node(holder, status, owner);
        slots[i] = n;
        owner.owned++;
        NODES.increment();
        if (status == ChunkStatus.EMPTY) {
            n.step = ChunkPyramid.LOADING_PYRAMID.getStepTo(ChunkStatus.EMPTY);
            ready(n, run);
            return n;
        }
        Node p = want(owner, holder, status.getParent(), run);
        if (p == null) {
            expand(n, run);
        } else {
            n.state = WAIT_PARENT;
            n.pending = 1;
            addWaiter(p, n);
        }
        return n;
    }

    private static void addWaiter(Node dep, Node n) {
        if (dep.waiters == null) dep.waiters = new ArrayList<>(4);
        dep.waiters.add(n);
        if (n.deps == null) n.deps = new ArrayList<>(4);
        n.deps.add(dep);
    }

    /** the parent is complete: pick the step (generation or loading by the persisted status) and link the neighbours */
    private static void expand(Node n, ArrayList<Node> run) {
        ChunkStatus persisted = n.holder.getPersistedStatus();
        boolean generate = persisted != null && n.status.isAfter(persisted);
        n.step = (generate ? ChunkPyramid.GENERATION_PYRAMID : ChunkPyramid.LOADING_PYRAMID).getStepTo(n.status);
        ChunkDependencies deps = n.step.directDependencies();
        int radius = deps.getRadius();
        n.state = WAIT_DEPS;
        n.pending = 0;
        if (radius > 0) {
            ChunkPos c = n.holder.getPos();
            StaticCache2D<GenerationChunkHolder> cache = n.owner.cache;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int d = Math.max(Math.abs(dx), Math.abs(dz));
                    if (d == 0) continue;
                    GenerationChunkHolder y = cache.get(c.x + dx, c.z + dz);
                    Node dep = want(n.owner, y, deps.get(d), run);
                    if (dep != null) {
                        n.pending++;
                        addWaiter(dep, n);
                    }
                }
            }
        }
        if (n.pending == 0) ready(n, run);
    }

    private static void ready(Node n, ArrayList<Node> run) {
        n.state = QUEUED;
        n.readyNanos = System.nanoTime();
        run.add(n);
    }

    private static void complete(Node n, boolean ok, ArrayList<Node> run, ArrayList<Root> rel) {
        n.state = ok ? DONE : FAILED_STATE;
        if (!ok) FAILED.increment();
        Node[] slots = ((Holder) n.holder).sleep$graphSlots();
        if (slots[n.status.getIndex()] == n) slots[n.status.getIndex()] = null;
        n.deps = null;
        if (n.waiters != null) {
            for (Node w : n.waiters) {
                if (w.state >= DONE) continue;
                if (!ok) {
                    complete(w, false, run, rel);
                } else if (--w.pending == 0) {
                    if (w.state == WAIT_PARENT) expand(w, run);
                    else ready(w, run);
                }
            }
            n.waiters = null;
        }
        if (n.roots != null) {
            for (Root r : n.roots) finishRoot(r, rel);
            n.roots = null;
        }
        Root o = n.owner;
        if (--o.owned == 0 && o.finished && !o.released) {
            o.released = true;
            rel.add(o);
        }
    }

    private static void finishRoot(Root r, ArrayList<Root> rel) {
        r.finished = true;
        ROOT_OF.remove(r.task);
        if (r.owned == 0 && !r.released) {
            r.released = true;
            rel.add(r);
        }
    }

    // ------------------------------------------------------------------ cancellation (main thread)

    /** ChunkGenerationTask.markForCancellation: queued, applied at the end of runGenerationTasks (tasks that replace it
     *  have attached to the shared nodes by then, so a re-targeted chunk does not drop and re-create its chain) */
    public static void cancel(ChunkGenerationTask task) {
        CANCELS.add(task);
    }

    public static void processCancels() {
        if (CANCELS.isEmpty()) return;
        ArrayList<Root> rel = TO_RELEASE.get();
        ArrayList<Node> run = TO_RUN.get();
        synchronized (LOCK) {
            ChunkGenerationTask t;
            while ((t = CANCELS.poll()) != null) {
                Root r = ROOT_OF.remove(t);
                if (r == null || r.finished) continue;
                CANCELLED.increment();
                Node n = r.node;
                if (n != null && n.roots != null) n.roots.remove(r);
                finishRoot(r, rel);
                if (n != null) prune(n, rel);
            }
        }
        flush(run, rel);
    }

    /** a node nobody waits for any more and that has not started leaves the graph, and so may its dependencies */
    private static void prune(Node n, ArrayList<Root> rel) {
        if (n.state != WAIT_PARENT && n.state != WAIT_DEPS && n.state != QUEUED) return;
        if ((n.roots != null && !n.roots.isEmpty()) || (n.waiters != null && !n.waiters.isEmpty())) return;
        n.state = DROPPED;
        PRUNED.increment();
        Node[] slots = ((Holder) n.holder).sleep$graphSlots();
        if (slots[n.status.getIndex()] == n) slots[n.status.getIndex()] = null;
        Root o = n.owner;
        if (--o.owned == 0 && o.finished && !o.released) {
            o.released = true;
            rel.add(o);
        }
        ArrayList<Node> deps = n.deps;
        n.deps = null;
        if (deps != null) {
            for (Node d : deps) {
                if (d.waiters != null) d.waiters.remove(n);
                prune(d, rel);
            }
        }
    }

    // ------------------------------------------------------------------ running (outside LOCK)

    private static void flush(ArrayList<Node> run, ArrayList<Root> rel) {
        if (!rel.isEmpty()) {
            for (Root r : rel) ((Task) r.task).sleep$releaseClaim();
            rel.clear();
        }
        if (!run.isEmpty()) {
            // -Dlab.gen.graphLocal: on a pool worker, ready nodes go to that worker's own deque (LIFO, stolen by idle
            // workers) - the chunk whose step just finished continues on the core whose caches hold it; otherwise (and
            // from other threads) the global queues by ticket level
            if (LOCAL && Thread.currentThread() instanceof dev.lab.runtime.LabRuntime.LabThread) {
                java.util.concurrent.Executor pool = net.minecraft.Util.backgroundExecutor();
                for (Node n : run) pool.execute(() -> runNode(n));
            } else {
                for (Node n : run) GenReactor.post(n.holder.getTicketLevel(), n.holder.getPos().toLong(), () -> runNode(n));
            }
            run.clear();
        }
    }

    private static void runNode(Node n) {
        if (n.status != ChunkStatus.EMPTY && n.owner.map instanceof Gate g) {
            CompletableFuture<?> gate = g.sleep$gateFor(n.holder.getPos());
            if (gate != null) {
                GATED.increment();
                gate.whenComplete((v, e) -> GenReactor.post(n.holder.getTicketLevel(), n.holder.getPos().toLong(), () -> runNode(n)));
                return;
            }
        }
        synchronized (LOCK) {
            if (n.state != QUEUED) return;   // pruned while queued
            n.state = RUNNING;
        }
        RUN.increment();
        WAIT_NANOS.add(System.nanoTime() - n.readyNanos);
        CompletableFuture<ChunkResult<ChunkAccess>> f;
        try {
            f = ((Holder) n.holder).sleep$applyStep(n.step, n.owner.map, n.owner.cache);
        } catch (Throwable t) {
            finish(n, false);
            throw t;
        }
        f.whenComplete((r, e) -> finish(n, e == null && r != null && r.isSuccess()));
    }

    private static void finish(Node n, boolean ok) {
        ArrayList<Node> run = TO_RUN.get();
        ArrayList<Root> rel = TO_RELEASE.get();
        // a finish can arrive inside flush (a future completed synchronously): keep the outer lists intact
        boolean nested = !run.isEmpty() || !rel.isEmpty();
        if (nested) {
            run = new ArrayList<>();
            rel = new ArrayList<>();
        }
        synchronized (LOCK) {
            complete(n, ok, run, rel);
        }
        flush(run, rel);
    }

    public static String report() {
        long run = RUN.sumThenReset();
        return String.format(Locale.ROOT, "gen graph: %d tasks (%d cancelled), %d nodes (%d pruned), %d run (ready -> start %.2f ms on average), %d failed, %d gated",
                ROOTS.sumThenReset(), CANCELLED.sumThenReset(), NODES.sumThenReset(), PRUNED.sumThenReset(), run, run == 0 ? 0.0 : WAIT_NANOS.sumThenReset() / 1e6 / run,
                FAILED.sumThenReset(), GATED.sumThenReset());
    }
}
