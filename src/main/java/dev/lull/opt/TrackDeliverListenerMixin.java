package dev.lull.opt;

import dev.lull.net.NetReactor;
import dev.lull.optsupport.TrackDeliver;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * lab.opt.TrackDeliver: a server-thread send inside the tracker is logged (after NeoForge's checkPacket, where vanilla
 * runs it) and delivered at the tracker's end per connection (optsupport/TrackDeliver); every other send is vanilla's.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class TrackDeliverListenerMixin implements TrackDeliver.Sender {
    @Shadow @Final protected Connection connection;
    @Shadow @Final protected MinecraftServer server;
    @Shadow private volatile boolean suspendFlushingOnServerThread;

    @Shadow
    private void close() {
        throw new AssertionError();
    }

    @Unique private int sleep$slot, sleep$slotGen;

    /**
     * @author sleepium (lab TrackDeliver)
     * @reason the tracker's sends logged for delivery per connection; otherwise vanilla's body
     */
    @Overwrite
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        net.neoforged.neoforge.network.registration.NetworkRegistry.checkPacket(packet, (ServerCommonPacketListenerImpl) (Object) this);
        if (TrackDeliver.capturing && Thread.currentThread() == TrackDeliver.serverThread) {
            if (!packet.isTerminal()) {
                // the flag the server thread computes here (isSameThread: true)
                TrackDeliver.capture(this, packet, listener, !this.suspendFlushingOnServerThread);
                return;
            }
            TrackDeliver.drainSerial();   // what was logged goes first
        }
        if (packet.isTerminal()) {
            this.close();
        }
        boolean flag = !this.suspendFlushingOnServerThread || !this.server.isSameThread();
        try {
            this.connection.send(packet, listener, flag);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.forThrowable(throwable, "Sending packet");
            CrashReportCategory crashreportcategory = crashreport.addCategory("Packet being sent");
            crashreportcategory.setDetail("Packet class", () -> packet.getClass().getCanonicalName());
            throw new ReportedException(crashreport);
        }
    }

    @Override
    public int sleep$slot(int gen) {
        if (this.sleep$slotGen != gen) {
            this.sleep$slotGen = gen;
            this.sleep$slot = TrackDeliver.newSlot(this);
        }
        return this.sleep$slot;
    }

    @Override
    public boolean sleep$deliver(Packet<?> packet, PacketSendListener listener, boolean flush) {
        try {
            return ((NetReactor.Batched) this.connection).sleep$sendAsServer(packet, listener, flush);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.forThrowable(throwable, "Sending packet");
            CrashReportCategory crashreportcategory = crashreport.addCategory("Packet being sent");
            crashreportcategory.setDetail("Packet class", () -> packet.getClass().getCanonicalName());
            throw new ReportedException(crashreport);
        }
    }

    @Override
    public NetReactor.Batched sleep$batched() {
        return (NetReactor.Batched) this.connection;
    }
}
