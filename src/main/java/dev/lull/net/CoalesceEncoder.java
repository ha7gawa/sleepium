package dev.lull.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.zip.Deflater;

/**
 * Server side of {@link Coalesce}, between the frame prepender and encryption: frames written before a flush are
 * appended to one buffer and leave, at the flush, as one container deflated by the connection's own stream. Event loop
 * only; the pending buffer and the scratch array are reused for the connection's life.
 *
 * <p>A container always ends on a frame boundary. The buffers that come here are not always whole frames: Krypton's
 * prepender writes a frame's length and its body as two buffers. Up to 1.2.3 a large body (stored as it is) closed the
 * container holding its length, and the client found the frame cut in two (GitHub issue #7, after 1.2.3). Now the
 * frames' lengths are followed (a small state machine over the length prefixes, bodies skipped whole): a buffer is
 * stored alone only when it is whole frames, and a container is closed only between frames.
 */
final class CoalesceEncoder extends ChannelOutboundHandlerAdapter {
    volatile boolean on;
    private final Deflater deflater = new Deflater(Coalesce.LEVEL);
    private ByteBuf pending;
    private final ArrayList<ChannelPromise> promises = new ArrayList<>();
    private final byte[] scratch = new byte[64 * 1024];

    /** bytes of the current frame's body still to come (0 between frames) */
    private long need;
    /** a length prefix being read across buffers: its value so far and the next shift (0: not inside a prefix) */
    private int prefix, shift;

    private boolean boundary() {
        return need == 0 && shift == 0;
    }

    /** follows the frames through these bytes (a body is skipped whole; a prefix is read byte by byte) */
    private void follow(ByteBuf b) {
        int i = b.readerIndex(), end = b.writerIndex();
        while (i < end) {
            if (need > 0) {
                long k = Math.min(need, end - i);
                need -= k;
                i += (int) k;
                continue;
            }
            byte x = b.getByte(i++);
            prefix |= (x & 0x7F) << shift;
            if ((x & 0x80) != 0) {
                shift += 7;
                if (shift > 28) throw new io.netty.handler.codec.EncoderException("sleep coalesce: a frame length prefix over 5 bytes");
                continue;
            }
            need = prefix & 0xFFFFFFFFL;
            prefix = shift = 0;
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!on || !(msg instanceof ByteBuf b)) {
            ctx.write(msg, promise);
            return;
        }
        int n = b.readableBytes();
        Coalesce.FRAME_BYTES.add(n);
        boolean wasBoundary = boundary();
        follow(b);
        if (n >= Coalesce.RAW_LIMIT && wasBoundary && boundary()) {   // whole frames, large (already packed by vanilla compression): stored as they are, in order
            flushPending(ctx);
            ByteBuf head = ctx.alloc().buffer(6);
            Coalesce.writeVarInt(head, n + 1);
            head.writeByte(0);
            Coalesce.WIRE_BYTES.add(head.readableBytes() + n);
            Coalesce.CONTAINERS.increment();
            Coalesce.STORED.increment();
            ctx.write(head);
            ctx.write(b, promise);
            return;
        }
        if (pending == null) pending = ctx.alloc().directBuffer(16 * 1024);
        pending.writeBytes(b);
        b.release();
        promises.add(promise);
        if (pending.readableBytes() >= Coalesce.MAX_PENDING && boundary()) flushPending(ctx);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (boundary()) flushPending(ctx);   // (inside a frame: its rest comes with the next writes, the container waits for it)
        ctx.flush();
    }

    private void flushPending(ChannelHandlerContext ctx) {
        if (pending == null || !pending.isReadable()) return;
        ByteBuf body = ctx.alloc().buffer(pending.readableBytes() / 2 + 64);
        ByteBuffer in = pending.nioBuffer();
        deflater.setInput(in);
        int k;
        do {   // SYNC_FLUSH: everything so far is decodable; call again while the scratch came back full
            k = deflater.deflate(scratch, 0, scratch.length, Deflater.SYNC_FLUSH);
            body.writeBytes(scratch, 0, k);
        } while (k == scratch.length || in.hasRemaining());
        pending.clear();
        ByteBuf head = ctx.alloc().buffer(6);
        Coalesce.writeVarInt(head, body.readableBytes() + 1);
        head.writeByte(1);
        Coalesce.WIRE_BYTES.add(head.readableBytes() + body.readableBytes());
        Coalesce.CONTAINERS.increment();
        ctx.write(head);
        if (promises.isEmpty()) {
            ctx.write(body);
            return;
        }
        ChannelPromise[] ps = promises.toArray(new ChannelPromise[0]);
        promises.clear();
        ctx.write(body).addListener(f -> {
            for (ChannelPromise p : ps) {
                if (f.isSuccess()) p.trySuccess();
                else p.tryFailure(f.cause());
            }
        });
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (pending != null) {
            pending.release();
            pending = null;
        }
        deflater.end();
    }
}
