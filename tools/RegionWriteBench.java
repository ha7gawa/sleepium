import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * Region-file write patterns outside the game (notes/86): a stream of chunk records (random bytes, 1-2 sectors, a
 * share of re-saves of already stored positions) written into several region files the way the game's writer would,
 * with vanilla's RegionBitmap first-fit allocation, so file growth and sector reuse are as in the game.
 *
 *   java tools/RegionWriteBench.java <dir> <seconds> <chunks/s, 0 = as fast as possible> [options]
 *     sync=vanilla|wt|batch|cadence   vanilla: DSYNC, record + 8 KB header per chunk; wt: DSYNC, per batch and file the
 *                                  records, then the header (write-through batch); batch: ChunkIo 1.3.3 (per batch and
 *                                  file: records, force, header, force); cadence: per file at most one round per interval
 *                                  (force, header, [force]); in between records are only written
 *     interval=500                 cadence round interval, ms
 *     header=full|delta            8 KB header, or only the changed 4-byte entries (offsets and timestamps), coalesced
 *     hforce=false                 cadence: force after the header too (else the next round's force makes it durable)
 *     grow=0                       extend a file in steps of N KB of zeros (0: the records extend it)
 *     coalesce=false               contiguous records of a batch in one write
 *     md=true                      force(md)
 *     files=4 batch=4 resave=0.3   active files (round robin), chunks per file per batch (rate 0), re-save share
 */
public class RegionWriteBench {
    static String sync = "batch", header = "full";
    static int interval = 500, growKb = 0, files = 4, batchN = 4;
    static boolean hforce = false, coalesce = false, md = true;
    static double resave = 0.3;
    static long forces, forceNs, forceMax, dataWrites, headerWrites, headerBytes, dataBytes, extends_, growBytes, rounds;

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a[0]);
        double secs = Double.parseDouble(a[1]);
        double rate = Double.parseDouble(a[2]);
        for (int i = 3; i < a.length; i++) {
            String[] kv = a[i].split("=", 2);
            switch (kv[0]) {
                case "sync" -> sync = kv[1];
                case "interval" -> interval = Integer.parseInt(kv[1]);
                case "header" -> header = kv[1];
                case "hforce" -> hforce = Boolean.parseBoolean(kv[1]);
                case "grow" -> growKb = Integer.parseInt(kv[1]);
                case "coalesce" -> coalesce = Boolean.parseBoolean(kv[1]);
                case "md" -> md = Boolean.parseBoolean(kv[1]);
                case "files" -> files = Integer.parseInt(kv[1]);
                case "batch" -> batchN = Integer.parseInt(kv[1]);
                case "resave" -> resave = Double.parseDouble(kv[1]);
                default -> throw new IllegalArgumentException(a[i]);
            }
        }
        Files.createDirectories(dir);
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) if (p.getFileName().toString().startsWith("wb.")) Files.delete(p);
        }
        Random rnd = new Random(42);
        byte[] noise = new byte[1 << 20];
        rnd.nextBytes(noise);
        List<Region> open = new ArrayList<>();
        int nextFile = 0;
        for (int i = 0; i < files; i++) open.add(new Region(dir.resolve("wb." + nextFile++ + ".mca")));
        long t0 = System.nanoTime(), end = t0 + (long) (secs * 1e9), busy = 0;
        long chunks = 0;
        double due = 0;
        int rr = 0;
        List<List<byte[]>> recs = new ArrayList<>();
        List<List<Integer>> idx = new ArrayList<>();
        for (int i = 0; i < files; i++) { recs.add(new ArrayList<>()); idx.add(new ArrayList<>()); }
        while (System.nanoTime() < end) {
            int n;
            long now = System.nanoTime();
            if (rate > 0) {
                due = rate * (now - t0) / 1e9 - chunks;
                n = (int) due;
                if (n == 0) {
                    for (Region r : open) r.idle(now);
                    Thread.sleep(2);
                    continue;
                }
            } else {
                n = batchN * files;
            }
            long b0 = System.nanoTime();
            for (int k = 0; k < n; k++) {
                int f = rate > 0 ? rnd.nextInt(files) : (k / batchN);
                Region r = open.get(f);
                int slot;
                if (r.filled > 0 && rnd.nextDouble() < resave) slot = rnd.nextInt(r.filled);
                else if (r.filled < 1024) slot = r.filled++;
                else slot = rnd.nextInt(1024);
                int len = 1500 + rnd.nextInt(4500);   // 1-2 sectors, ~3.7 KB mean
                byte[] rec = new byte[len];
                System.arraycopy(noise, rnd.nextInt(noise.length - len), rec, 0, len);
                ByteBuffer.wrap(rec).putInt(0, len - 4);
                rec[4] = 2;
                recs.get(f).add(rec);
                idx.get(f).add(slot);
            }
            for (int f = 0; f < files; f++) {
                if (recs.get(f).isEmpty()) continue;
                open.get(f).writeBatch(recs.get(f), idx.get(f));
                chunks += recs.get(f).size();
                recs.get(f).clear();
                idx.get(f).clear();
            }
            for (Region r : open) r.idle(System.nanoTime());
            busy += System.nanoTime() - b0;
            for (int f = 0; f < files; f++) {   // a full file is closed, a new one opened (the generation front moves on)
                if (open.get(f).filled >= 1024 && rnd.nextDouble() < 0.02) {
                    open.get(f).close();
                    open.set(f, new Region(dir.resolve("wb." + nextFile++ + ".mca")));
                }
            }
        }
        long tc = System.nanoTime();
        for (Region r : open) r.close();
        long t1 = System.nanoTime();
        double el = (t1 - t0) / 1e9;
        System.out.printf("%s header=%s interval=%d hforce=%s grow=%dK coalesce=%s md=%s rate=%s: %d chunks in %.1f s = %.0f chunks/s, writer busy %.1f %% (%.3f ms/chunk), close %.0f ms%n",
                sync, header, interval, hforce, growKb, coalesce, md, rate > 0 ? (int) rate + "/s" : "max", chunks, el, chunks / el, 100.0 * busy / (tc - t0), busy / 1e6 / chunks, (t1 - tc) / 1e6);
        System.out.printf("   forces %d (%.1f/s, %.2f ms avg, max %.1f), data writes %d (%.1f KB avg), header writes %d (%.0f B avg), extends %d (%.1f MB zeros), files %d%n",
                forces, forces / el, forces == 0 ? 0 : forceNs / 1e6 / forces, forceMax / 1e6, dataWrites, dataWrites == 0 ? 0 : dataBytes / 1024.0 / dataWrites,
                headerWrites, headerWrites == 0 ? 0 : headerBytes / (double) headerWrites, extends_, growBytes / 1048576.0, nextFile);
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) if (p.getFileName().toString().startsWith("wb.")) Files.delete(p);
        }
    }

    static final class Region {
        final FileChannel ch;
        final ByteBuffer hdr = ByteBuffer.allocateDirect(8192);
        final BitSet used = new BitSet(), dirty = new BitSet(1024);
        final List<int[]> freeNow = new ArrayList<>(), freeAfterForce = new ArrayList<>();
        long size, lastRound = System.nanoTime();
        int filled;
        boolean pending;
        static final ByteBuffer ZERO = ByteBuffer.allocateDirect(4 << 20);

        Region(Path p) throws IOException {
            ch = "vanilla".equals(sync) || "wt".equals(sync) ? FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DSYNC)
                    : FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            used.set(0, 2);
            size = ch.size();
            hdr.position(0);
            ch.write(hdr, 0);   // a new region file: vanilla writes the header with the first chunk; here up front
            size = Math.max(size, 8192);
        }

        int allocate(int n) {   // RegionBitmap.allocate: first fit
            int i = 0;
            while (true) {
                int s = used.nextClearBit(i), e = used.nextSetBit(s);
                if (e == -1 || e - s >= n) {
                    used.set(s, s + n);
                    return s;
                }
                i = e;
            }
        }

        void writeBatch(List<byte[]> recs, List<Integer> idx) throws IOException {
            int n = recs.size();
            int[] start = new int[n], cnt = new int[n];
            for (int i = 0; i < n; i++) {
                cnt[i] = (recs.get(i).length + 4095) / 4096;
                start[i] = allocate(cnt[i]);
            }
            long need = 0;
            for (int i = 0; i < n; i++) need = Math.max(need, (long) (start[i] + cnt[i]) * 4096);
            if (growKb > 0 && need > size) {
                long step = growKb * 1024L, to = (need + step - 1) / step * step;
                long pos = size;
                while (pos < to) {
                    ByteBuffer z = ZERO.duplicate().clear().limit((int) Math.min(ZERO.capacity(), to - pos));
                    pos += ch.write(z, pos);
                }
                extends_++;
                growBytes += to - size;
                size = to;
            }
            if (coalesce) {
                Integer[] order = new Integer[n];
                for (int i = 0; i < n; i++) order[i] = i;
                Arrays.sort(order, Comparator.comparingInt(i -> start[i]));
                int i = 0;
                while (i < n) {
                    int j = i + 1;
                    while (j < n && start[order[j]] == start[order[j - 1]] + cnt[order[j - 1]]) j++;
                    int sectors = start[order[j - 1]] + cnt[order[j - 1]] - start[order[i]];
                    ByteBuffer buf = ByteBuffer.allocate(sectors * 4096);
                    for (int k = i; k < j; k++) {
                        buf.position((start[order[k]] - start[order[i]]) * 4096);
                        buf.put(recs.get(order[k]));
                    }
                    int last = (start[order[j - 1]] - start[order[i]]) * 4096 + recs.get(order[j - 1]).length;
                    buf.position(0).limit(last);
                    ch.write(buf, (long) start[order[i]] * 4096);
                    dataWrites++;
                    dataBytes += last;
                    i = j;
                }
            } else {
                for (int i = 0; i < n; i++) {
                    ch.write(ByteBuffer.wrap(recs.get(i)), (long) start[i] * 4096);
                    dataWrites++;
                    dataBytes += recs.get(i).length;
                }
            }
            for (int i = 0; i < n; i++) size = Math.max(size, (long) start[i] * 4096 + recs.get(i).length);
            int ts = (int) (System.currentTimeMillis() / 1000);
            for (int i = 0; i < n; i++) {
                int x = idx.get(i);
                int old = hdr.getInt(x * 4);
                if (old != 0) freeNow.add(new int[] {old >>> 8, old & 0xFF});
                hdr.putInt(x * 4, start[i] << 8 | cnt[i]);
                hdr.putInt(4096 + x * 4, ts);
                dirty.set(x);
                if ("vanilla".equals(sync)) {   // vanilla: one chunk at a time, the whole header each
                    writeHeader();
                    release(freeNow);
                }
            }
            if ("wt".equals(sync)) {   // write-through batch: the records were acknowledged by the device; then the header
                writeHeader();
                release(freeNow);
            } else if ("batch".equals(sync)) {
                force();
                writeHeader();
                force();
                release(freeNow);
            } else if ("cadence".equals(sync)) {
                pending = true;
            }
        }

        /** cadence: a round when the interval is over and something is not yet durable */
        void idle(long now) throws IOException {
            if (!"cadence".equals(sync) || !pending || now - lastRound < interval * 1_000_000L) return;
            round(hforce);
            lastRound = now;
        }

        void round(boolean headerForce) throws IOException {
            rounds++;
            force();                   // the records since the last round, and the last round's header, are durable
            release(freeAfterForce);   // sectors the now durable header no longer points at
            boolean wrote = !dirty.isEmpty();
            writeHeader();
            freeAfterForce.addAll(freeNow);
            freeNow.clear();
            if (headerForce) {
                force();
                release(freeAfterForce);
            }
            pending = wrote && !headerForce || !freeAfterForce.isEmpty();
        }

        void writeHeader() throws IOException {
            if (dirty.isEmpty()) return;
            if ("full".equals(header)) {
                hdr.clear();
                ch.write(hdr, 0);
                headerWrites++;
                headerBytes += 8192;
            } else {
                for (int base : new int[] {0, 4096}) {   // offsets, then timestamps: one write per contiguous run
                    for (int i = dirty.nextSetBit(0); i >= 0; ) {
                        int j = dirty.nextClearBit(i);
                        ByteBuffer b = hdr.duplicate().position(base + i * 4).limit(base + j * 4);
                        ch.write(b, base + i * 4L);
                        headerWrites++;
                        headerBytes += (j - i) * 4L;
                        i = dirty.nextSetBit(j);
                    }
                }
            }
            dirty.clear();
        }

        void release(List<int[]> l) {
            for (int[] r : l) used.clear(r[0], r[0] + r[1]);
            l.clear();
        }

        void force() throws IOException {
            long t = System.nanoTime();
            ch.force(md);
            t = System.nanoTime() - t;
            forces++;
            forceNs += t;
            forceMax = Math.max(forceMax, t);
        }

        void close() throws IOException {
            if ("cadence".equals(sync)) {
                force();
                writeHeader();
                force();
            }
            ch.close();
        }
    }
}
