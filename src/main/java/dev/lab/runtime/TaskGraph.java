package dev.lab.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * The pipeline's task graph (notes/75, stages 5-6): nodes with dependency counters on a LabRuntime, made once and run
 * again and again without allocation. A node runs when the nodes it depends on are done; it runs either on any thread
 * (the pool's workers, or a pinned member helping while it waits) or only on its owner - a {@link Member}: a thread
 * pinned to the pool with an inbox (main for the game's state, the frame thread for the GL context). A member's inbox is
 * worked by the member itself ({@link Member#runInbox}); a member that waits for a graph runs its inbox and the graph's
 * ready any-thread nodes meanwhile, then sleeps the member's own way (its {@link Waker}: the main thread in the window's
 * event wait, the frame thread parked) - never a spin without end.
 * <p>
 * One run at a time per graph ({@link #start} after the last run is {@link #done}); the graph's shape is fixed after
 * {@link #seal}. A node that throws marks the run failed and still releases its successors (the run always ends); the
 * first error is rethrown by {@link #await}.
 */
public final class TaskGraph {
    /** how a pinned thread is woken when work arrives for it (main: glfwPostEmptyEvent; others: unpark) */
    public interface Waker {
        void wake(Thread t);
    }

    /** a thread pinned to the pool: runs the nodes bound to it, helps with any-thread nodes while it waits */
    public static final class Member {
        final String name;
        volatile Thread thread;
        final Waker waker;
        final MpmcRing inbox = new MpmcRing(1 << 10);
        public long ran, helped, waits;

        public Member(String name, Waker waker) {
            this.name = name;
            this.waker = waker;
        }

        /** the member's thread (set by the thread itself before the first graph that needs it) */
        public void bind(Thread t) {
            thread = t;
        }

        public Thread thread() {
            return thread;
        }

        void post(Node n) {
            while (!inbox.offer(n)) Thread.onSpinWait();   // (1024 nodes in one member's inbox: never in practice)
            Thread t = thread;
            if (t != null && t != Thread.currentThread()) waker.wake(t);
        }

        /** the member's thread: every node waiting in its inbox, in order; true if any ran */
        public boolean runInbox() {
            boolean any = false;
            Object o;
            while ((o = inbox.poll()) != null) {
                ((Node) o).runHere();
                ran++;
                any = true;
            }
            return any;
        }

        public boolean hasInbox() {
            return !inbox.isEmpty();
        }
    }

    public static final Waker UNPARK = LockSupport::unpark;

    /** a node: its body, where it may run, the nodes waiting for it */
    public final class Node implements Runnable {
        public final String name;
        final Runnable body;
        final Member owner;   // null: any thread
        final LabRuntime.Lane lane;
        final List<Node> next = new ArrayList<>();
        Node[] succ = new Node[0];
        int deps;
        final AtomicInteger pending = new AtomicInteger();
        /**
         * any-thread nodes: the run it was made ready in, and the last run it was claimed in - a worker or a helping
         * member claims it by moving claim from run - 1 to run; a stale pool entry of an earlier run (a member ran that
         * node first) finds another run in ready and does nothing (it ran a node early before: GraphTest's diamond)
         */
        volatile int ready;
        final AtomicInteger claim = new AtomicInteger();
        public long runs, nanos;

        Node(String name, Runnable body, Member owner, LabRuntime.Lane lane) {
            this.name = name;
            this.body = body;
            this.owner = owner;
            this.lane = lane;
        }

        /** the pool's entry: runs the node if nobody claimed it yet */
        @Override
        public void run() {
            if (tryClaim()) runHere();
        }

        boolean tryClaim() {
            int r = ready;
            return r == epoch && claim.compareAndSet(r - 1, r);
        }

        void runHere() {
            long t0 = System.nanoTime();
            try {
                body.run();
            } catch (Throwable t) {
                if (error == null) error = t;
            } finally {
                nanos += System.nanoTime() - t0;
                runs++;
                finished(this);
            }
        }
    }

    final String name;
    final LabRuntime pool;
    private final List<Node> nodes = new ArrayList<>();
    private boolean sealed;
    private final AtomicInteger remaining = new AtomicInteger();
    private volatile boolean running;
    /** the run's number (1, 2, ...) */
    private volatile int epoch;
    private volatile Throwable error;
    /** any-thread nodes ready now: members waiting take them from here (the pool's copy is skipped by its claim) */
    private final MpmcRing readyAny = new MpmcRing(1 << 10);
    /** the members waiting in await (woken when a node is ready or the run ends); fixed slots - no allocation */
    private final java.util.concurrent.atomic.AtomicReferenceArray<Member> waiters = new java.util.concurrent.atomic.AtomicReferenceArray<>(4);
    public long starts;

    public TaskGraph(String name, LabRuntime pool) {
        this.name = name;
        this.pool = pool;
    }

    /** a node that runs on any thread (the pool's lane given) */
    public Node add(String name, LabRuntime.Lane lane, Runnable body) {
        return add(name, null, lane, body);
    }

    /** a node that runs only on its owner */
    public Node add(String name, Member owner, Runnable body) {
        return add(name, owner, LabRuntime.Lane.TICK, body);
    }

    private Node add(String name, Member owner, LabRuntime.Lane lane, Runnable body) {
        if (sealed) throw new IllegalStateException(this.name + " is sealed");
        Node n = new Node(name, body, owner, lane);
        nodes.add(n);
        return n;
    }

    /** b runs after a */
    public TaskGraph after(Node a, Node b) {
        if (sealed) throw new IllegalStateException(name + " is sealed");
        a.next.add(b);
        b.deps++;
        return this;
    }

    public TaskGraph seal() {
        int any = 0;
        for (Node n : nodes) {
            n.succ = n.next.toArray(new Node[0]);
            if (n.owner == null) any++;
        }
        if (any > 1000) throw new IllegalStateException(name + ": more than 1000 any-thread nodes");
        sealed = true;
        return this;
    }

    /** a run begins: the nodes without dependencies are dispatched (the last run must be done) */
    public void start() {
        if (!sealed) seal();
        if (running) throw new IllegalStateException(name + ": started while running");
        error = null;
        while (readyAny.poll() != null) { }   // (the last run's entries the pool ran: claimed, dropped here)
        running = true;
        starts++;
        remaining.set(nodes.size());
        for (Node n : nodes) n.pending.set(n.deps);
        epoch++;
        for (Node n : nodes) if (n.deps == 0) dispatch(n);
    }

    private void dispatch(Node n) {
        if (n.owner != null) {
            n.owner.post(n);
            return;
        }
        n.ready = epoch;
        while (!readyAny.offer(n)) Thread.onSpinWait();
        pool.execute(n, n.lane);
        wakeWaiters();   // (members waiting for this graph can take it too: whoever claims it first runs it)
    }

    private void finished(Node n) {
        for (Node s : n.succ) if (s.pending.decrementAndGet() == 0) dispatch(s);
        if (remaining.decrementAndGet() == 0) {
            running = false;
            wakeWaiters();
        }
    }

    private void wakeWaiters() {
        for (int i = 0; i < 4; i++) {
            Member m = waiters.get(i);
            if (m == null) continue;
            Thread t = m.thread;
            if (t != null && t != Thread.currentThread()) m.waker.wake(t);
        }
    }

    /** the run has ended (every node ran) */
    public boolean done() {
        return !running;
    }

    /** a member: one ready any-thread node of this graph run here, if there is one; true if it ran one */
    public boolean helpOne(Member m) {
        Object o;
        while ((o = readyAny.poll()) != null) {
            Node n = (Node) o;
            if (n.tryClaim()) {
                n.runHere();
                m.helped++;
                return true;
            }
        }
        return false;
    }

    /**
     * A member waits for the run's end: its inbox first, then the graph's ready any-thread nodes, then its own way of
     * sleeping ({@code sleep}: returns after a wake or a short timeout - the main thread's event wait). The first error
     * of the run is thrown here.
     */
    public void await(Member m, Runnable sleep) {
        if (running) {
            int slot = -1;
            for (int i = 0; i < 4 && slot < 0; i++) if (waiters.compareAndSet(i, null, m)) slot = i;
            try {
                while (running) {
                    if (m.runInbox()) continue;
                    if (helpOne(m)) continue;
                    if (!running) break;
                    m.waits++;
                    sleep.run();
                }
            } finally {
                if (slot >= 0) waiters.set(slot, null);
            }
            m.runInbox();   // (nodes posted to it by the run's last nodes)
        }
        Throwable t = error;
        if (t != null) {
            error = null;
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        }
    }

    public List<Node> nodes() {
        return nodes;
    }

    public String report() {
        StringBuilder b = new StringBuilder(name).append(": runs ").append(starts);
        for (Node n : nodes) b.append(String.format(java.util.Locale.ROOT, ", %s %.3f ms", n.name, n.runs == 0 ? 0.0 : n.nanos / 1e6 / n.runs));
        return b.toString();
    }
}
