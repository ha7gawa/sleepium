package dev.lull.opt;

import dev.lull.net.NetReactor;
import io.netty.channel.Channel;
import java.util.ArrayList;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
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
 */
@Mixin(Connection.class)
public abstract class NetReactorConnectionMixin implements NetReactor.Batched {
    @Shadow private Channel channel;
    @Shadow private int sentPackets;

    @Shadow
    private void doSendPacket(Packet<?> packet, @Nullable PacketSendListener sendListener, boolean flush) {
        throw new AssertionError();
    }

    @Shadow
    public abstract PacketFlow getSending();

    @Shadow
    public abstract boolean isConnected();

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush);

    @Shadow
    private void flushQueue() {
        throw new AssertionError();
    }

    @Override
    public boolean sleep$sendAsServer(Packet<?> packet, @Nullable PacketSendListener sendListener, boolean flush) {
        // Connection.send + this mixin's sendPacket as the server thread inside the tick runs them (batching() true,
        // not the event loop); every other case is the plain send, the same from any thread
        if (!this.isConnected() || !this.sleep$play || this.channel.eventLoop().inEventLoop()) {
            this.send(packet, sendListener, flush);
            return false;
        }
        this.flushQueue();
        this.sentPackets++;
        Object entry = sendListener == null ? packet : new NetReactor.WithListener(packet, sendListener);
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
            return false;
        }
        return mark;
    }

    @Override
    public Object[] sleep$batchTail(int n) {
        synchronized (this.sleep$lock) {
            int size = this.sleep$filling.size();
            if (size < n) return null;
            Object[] out = new Object[n];
            for (int i = 0; i < n; i++) {
                Object o = this.sleep$filling.get(size - n + i);
                out[i] = o instanceof NetReactor.WithListener w ? w.packet() : o;
            }
            return out;
        }
    }

    @Unique private final Object sleep$lock = new Object();
    @Unique private ArrayList<Object> sleep$filling = new ArrayList<>(64);
    @Unique private ArrayList<Object> sleep$draining = new ArrayList<>(64);
    /** a drain task is queued on the event loop; dirty: on the server thread's list of connections to flush */
    @Unique private boolean sleep$scheduled, sleep$dirty;
    /** the outbound protocol is PLAY and this is the server's side (sending to a client) */
    @Unique private volatile boolean sleep$play;
    @Unique private final Runnable sleep$drainTask = this::sleep$drain;

    /**
     * @author probe
     * @reason NetReactor: server-thread sends in PLAY are batched per connection (one netty task and one flush per batch)
     */
    @Overwrite
    private void sendPacket(Packet<?> packet, @Nullable PacketSendListener sendListener, boolean flush) {
        this.sentPackets++;
        if (this.channel.eventLoop().inEventLoop()) {
            this.doSendPacket(packet, sendListener, flush);
            return;
        }
        if (this.sleep$play) {
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
        }
        this.channel.eventLoop().execute(() -> this.doSendPacket(packet, sendListener, flush));
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
            if (o instanceof NetReactor.WithListener w) this.doSendPacket(w.packet(), w.listener(), false);
            else this.doSendPacket((Packet<?>) o, null, false);
        }
        list.clear();
        this.channel.flush();
    }

    /** packets queued under the old protocol leave before the switch; batching only in PLAY on the server side */
    @Inject(method = "setupOutboundProtocol", at = @At("HEAD"))
    private void sleep$protocol(ProtocolInfo<?> info, CallbackInfo ci) {
        this.sleep$flush();
        this.sleep$play = info.id() == ConnectionProtocol.PLAY && this.getSending() == PacketFlow.CLIENTBOUND;
    }

    @Inject(method = {"tick", "flushChannel"}, at = @At("HEAD"))
    private void sleep$tick(CallbackInfo ci) {
        if (NetReactor.flushLater && NetReactor.batching()) return;   // inside the tick: the tick's end flushes (NetReactor.flushLater)
        this.sleep$flush();
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void sleep$disconnect(net.minecraft.network.DisconnectionDetails details, CallbackInfo ci) {
        this.sleep$flush();
    }
}
