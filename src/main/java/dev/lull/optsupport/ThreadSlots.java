package dev.lull.optsupport;

import dev.lab.runtime.LabRuntime;

/**
 * Per-thread state of the meshing hot paths kept on our pool's threads (LabRuntime.LabThread.slots): the current thread
 * and a field read instead of a ThreadLocal's hash into the thread's map - asked for every face and block meshed
 * (ThreadLocalMap.getEntry: 5.6 % of the meshing workers in flight, 11-18). Other threads keep their ThreadLocal:
 * one instance a thread either way.
 */
public final class ThreadSlots {
    private ThreadSlots() {}

    public static final int AO = 0, FACES = 1, AO_CACHE = 2, STATES = 3;

    /** the current thread's slots when it is one of our pool's, else null */
    public static Object[] mine() {
        return Thread.currentThread() instanceof LabRuntime.LabThread t ? t.slots : null;
    }
}
