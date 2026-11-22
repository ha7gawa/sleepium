package dev.lull.opt;

import dev.lull.net.NetReactor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.util.ArrayList;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NetReactor: a server connection in the PLAY protocol batches the server thread's sends (see NetReactor). Two lists
 * swap between the filler and the netty drain task, so a steady stream allocates nothing per packet (vanilla allocates a
 * task lambda per packet).
 * <p>
 * 1.20.1: the protocol is not switched by the listener (1.21.1's setupOutboundProtocol) but per packet - sendPacket
 * compares the packet's protocol with the channel's and doSendPacket switches on the event loop - and every write is a
 * writeAndFlush. So a packet is batched when it and the channel are both in PLAY on the server's side; any other packet
 * first schedules the pending batch (it cannot overtake it) and then goes as in vanilla. The drain writes the batch
 * without flushing (vanilla's doSendPacket, write instead of writeAndFlush) and flushes once.
 */
@Mixin(Connection.class)
public abstract class NetReactorConnectionMixin implements NetReactor.Batched {
    @Shadow @Final private static Logger LOGGER;
    @Shadow private Channel channel;
    @Shadow private int sentPackets;

    @Shadow
    private void doSendPacket(Packet<?> packet, @Nullable PacketSendListener sendListener, ConnectionProtocol newProtocol, ConnectionProtocol currentProtocol) {
        throw new AssertionError();
    }

    @Shadow
    private ConnectionProtocol getCurrentProtocol() {
        throw new AssertionError();
    }

    @Shadow
    public abstract PacketFlow getSending();

    @Unique private final Object sleep$lock = new Object();
    @Unique private ArrayList<Object> sleep$filling = new ArrayList<>(64);
    @Unique private ArrayList<Object> sleep$draining = new ArrayList<>(64);
    /** a drain task is queued on the event loop; dirty: on the server thread's list of connections to flush */
    @Unique private boolean sleep$scheduled, sleep$dirty;
    @Unique private final Runnable sleep$drainTask = this::sleep$drain;

    /**
     * @author probe
     * @reason NetReactor: server-thread sends in PLAY are batched per connection (one netty task and one flush per batch)
     */
    @Overwrite
    private void sendPacket(Packet<?> packet, @Nullable PacketSendListener sendListener) {
        ConnectionProtocol packetProtocol = ConnectionProtocol.getProtocolForPacket(packet);
        ConnectionProtocol current = this.getCurrentProtocol();
        this.sentPackets++;
        if (current != packetProtocol) {   // vanilla: the switch happens in doSendPacket; reads wait for it
            if (packetProtocol == null) throw new IllegalStateException("Encountered packet without set protocol: " + packet);
            LOGGER.debug("Disabled auto read");
            this.channel.config().setAutoRead(false);
        }
        if (this.channel.eventLoop().inEventLoop()) {
            this.doSendPacket(packet, sendListener, packetProtocol, current);
            return;
        }
        if (this.getSending() == PacketFlow.CLIENTBOUND) {
            if (packetProtocol == ConnectionProtocol.PLAY && current == ConnectionProtocol.PLAY) {
                Object entry = sendListener == null ? packet : new NetReactor.WithListener(packet, sendListener);
                if (NetReactor.batching()) {
                    boolean full, mark;
                    synchronized (this.sleep$lock) {
                        this.sleep$filling.add(entry);
                        full = !NetReactor.flushLater && this.sleep$filling.size() >= NetReactor.MAX_BATCH && !this.sleep$scheduled;
                        mark = !this.sleep$dirty;
                        this.sleep$dirty = true;
                    }
                    NetReactor.BATCHED.increment();
                    if (full) {
                        NetReactor.EARLY.increment();
                        this.sleep$flush();
                    } else if (mark) {
                        NetReactor.markDirty(this);
                    }
                    return;
                }
                // another thread: behind a pending batch (never ahead of it), else straight to netty as in vanilla
                boolean joined;
                synchronized (this.sleep$lock) {
                    joined = !this.sleep$filling.isEmpty() || this.sleep$scheduled;
                    if (joined) this.sleep$filling.add(entry);
                }
                if (joined) {
                    NetReactor.OTHER_THREAD.increment();
                    NetReactor.BATCHED.increment();
                    this.sleep$flush();
                    return;
                }
            } else {
                this.sleep$flush();   // a packet of another protocol (a switch): what is batched leaves before it
            }
        }
        this.channel.eventLoop().execute(() -> this.doSendPacket(packet, sendListener, packetProtocol, current));
    }

    @Override
    public void sleep$flush() {
        synchronized (this.sleep$lock) {
            this.sleep$dirty = false;
            if (this.sleep$filling.isEmpty() || this.sleep$scheduled) return;
            this.sleep$scheduled = true;
        }
        NetReactor.DRAINS.increment();
        this.channel.eventLoop().execute(this.sleep$drainTask);
    }

    /** event loop: write the whole batch, flush once */
    @Unique
    private void sleep$drain() {
        ArrayList<Object> list;
        synchronized (this.sleep$lock) {
            list = this.sleep$filling;
            this.sleep$filling = this.sleep$draining;
            this.sleep$draining = list;
            this.sleep$scheduled = false;
        }
        for (int i = 0, n = list.size(); i < n; i++) {
            Object o = list.get(i);
            if (o instanceof NetReactor.WithListener w) this.sleep$write(w.packet(), w.listener());
            else this.sleep$write((Packet<?>) o, null);
        }
        list.clear();
        this.channel.flush();
    }

    /** vanilla's doSendPacket for a packet of the channel's own protocol (no switch), without the flush */
    @Unique
    private void sleep$write(Packet<?> packet, @Nullable PacketSendListener sendListener) {
        if (dev.lull.optsupport.NetOrderVerify.ON && (Object) this instanceof dev.lull.optsupport.NetOrderVerify.Holder h)
            h.sleep$orderState().out(packet);   // (the batch drain writes without doSendPacket)
        ChannelFuture f = this.channel.write(packet);
        if (sendListener != null) {
            f.addListener(r -> {
                if (r.isSuccess()) {
                    sendListener.onSuccess();
                } else {
                    Packet<?> other = sendListener.onFailure();
                    if (other != null) this.channel.writeAndFlush(other).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                }
            });
        }
        f.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    /** the connection's tick (it flushes the channel itself) is a flush point (1.20.1 has no flushChannel) */
    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$tick(CallbackInfo ci) {
        if (NetReactor.flushLater && NetReactor.batching()) return;   // inside the tick: the tick's end flushes (NetReactor.flushLater)
        this.sleep$flush();
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void sleep$disconnect(Component message, CallbackInfo ci) {
        this.sleep$flush();
    }
}
