package dev.lull.opt;

import dev.lull.optsupport.ChunkIo;
import dev.lull.optsupport.ChunkIoWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionBitmap;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChunkIo: a region file opened for sync writes is opened without DSYNC; the header write is synced on both sides
 * instead (force before it: the data it points at is on the device; force after it: the header is) — vanilla's single
 * write keeps its guarantee with the same two waits, and a batch pays them once for all its chunks.
 *
 * <p>ChunkIoWriter (notes/86): with a cadence, a batch only writes its records and updates the header in memory; at
 * most once per cadence a round forces the file and then writes the header (durable at the next round's force). Old
 * sectors / .mcc files wait for the header that stops pointing at them to be durable.
 */
@Mixin(RegionFile.class)
public abstract class ChunkIoRegionMixin implements ChunkIo.Region {
    @Shadow @Final private FileChannel file;
    @Shadow @Final private ByteBuffer header;
    @Shadow @Final private IntBuffer offsets;
    @Shadow @Final private IntBuffer timestamps;
    @Shadow @Final protected RegionBitmap usedSectors;
    @Shadow @Final RegionFileVersion version;
    /** sync writes asked for (vanilla: DSYNC) and how they are kept: ChunkIoWriter.FORCE / WRITE_THROUGH / CADENCE; 0 = no sync */
    @Unique private byte sleep$mode;

    /** header entries changed since the header was last written (delta writes, and whether a round has to write it) */
    @Unique private final BitSet sleep$dirty = new BitSet(1024);
    /** packed sector offsets (and .mcc files) the in-memory header stopped pointing at: free after the next header is durable */
    @Unique private int[] sleep$freeNow = new int[32];
    @Unique private int sleep$freeNowN;
    @Unique private Path[] sleep$delNow = new Path[32];
    @Unique private int sleep$delNowN;
    /** ... whose header is written but not yet forced: free after the next force */
    @Unique private int[] sleep$freeLater = new int[32];
    @Unique private int sleep$freeLaterN;
    @Unique private Path[] sleep$delLater = new Path[32];
    @Unique private int sleep$delLaterN;
    /** records or a header written since the last force */
    @Unique private boolean sleep$unforced;
    /** sectors in sleep$freeNow and sleep$freeLater */
    @Unique private int sleep$held;
    @Unique private long sleep$lastRound;

    @Shadow
    private void writeHeader() throws IOException {
        throw new AssertionError();
    }

    @Shadow
    private int packSectorOffset(int sectorOffset, int sectorCount) {
        throw new AssertionError();
    }

    @Shadow
    private static int getOffsetIndex(ChunkPos chunkPos) {
        throw new AssertionError();
    }

    @Shadow
    private static int sizeToSectors(int size) {
        throw new AssertionError();
    }

    @Shadow
    private static int getTimestamp() {
        throw new AssertionError();
    }

    @Shadow
    private static int getSectorNumber(int packedSectorOffset) {
        throw new AssertionError();
    }

    @Shadow
    private static int getNumSectors(int packedSectorOffset) {
        throw new AssertionError();
    }

    @Shadow
    private Path getExternalChunkPath(ChunkPos chunkPos) {
        throw new AssertionError();
    }

    @Shadow
    protected abstract void write(ChunkPos chunkPos, ByteBuffer chunkData) throws IOException;

    // 1.20.1: the constructor has no RegionStorageInfo, and opens the channel in one of two calls (with or without DSYNC)
    @Redirect(method = "<init>(Ljava/nio/file/Path;Ljava/nio/file/Path;Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;Z)V",
            at = @At(value = "INVOKE", target = "Ljava/nio/channels/FileChannel;open(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/nio/channels/FileChannel;"))
    private FileChannel sleep$open(Path path, OpenOption[] options) throws IOException {
        int n = 0;
        OpenOption[] kept = new OpenOption[options.length];
        for (OpenOption o : options) {
            if (o == StandardOpenOption.DSYNC) sleep$mode = ChunkIoWriter.mode();
            else kept[n++] = o;
        }
        if (sleep$mode == ChunkIoWriter.WRITE_THROUGH) return FileChannel.open(path, options);   // vanilla's DSYNC kept
        return FileChannel.open(path, java.util.Arrays.copyOf(kept, n));
    }

    @Unique
    private boolean sleep$cadence() {
        return sleep$mode == ChunkIoWriter.CADENCE;
    }

    /** sync writes kept by forces (1.3.3's group commit, or the cadence): vanilla's header writes are forced around */
    @Unique
    private boolean sleep$forced() {
        return sleep$mode == ChunkIoWriter.FORCE || sleep$mode == ChunkIoWriter.CADENCE;
    }

    @Unique
    private void sleep$force() throws IOException {
        long t = System.nanoTime();
        file.force(true);
        ChunkIoWriter.noteForce(System.nanoTime() - t);
        sleep$unforced = false;
    }

    /** vanilla's header writes (clear, oversized chunks, our batch path): the data it points at is durable first */
    @Inject(method = "writeHeader", at = @At("HEAD"))
    private void sleep$syncData(CallbackInfo ci) throws IOException {
        if (sleep$forced()) sleep$force();
    }

    /** ... and the header after it: everything a cadence kept pending is durable now */
    @Inject(method = "writeHeader", at = @At("TAIL"))
    private void sleep$syncHeader(CallbackInfo ci) throws IOException {
        ChunkIoWriter.headerWrites.incrementAndGet();
        ChunkIoWriter.headerBytes.addAndGet(8192);
        if (!sleep$forced()) return;
        sleep$force();
        sleep$dirty.clear();
        sleep$releaseLater();
        sleep$releaseNow();
    }

    @Override
    public int sleep$versionId() {
        return version.getId();
    }

    @Override
    public synchronized void sleep$writeBatch(ChunkPos[] pos, byte[][] data, int n) throws IOException {
        int[] index = new int[n], packed = new int[n], old = new int[n], start = new int[n];
        byte[][] rec = new byte[n][];
        Path[] external = new Path[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            byte[] record = data[i];
            int sectors = sizeToSectors(record.length);
            // 1.21.1 emits JvmProfiler.onRegionFileWrite here as vanilla's write does; 1.20.1's RegionFile has no such event
            if (sectors >= 256) {   // an oversized chunk: vanilla's own path (its external file, the stub)
                write(pos[i], ByteBuffer.wrap(record));
                continue;
            }
            start[m] = usedSectors.allocate(sectors);
            rec[m] = record;
            index[m] = getOffsetIndex(pos[i]);
            packed[m] = packSectorOffset(start[m], sectors);
            external[m] = getExternalChunkPath(pos[i]);
            m++;
        }
        if (m == 0) return;
        if (ChunkIoWriter.COALESCE && m > 1) sleep$writeRuns(rec, start, m);
        else for (int i = 0; i < m; i++) file.write(ByteBuffer.wrap(rec[i]), (long) start[i] * 4096L);
        int now = getTimestamp();
        boolean cadence = sleep$cadence();
        for (int i = 0; i < m; i++) {
            old[i] = offsets.get(index[i]);   // (read here: an oversized write above may have changed the entry)
            if (cadence && old[i] != 0) {
                // an entry no header has carried yet (changed since the last header write) points at sectors no header
                // on the device or in the cache knows: they go right away; else after that header is durable
                if (sleep$dirty.get(index[i])) usedSectors.free(getSectorNumber(old[i]), getNumSectors(old[i]));
                else {
                    sleep$freeNow = sleep$push(sleep$freeNow, sleep$freeNowN++, old[i]);
                    sleep$held += getNumSectors(old[i]);
                }
            }
            offsets.put(index[i], packed[i]);
            timestamps.put(index[i], now);
            sleep$dirty.set(index[i]);
        }
        if (cadence) {
            for (int i = 0; i < m; i++) sleep$delNow = sleep$push(sleep$delNow, sleep$delNowN++, external[i]);
            sleep$unforced = true;
            long t = System.nanoTime();
            // a round when the cadence is over, or early when the held sectors would grow the file too much
            if (t - sleep$lastRound >= ChunkIoWriter.CADENCE_NS || ChunkIoWriter.HOLD_CAP > 0 && sleep$held >= ChunkIoWriter.HOLD_CAP) sleep$round(false, t);
            return;
        }
        // write-through: every record above was acknowledged by the device (DSYNC) before the header is issued
        writeHeader();   // forced before (the sectors) and after (the header) in the force mode; DSYNC in write-through
        for (int i = 0; i < m; i++) {
            Files.deleteIfExists(external[i]);   // vanilla's commit op: an older oversized copy goes
            if (old[i] != 0) usedSectors.free(getSectorNumber(old[i]), getNumSectors(old[i]));
        }
    }

    /**
     * A round: the force makes the records since the last round and the last round's header durable, so the sectors
     * that header stopped pointing at can go; then the header is written (it points at durable records only). full:
     * the header forced too and everything released (flush, close).
     */
    @Unique
    private void sleep$round(boolean full, long now) throws IOException {
        ChunkIoWriter.rounds.incrementAndGet();
        sleep$force();
        sleep$releaseLater();
        if (!sleep$dirty.isEmpty()) {
            sleep$writeHeaderFull();
            sleep$unforced = true;
        }
        // what the header just written stopped pointing at: free after the next force
        for (int i = 0; i < sleep$freeNowN; i++) sleep$freeLater = sleep$push(sleep$freeLater, sleep$freeLaterN++, sleep$freeNow[i]);
        for (int i = 0; i < sleep$delNowN; i++) sleep$delLater = sleep$push(sleep$delLater, sleep$delLaterN++, sleep$delNow[i]);
        sleep$freeNowN = 0;
        java.util.Arrays.fill(sleep$delNow, 0, sleep$delNowN, null);
        sleep$delNowN = 0;
        if (full && (sleep$unforced || sleep$freeLaterN > 0 || sleep$delLaterN > 0)) {
            sleep$force();
            sleep$releaseLater();
        }
        sleep$lastRound = now;
    }

    @Override
    public synchronized boolean sleep$commitDue(long now) throws IOException {
        if (!sleep$cadence() || !sleep$pending()) return false;
        if (now - sleep$lastRound >= ChunkIoWriter.CADENCE_NS) sleep$round(false, now);
        return sleep$pending();
    }

    @Unique
    private boolean sleep$pending() {
        return sleep$unforced || !sleep$dirty.isEmpty() || sleep$freeNowN > 0 || sleep$freeLaterN > 0 || sleep$delNowN > 0 || sleep$delLaterN > 0;
    }

    /** RegionFile.flush (save-all flush, IOWorker.synchronize(true)): fully durable before vanilla's own force */
    @Inject(method = "flush", at = @At("HEAD"))
    private void sleep$flush(CallbackInfo ci) throws IOException {
        synchronized (this) {
            if (sleep$cadence() && sleep$pending()) sleep$round(true, System.nanoTime());
        }
    }

    /** RegionFile.close (server stop, the region cache's eviction): fully durable */
    @Inject(method = "close", at = @At("HEAD"))
    private void sleep$close(CallbackInfo ci) throws IOException {
        synchronized (this) {
            if (sleep$cadence() && sleep$pending()) sleep$round(true, System.nanoTime());
        }
    }

    /** records whose sectors follow each other in one write each (sorted by start sector) */
    @Unique
    private void sleep$writeRuns(byte[][] rec, int[] start, int m) throws IOException {
        long[] order = new long[m];
        for (int i = 0; i < m; i++) order[i] = (long) start[i] << 20 | i;
        java.util.Arrays.sort(order);
        int i = 0;
        while (i < m) {
            int a = (int) (order[i] & 0xFFFFF), j = i + 1, last = a;
            while (j < m) {
                int b = (int) (order[j] & 0xFFFFF);
                if (start[b] != start[last] + sizeToSectors(rec[last].length)) break;
                last = b;
                j++;
            }
            if (j == i + 1) {
                file.write(ByteBuffer.wrap(rec[a]), (long) start[a] * 4096L);
            } else {
                int len = (start[last] - start[a]) * 4096 + rec[last].length;
                ByteBuffer buf = ByteBuffer.allocate(len);
                for (int k = i; k < j; k++) {
                    int b = (int) (order[k] & 0xFFFFF);
                    buf.position((start[b] - start[a]) * 4096);
                    buf.put(rec[b]);
                }
                buf.clear();
                while (buf.hasRemaining()) file.write(buf, (long) start[a] * 4096L + buf.position());
            }
            i = j;
        }
    }

    @Unique
    private void sleep$writeHeaderFull() throws IOException {
        ByteBuffer h = header.duplicate();
        h.clear();
        while (h.hasRemaining()) file.write(h, h.position());
        ChunkIoWriter.headerWrites.incrementAndGet();
        ChunkIoWriter.headerBytes.addAndGet(8192);
        sleep$dirty.clear();
    }

    @Unique
    private void sleep$releaseLater() throws IOException {
        for (int i = 0; i < sleep$freeLaterN; i++) {
            usedSectors.free(getSectorNumber(sleep$freeLater[i]), getNumSectors(sleep$freeLater[i]));
            sleep$held -= getNumSectors(sleep$freeLater[i]);
        }
        sleep$freeLaterN = 0;
        for (int i = 0; i < sleep$delLaterN; i++) {
            Files.deleteIfExists(sleep$delLater[i]);
            sleep$delLater[i] = null;
        }
        sleep$delLaterN = 0;
    }

    @Unique
    private void sleep$releaseNow() throws IOException {
        for (int i = 0; i < sleep$freeNowN; i++) {
            usedSectors.free(getSectorNumber(sleep$freeNow[i]), getNumSectors(sleep$freeNow[i]));
            sleep$held -= getNumSectors(sleep$freeNow[i]);
        }
        sleep$freeNowN = 0;
        for (int i = 0; i < sleep$delNowN; i++) {
            Files.deleteIfExists(sleep$delNow[i]);
            sleep$delNow[i] = null;
        }
        sleep$delNowN = 0;
    }

    @Unique
    private static int[] sleep$push(int[] a, int at, int v) {
        if (at == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
        a[at] = v;
        return a;
    }

    @Unique
    private static Path[] sleep$push(Path[] a, int at, Path v) {
        if (at == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
        a[at] = v;
        return a;
    }
}
