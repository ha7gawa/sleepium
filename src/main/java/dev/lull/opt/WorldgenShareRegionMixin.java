package dev.lull.opt;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * WorldgenShare (region): every block access of a generation step (features place ores block by block) looks its chunk
 * up again — StaticCache2D, then the holder's AtomicReferenceArray of futures and CompletableFuture.getNow: volatile
 * reads of lines other workers keep writing (18.7 % of FEATURES' own time with parallel generation).
 *
 * <p>The answer for (x, z) is fixed for the region's lifetime unless a chunk in it reaches FULL meanwhile (FULL replaces
 * the ProtoChunk futures with an ImposterProtoChunk). That cannot happen when FULL of a chunk at distance d requires
 * this region's centre to be at a status ≥ the running step's target — read from ChunkPyramid per step and distance
 * (modded steps too): at those distances a memo per region returns exactly the object vanilla's lookup returns; other
 * distances and steps go the vanilla way.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldgenShareRegionMixin {
    @Shadow @Final private StaticCache2D<GenerationChunkHolder> cache;
    @Shadow @Final private ChunkAccess center;
    @Shadow @Final private ChunkStep generatingStep;

    /** per step: for each distance, whether a chunk that far cannot reach FULL while this step runs */
    @Unique private static final Map<ChunkStep, boolean[]> PROBE$SAFE = new ConcurrentHashMap<>();
    /** 0 unknown, 1 memo on (for the safe distances), 2 memo off */
    @Unique private byte sleep$state;
    @Unique private boolean[] sleep$safeAt;
    @Unique private ChunkAccess[] sleep$memo;
    /** RegionFast: the step's dependency status at each memo slot (what vanilla's distance lookup gives there) */
    @Unique private ChunkStatus[] sleep$memoDep;
    /**
     * RegionFast (-Dlab.opt.WorldgenRegionFast): a filled memo slot answers before the distance and dependency lookups.
     * The slot of (x, z) is filled only after vanilla's path found, for this (x, z): distance i, dependency status
     * deps.get(i), the requested status isOrBefore it, a safe distance and a chunk; the slot keeps that dependency
     * status too. A later call for the same (x, z) computes the same i and deps.get(i) in vanilla's path, so "slot
     * filled and requested isOrBefore the kept status" is exactly the case in which that path returns the slot; any
     * other case (empty slot, a later status asked) still goes through it.
     */
    @Unique private static final boolean SLEEP$FAST = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.WorldgenRegionFast");
    @Unique private int sleep$r, sleep$cx, sleep$cz;

    @Unique
    private static boolean[] sleep$safe(ChunkStep step) {
        ChunkDependencies full = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL).accumulatedDependencies();
        int r = step.directDependencies().size() - 1;
        boolean[] safe = new boolean[r + 1];
        int upTo = -1;
        for (int d = 0; d <= r; d++) {
            // FULL of a chunk d away needs this centre at full.get(d): at or after the running step's target → not yet
            safe[d] = d < full.size() && !full.get(d).isBefore(step.targetStatus());
            if (safe[d] && upTo == d - 1) upTo = d;
        }
        System.out.println("[sleep/opt] worldgen region memo for step " + step.targetStatus().getName() + " (radius " + r + "): "
                + (upTo < 0 ? "off" : "distances 0.." + upTo));
        return safe;
    }

    /**
     * @author probe
     * @reason memo of the region's chunk lookups where they cannot change (see class doc); otherwise vanilla
     */
    @Overwrite
    public ChunkAccess getChunk(int x, int z, ChunkStatus chunkStatus, boolean requireChunk) {
        if (SLEEP$FAST && sleep$state == 1) {
            int r = sleep$r, dx = x - sleep$cx, dz = z - sleep$cz;
            if (dx >= -r && dx <= r && dz >= -r && dz <= r) {
                int idx = (dx + r) * (2 * r + 1) + (dz + r);
                ChunkAccess m = sleep$memo[idx];
                ChunkStatus dep = sleep$memoDep[idx];
                if (m != null && dep != null && chunkStatus.isOrBefore(dep)) return m;
            }
        }
        int i = this.center.getPos().getChessboardDistance(x, z);
        ChunkStatus chunkstatus = i >= this.generatingStep.directDependencies().size() ? null : this.generatingStep.directDependencies().get(i);
        GenerationChunkHolder generationchunkholder;
        if (chunkstatus != null) {
            int idx = -1;
            if (chunkStatus.isOrBefore(chunkstatus)) {
                if (sleep$state == 0) {
                    sleep$safeAt = PROBE$SAFE.computeIfAbsent(this.generatingStep, WorldgenShareRegionMixin::sleep$safe);
                    boolean on = false;
                    for (boolean b : sleep$safeAt) on |= b;
                    sleep$state = (byte) (on ? 1 : 2);
                    if (on) {
                        sleep$r = this.generatingStep.directDependencies().size() - 1;
                        sleep$cx = this.center.getPos().x;
                        sleep$cz = this.center.getPos().z;
                        int w = 2 * sleep$r + 1;
                        sleep$memoDep = new ChunkStatus[w * w];
                        sleep$memo = new ChunkAccess[w * w];
                    }
                }
                if (sleep$state == 1 && sleep$safeAt[i]) {
                    idx = (x - sleep$cx + sleep$r) * (2 * sleep$r + 1) + (z - sleep$cz + sleep$r);
                    ChunkAccess m = sleep$memo[idx];
                    if (m != null) return m;
                }
            }
            generationchunkholder = this.cache.get(x, z);
            if (chunkStatus.isOrBefore(chunkstatus)) {
                ChunkAccess chunkaccess = generationchunkholder.getChunkIfPresentUnchecked(chunkstatus);
                if (chunkaccess != null) {
                    if (idx >= 0) {
                        sleep$memoDep[idx] = chunkstatus;
                        sleep$memo[idx] = chunkaccess;
                    }
                    return chunkaccess;
                }
            }
        } else {
            generationchunkholder = null;
        }

        CrashReport crashreport = CrashReport.forThrowable(
            new IllegalStateException("Requested chunk unavailable during world generation"), "Exception generating new chunk"
        );
        CrashReportCategory crashreportcategory = crashreport.addCategory("Chunk request details");
        crashreportcategory.setDetail("Requested chunk", String.format(Locale.ROOT, "%d, %d", x, z));
        crashreportcategory.setDetail("Generating status", () -> this.generatingStep.targetStatus().getName());
        crashreportcategory.setDetail("Requested status", chunkStatus::getName);
        crashreportcategory.setDetail(
            "Actual status", () -> generationchunkholder == null ? "[out of cache bounds]" : generationchunkholder.getPersistedStatus().getName()
        );
        crashreportcategory.setDetail("Maximum allowed status", () -> chunkstatus == null ? "null" : chunkstatus.getName());
        crashreportcategory.setDetail("Dependencies", this.generatingStep.directDependencies()::toString);
        crashreportcategory.setDetail("Requested distance", i);
        crashreportcategory.setDetail("Generating chunk", this.center.getPos()::toString);
        throw new ReportedException(crashreport);
    }
}
