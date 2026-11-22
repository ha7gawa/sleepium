package dev.lull.bots;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.SingleThreadEventLoop;
import java.net.SocketAddress;

/**
 * A bot's "socket": a netty channel registered on the server's own event loop group (the threads a TCP connection
 * would use, {@code Netty Server IO}), so everything vanilla's pipeline does for a real player - encoding, compression,
 * encryption, the frame prefix - runs where it runs for a real player and costs what it costs. The bytes that come out
 * of the pipeline are counted and dropped (a socket write without the system call). Not a LocalChannel on purpose:
 * vanilla treats those as the singleplayer memory connection (no compression, all pending chunks in one batch).
 * Never reads: what the bot "receives" is seen as packet objects by {@link BotLoad}'s tap before encoding.
 */
final class SinkChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final SocketAddress local, remote;
    private volatile boolean open = true;
    /** written on the channel's event loop only */
    volatile long bytes, frames;

    SinkChannel(SocketAddress local, SocketAddress remote) {
        super(null);
        this.local = local;
        this.remote = remote;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                promise.setFailure(new UnsupportedOperationException("a bot's sink channel does not connect"));
            }
        };
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof SingleThreadEventLoop;
    }

    @Override
    protected SocketAddress localAddress0() {
        return local;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remote;
    }

    @Override
    protected void doBind(SocketAddress localAddress) {}

    @Override
    protected void doDisconnect() {
        open = false;
    }

    @Override
    protected void doClose() {
        open = false;
    }

    @Override
    protected void doBeginRead() {}

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        long b = bytes, f = frames;
        for (;;) {
            Object m = in.current();
            if (m == null) break;
            if (m instanceof ByteBuf buf) {
                b += buf.readableBytes();
                f++;
            }
            in.remove();   // releases the message
        }
        bytes = b;
        frames = f;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isActive() {
        return open;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }
}
