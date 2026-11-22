package dev.lull;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Measurement only (-Dlab.light.trace=x0,y0,z0,x1,y1,z1 with -Dlab.light.probe, notes/84): every block-light event of
 * a box, in order, from the thread it happens on - checkBlock submitted, checkNode run (the state it read, section stored,
 * light on, stored level), section status submitted / applied, light enabled, a chunk's sources propagated. For the
 * N100 warm-up spot where LightDirect's light ends below the from-scratch light (a lava spring at 64087,-22,64124 of
 * seed 20260924), to compare a run that differs with one that does not.
 */
public final class LightTrace {
    private LightTrace() {}

    private static final int[] BOX = parse(System.getProperty("lab.light.trace"));
    public static final boolean ON = BOX != null;
    private static final long T0 = System.nanoTime();
    private static final AtomicLong SEQ = new AtomicLong();

    private static int[] parse(String v) {
        if (v == null) return null;
        String[] p = v.split(",");
        int[] b = new int[6];
        for (int i = 0; i < 6; i++) b[i] = Integer.parseInt(p[i].trim());
        return b;
    }

    public static boolean in(BlockPos p) {
        return ON && p.getX() >= BOX[0] && p.getY() >= BOX[1] && p.getZ() >= BOX[2] && p.getX() <= BOX[3] && p.getY() <= BOX[4] && p.getZ() <= BOX[5];
    }

    /** the section intersects the box */
    public static boolean inSection(long sectionPos) {
        if (!ON) return false;
        int x = SectionPos.x(sectionPos) << 4, y = SectionPos.y(sectionPos) << 4, z = SectionPos.z(sectionPos) << 4;
        return x + 15 >= BOX[0] && x <= BOX[3] && y + 15 >= BOX[1] && y <= BOX[4] && z + 15 >= BOX[2] && z <= BOX[5];
    }

    /** the chunk column intersects the box */
    public static boolean inChunk(int cx, int cz) {
        if (!ON) return false;
        int x = cx << 4, z = cz << 4;
        return x + 15 >= BOX[0] && x <= BOX[3] && z + 15 >= BOX[2] && z <= BOX[5];
    }

    public static void log(String what) {
        System.out.println(String.format(java.util.Locale.ROOT, "[sleep/ltrace] %d %.3f ms [%s] %s", SEQ.incrementAndGet(),
                (System.nanoTime() - T0) / 1e6, Thread.currentThread().getName(), what));
    }
}
