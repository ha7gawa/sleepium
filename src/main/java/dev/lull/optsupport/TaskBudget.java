package dev.lull.optsupport;

import net.minecraft.client.Minecraft;

/**
 * -Dlab.opt.TaskBudget (1.3.0 research, the packet queue as a role with a budget): the render thread runs the queued
 * tasks (the packets the network thread hands over, other threads' tasks) at the start of each frame - vanilla runs all
 * of them, so a burst (chunk data while loading: 90-300 ms of work on the N100) is one long frame. Here the queue is run
 * until the frame's budget is spent - half the usual frame interval, at least -Dlab.taskBudget.minMs (2) - and the rest
 * waits for the next frame, in the same order (for the game: one frame more of network delay). A backlog older than
 * -Dlab.taskBudget.maxFrames (30) frames is run whole, so nothing waits without end.
 */
public final class TaskBudget {
    private TaskBudget() {}

    public static final boolean ON = Boolean.getBoolean("lab.opt.TaskBudget");
    private static final long MIN = (long) (Double.parseDouble(System.getProperty("lab.taskBudget.minMs", "2")) * 1e6);
    private static final int MAX_FRAMES = Integer.getInteger("lab.taskBudget.maxFrames", 30);
    private static long prevFrameEnd;
    private static double avgInterval = 8e6;
    private static int backlogFrames;
    public static long frames, cutFrames, tasks, deferredFrames, forced;
    /** -Dlab.taskBudget.split=true: each task timed by its packet (the packet a handling lambda holds), the 10 costliest reported */
    private static final boolean SPLIT = Boolean.getBoolean("lab.taskBudget.split");
    private static final java.util.Map<String, long[]> COST = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, java.lang.reflect.Field> PACKET_FIELD = new java.util.HashMap<>();

    private static String key(Object task) {
        if (task == null) return "?";
        Class<?> c = task.getClass();
        java.lang.reflect.Field f = PACKET_FIELD.computeIfAbsent(c, k -> {
            for (java.lang.reflect.Field x : k.getDeclaredFields())
                if (net.minecraft.network.protocol.Packet.class.isAssignableFrom(x.getType()) || x.getType() == Object.class) {
                    try {
                        x.setAccessible(true);
                        return x;
                    } catch (Exception e) {
                        return null;
                    }
                }
            return null;
        });
        if (f != null) {
            try {
                Object v = f.get(task);
                if (v instanceof net.minecraft.network.protocol.Packet<?>) return v.getClass().getSimpleName();
            } catch (Exception ignored) {}
        }
        String n = c.getName();
        int i = n.indexOf("$$Lambda");
        return i > 0 ? n.substring(n.lastIndexOf('.') + 1, i) + " lambda" : n.substring(n.lastIndexOf('.') + 1);
    }

    private static boolean pollTimed(Minecraft mc) {
        if (!SPLIT) return mc.pollTask();
        Object next = ((dev.lull.opt.TaskQueueAccessor) mc).sleep$pending().peek();
        long t0 = System.nanoTime();
        boolean r = mc.pollTask();
        if (r) {
            long[] a = COST.computeIfAbsent(key(next), k -> new long[2]);
            a[0]++;
            a[1] += System.nanoTime() - t0;
        }
        return r;
    }

    public static void frameEnd() {
        long now = System.nanoTime();
        if (prevFrameEnd != 0) {
            long iv = now - prevFrameEnd;
            if (iv < 100_000_000L) avgInterval += (iv - avgInterval) * 0.05;
        }
        prevFrameEnd = now;
    }

    /** Minecraft.runTick's runAllTasks */
    public static void run(Minecraft mc) {
        frames++;
        long deadline = System.nanoTime() + Math.max(MIN, (long) (avgInterval * 0.5));
        boolean force = backlogFrames >= MAX_FRAMES;
        if (force) forced++;
        while (true) {
            if (!force && System.nanoTime() >= deadline) {
                if (mc.getPendingTasksCount() > 0) {
                    cutFrames++;
                    backlogFrames++;
                    deferredFrames++;
                    return;
                }
                break;
            }
            if (!pollTimed(mc)) break;
            tasks++;
        }
        backlogFrames = 0;
    }

    public static String report() {
        StringBuilder sb = new StringBuilder("task budget: " + frames + " frames, " + tasks + " tasks, " + cutFrames + " frames left tasks for the next one, " + forced + " backlogs run whole");
        if (SPLIT) {
            sb.append("; tasks by cost:");
            COST.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(10)
                    .forEach(e -> sb.append(String.format(java.util.Locale.ROOT, " %s %d x %.1f us = %.0f ms,", e.getKey(), e.getValue()[0], e.getValue()[1] / 1e3 / e.getValue()[0], e.getValue()[1] / 1e6)));
        }
        return sb.toString();
    }
}
