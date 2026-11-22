import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * Every region file under a world folder opened by vanilla's own RegionFile (unmodified class from the NeoForge jar, no
 * Sleepium) and every chunk its header lists read through getChunkDataInputStream + NbtIo.read (notes/86).
 * Its constructor's warnings (invalid / out of bounds sectors, truncated header) go to the log on stderr.
 *   java -cp <neoforge jar + libraries> tools/VanillaRead.java <world dir>
 */
public class VanillaRead {
    public static void main(String[] a) throws Exception {
        int files = 0, listed = 0, read = 0, nulls = 0, failed = 0, tails = 0;
        List<Path> mca;
        try (var w = Files.walk(Path.of(a[0]))) {
            mca = w.filter(p -> p.toString().endsWith(".mca")).sorted().toList();
        }
        for (Path p : mca) {
            String[] n = p.getFileName().toString().split("\\.");
            int rx = Integer.parseInt(n[1]), rz = Integer.parseInt(n[2]);
            byte[] head = new byte[8192];
            long size = Files.size(p);
            if (size == 0) continue;
            try (var in = Files.newInputStream(p)) { in.readNBytes(head, 0, 8192); }
            long usedEnd = 8192;
            ByteBuffer hb = ByteBuffer.wrap(head);
            for (int i = 0; i < 1024; i++) {
                int o = hb.getInt(i * 4);
                if (o != 0) usedEnd = Math.max(usedEnd, ((long) (o >>> 8) + (o & 0xFF)) * 4096);
            }
            if (size - usedEnd >= 65536) tails++;
            files++;
            try (RegionFile r = new RegionFile(new RegionStorageInfo("check", null, p.getParent().getFileName().toString()), p, p.getParent(), false)) {
                for (int i = 0; i < 1024; i++) {
                    ChunkPos pos = new ChunkPos(rx * 32 + (i & 31), rz * 32 + (i >> 5));
                    if (!r.hasChunk(pos)) continue;
                    listed++;
                    try (DataInputStream in = r.getChunkDataInputStream(pos)) {
                        if (in == null) { nulls++; continue; }
                        CompoundTag t = NbtIo.read(in);
                        if (t == null) nulls++;
                        else read++;
                    } catch (Exception e) {
                        failed++;
                        if (failed <= 10) System.out.println("  " + p + " " + pos + ": " + e);
                    }
                }
            }
        }
        System.out.printf("vanilla RegionFile: %d files (%d with >= 64 KB of unreferenced tail), %d chunks listed: %d read, %d null, %d failed%n",
                files, tails, listed, read, nulls, failed);
    }
}
