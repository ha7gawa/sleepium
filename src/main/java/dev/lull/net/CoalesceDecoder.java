package dev.lull.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Client side of {@link Coalesce}, right after the frame splitter: it sees whole frames, decrypted by whatever cipher
 * stands in front of the splitter (vanilla's, Krypton's native one, ...). Until the server's marker frame it passes
 * every frame on; after the marker each "frame" is a container (flag + frames, deflated by the connection's one
 * stream) and it hands on the plain frames inside it. One inflater lives as long as the connection.
 *
 * <p>Up to 1.2.2 it sat before the splitter and cut the stream into frames itself, so it had to stand behind the
 * cipher: it was moved there when vanilla's setEncryptionKey ran. A mod that installs its cipher another way (Krypton:
 * its own setupEncryption) left it in front, reading encrypted bytes as lengths - "VarInt too long" (GitHub issue #7).
 * After the splitter its place no longer depends on how a cipher got in. The wire is the same as before. A frame a
 * container ends inside (a 1.2.3 server whose prepender writes a length and a body apart: Krypton) waits for its rest.
 */
public final class CoalesceDecoder extends MessageToMessageDecoder<ByteBuf> {
    private boolean on;
    private final Inflater inflater = new Inflater();
    private final byte[] scratch = new byte[64 * 1024];
    private final int[] len = new int[1];
    /** the most a container may inflate to (a broken or hostile stream cannot make the client allocate more) */
    private static final int MAX_OUT = 8 << 20;
    /** the start of a frame a container ended inside (a server whose frames came as length + body buffers: Krypton), kept for the next container */
    private ByteBuf carry;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) throws Exception {
        if (!on) {
            if (isMagic(frame)) {
                on = true;   // the marker itself goes no further
                System.out.println("[sleep/net] coalesce: the server switched this connection to containers");
                return;
            }
            out.add(frame.retain());   // a plain frame, as the splitter cut it
            return;
        }
        if (!frame.isReadable()) throw new CorruptedFrameException("sleep coalesce: empty container");
        byte flag = frame.readByte();
        ByteBuf frames;
        if (flag == 0) frames = frame.retainedSlice();
        else if (flag == 1) frames = inflate(ctx, frame);
        else throw new CorruptedFrameException("sleep coalesce: container flag " + flag);
        if (carry != null && carry.isReadable()) {   // the rest of a frame the last container began: joined, then read from the copy
            carry.writeBytes(frames);
            frames.release();
            frames = carry;
        }
        try {
            while (frames.isReadable()) {   // the container's frames, each with its length: the bodies go on
                int n = Coalesce.peekVarInt(frames, len);
                if (n < 0 || frames.readableBytes() < len[0] + n) break;   // cut: its rest comes in the next container
                frames.skipBytes(len[0]);
                out.add(frames == carry ? frames.readBytes(n) : frames.readRetainedSlice(n));   // (the carry is reused: copies from it)
            }
            if (frames.isReadable()) {
                if (frames.readableBytes() > MAX_OUT) throw new CorruptedFrameException("sleep coalesce: a frame over " + MAX_OUT + " bytes across containers");
                if (frames != carry) {
                    if (carry == null) carry = ctx.alloc().heapBuffer(Math.max(256, frames.readableBytes() * 2));
                    carry.writeBytes(frames);
                } else carry.discardReadBytes();
            } else if (carry != null) carry.clear();
        } finally {
            if (frames != carry) frames.release();
        }
    }

    private static boolean isMagic(ByteBuf frame) {
        if (frame.readableBytes() != Coalesce.MAGIC.length) return false;
        int at = frame.readerIndex();
        for (int i = 0; i < Coalesce.MAGIC.length; i++) if (frame.getByte(at + i) != Coalesce.MAGIC[i]) return false;
        return true;
    }

    private ByteBuf inflate(ChannelHandlerContext ctx, ByteBuf in) throws DataFormatException {
        int n = in.readableBytes();
        inflater.setInput(in.nioBuffer(in.readerIndex(), n));
        in.skipBytes(n);
        ByteBuf outBuf = ctx.alloc().buffer(Math.max(256, n * 4));
        while (true) {
            int k = inflater.inflate(scratch);
            if (k > 0) {
                outBuf.writeBytes(scratch, 0, k);
                if (outBuf.readableBytes() > MAX_OUT) {
                    outBuf.release();
                    throw new CorruptedFrameException("sleep coalesce: container over " + MAX_OUT + " bytes");
                }
            }
            if (k < scratch.length && inflater.needsInput()) return outBuf;
            if (k == 0 && (inflater.needsDictionary() || inflater.finished())) {
                outBuf.release();
                throw new CorruptedFrameException("sleep coalesce: bad deflate stream");
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        inflater.end();
        if (carry != null) {
            carry.release();
            carry = null;
        }
        super.handlerRemoved(ctx);
    }
}
