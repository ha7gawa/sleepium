package dev.lull.optsupport;

import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * -Dlab.opt.SaveStreams (ChunkIo's encode on the pool): a chunk record made through streams each pool thread keeps.
 *
 * <p>Vanilla's chain per chunk - DataOutputStream(BufferedOutputStream(DeflaterOutputStream(buffer))), a new Deflater,
 * an 8 KB stream buffer, a 512-byte deflate buffer, a buffer for the record growing from 8 KB by copies, a byte array
 * per DataOutputStream for writeUTF - is here the same JDK classes, made once per thread: the DataOutputStream (its
 * writeUTF array reused), the BufferedOutputStream (8 KB), a DeflaterOutputStream over a Deflater of default settings
 * that is reset after each record (deflateReset: as a new one), and a record buffer that is reset, not reallocated.
 * The streams are never closed: the end of a record is vanilla's close sequence's effect - flush the buffered bytes
 * into the deflater, then finish it. The deflater therefore gets the very same input calls as vanilla's, so the same
 * output. Only the record's exact-size copy is new.
 *
 * <p>The tag itself is written as NbtIo.write does (through NbtIo's StringFallbackDataOutput) except that a compound's
 * entries are walked with HashMap.forEach - the same order as its key set's iterator, the same bucket chains - instead
 * of a key set object and a lookup per key; a ListTag's type is set first, as ListTag.write does. Anything that is not
 * exactly a CompoundTag over a HashMap or exactly a ListTag writes itself (tag.write). Only the deflate format
 * (the default region compression) takes this way; other region versions are encoded as before.
 *
 * <p>{@code -Dlab.chunkio.reuseVerify=true}: every record also made the vanilla way and compared byte for byte.
 */
public final class SaveStreams {
    private SaveStreams() {}

    public static final boolean ON = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.SaveStreams");
    public static final boolean VERIFY = Boolean.getBoolean("lab.chunkio.reuseVerify");
    /** a thread's record buffer past this is let go after the record (a huge chunk's buffer not kept) */
    private static final int KEEP_BUFFER = 1 << 20;

    public static final AtomicLong records = new AtomicLong(), verified = new AtomicLong(), mismatches = new AtomicLong();

    private static final ThreadLocal<Encoder> ENCODER = new ThreadLocal<>();

    /** null when this way does not apply (another region version): the caller encodes as before */
    public static byte[] encode(CompoundTag tag, RegionFileVersion version) throws IOException {
        // (a virtual thread's BufferedOutputStream starts at 512 bytes and grows: other flush points - not this way)
        if (version != RegionFileVersion.VERSION_DEFLATE || Thread.currentThread().isVirtual()) return null;
        Encoder e = ENCODER.get();
        if (e == null || e.used) e = new Encoder();   // (used: a record of this thread failed half way, or a re-entry)
        ENCODER.set(e);
        byte[] r = e.encode(tag, version.getId());
        records.incrementAndGet();
        if (VERIFY) {
            verified.incrementAndGet();
            if (!java.util.Arrays.equals(r, ChunkIo.encodeVanilla(tag, version)) && mismatches.incrementAndGet() <= 5)
                System.out.println("[sleep/opt] SaveStreams: a chunk record DIFFERS from the vanilla encode");
        }
        return r;
    }

    private static final class Record extends ByteArrayOutputStream {
        Record() {
            super(8096);
        }

        int capacity() {
            return buf.length;
        }
    }

    private static final class Encoder implements BiConsumer<String, Tag> {
        Record record = new Record();
        final Deflater deflater = new Deflater();
        final SwitchableSink sink = new SwitchableSink();
        final DeflaterOutputStream deflate = new DeflaterOutputStream(sink, deflater);   // 512-byte buffer, no sync flush: as vanilla's
        final BufferedOutputStream buffered = new BufferedOutputStream(deflate);         // 8 KB, as RegionFileVersion's
        final DataOutputStream data = new DataOutputStream(buffered);
        final DataOutput out = new NbtIo.StringFallbackDataOutput(data);
        boolean used;

        byte[] encode(CompoundTag tag, int versionId) throws IOException {
            used = true;
            Record b = record;
            b.reset();
            b.write(0);
            b.write(0);
            b.write(0);
            b.write(0);
            b.write(versionId);
            sink.to = b;
            // NbtIo.write -> writeUnnamedTagWithFallback -> writeUnnamedTag
            out.writeByte(tag.getId());
            out.writeUTF("");
            try {
                write(tag);
            } catch (UncheckedIOException u) {
                throw u.getCause();
            }
            // vanilla's close: the buffered bytes into the deflater (flush), then finish
            data.flush();
            deflate.finish();
            deflater.reset();
            byte[] r = b.toByteArray();
            ByteBuffer.wrap(r).putInt(0, r.length - 5 + 1);
            if (b.capacity() > KEEP_BUFFER) record = new Record();
            used = false;
            return r;
        }

        /** tag.write(out), compounds and lists walked here */
        void write(Tag t) throws IOException {
            if (t.getClass() == CompoundTag.class) {
                Map<String, Tag> m = ((dev.lull.opt.SaveStreamsCompoundAccessor) t).sleep$tags();
                if (m.getClass() == HashMap.class) {
                    m.forEach(this);
                    out.writeByte(0);
                    return;
                }
            } else if (t.getClass() == ListTag.class) {
                dev.lull.opt.SaveStreamsListAccessor a = (dev.lull.opt.SaveStreamsListAccessor) t;
                List<Tag> list = a.sleep$list();
                byte type = list.isEmpty() ? 0 : list.get(0).getId();
                a.sleep$setType(type);
                out.writeByte(type);
                out.writeInt(list.size());
                for (int i = 0, n = list.size(); i < n; i++) write(list.get(i));
                return;
            }
            t.write(out);
        }

        /** CompoundTag.writeNamedTag */
        @Override
        public void accept(String name, Tag tag) {
            try {
                byte id = tag.getId();
                out.writeByte(id);
                if (id != 0) {
                    out.writeUTF(name);
                    write(tag);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** the deflater's sink: the current record (the stream objects stay, the record buffer may be replaced) */
    private static final class SwitchableSink extends java.io.OutputStream {
        ByteArrayOutputStream to;

        @Override
        public void write(int b) {
            to.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            to.write(b, off, len);
        }
    }

    public static String report() {
        return "SaveStreams: " + records + " records" + (VERIFY ? ", verified " + verified + ", differing " + mismatches : "");
    }
}
