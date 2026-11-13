package dev.lull.optsupport;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.sounds.ChannelAccess;

/**
 * -Dlab.opt.SoundNoWaitMixin=true: SoundEngine.play no longer waits on the render thread for the sound thread to give it
 * a channel. Vanilla joins ChannelAccess.createHandle's future - a task on the sound thread's queue - so every new sound
 * waits for whatever that thread is doing: a 229-mod pack (DragonKnights 2026) with Sound Physics Remastered casting rays
 * for each sound and a burning zombie horde spent 76-87 s of a minute's window parked in SoundEngine.play (11-13 fps, the
 * render thread on the CPU 0.41 of the time).
 *
 * <p>Instead the handle is made at once, its channel still empty, and the first task queued for it on the sound thread
 * takes the channel (as createHandle's task did) and adds it to the access' channels; every channel task play() queues
 * after it (settings, buffer, play) runs after it on that thread's queue - the order on the sound thread is vanilla's.
 * No channel free (vanilla: the sound dropped, nothing registered): the handle is marked stopped, and the engine's tick
 * removes it at its delete time (20 ticks) - the one difference: such a sound counts as active for that second.
 */
public final class SoundNoWait {
    public static long deferred, noChannel, waited;
    /**
     * Vanilla's wait was also the backpressure: with more handles than this waiting for their channel (the sound thread
     * behind: a ray-casting mod), the next sound is dropped as vanilla drops one with no channel free (createHandle's
     * null: play() skips it) - the queue and how late sounds start stay bounded, the render thread never waits
     * (-Dlab.sound.maxPending; -Dlab.sound.overflow=wait: vanilla's wait instead - the pack's horde went back to 13 fps:
     * each wait was for the whole backlog).
     */
    private static final int MAX_PENDING = Integer.getInteger("lab.sound.maxPending", 32);
    private static final boolean OVERFLOW_WAIT = "wait".equals(System.getProperty("lab.sound.overflow"));
    private static final java.util.concurrent.atomic.AtomicInteger PENDING = new java.util.concurrent.atomic.AtomicInteger();
    public static int pendingMax;

    private SoundNoWait() {}

    /** ChannelAccess' fields (SoundChannelAccessAccessor) */
    public interface Access {
        Set<ChannelAccess.ChannelHandle> sleep$channels();

        Library sleep$library();

        Executor sleep$executor();
    }

    /** ChannelHandle's fields (SoundChannelHandleAccessor) */
    public interface Handle {
        void sleep$setChannel(Channel channel);

        void sleep$setStopped(boolean stopped);
    }

    public static CompletableFuture<ChannelAccess.ChannelHandle> createHandle(ChannelAccess access, Library.Pool pool) {
        int p = PENDING.get();
        if (p >= MAX_PENDING) {   // the sound thread far behind
            waited++;
            return OVERFLOW_WAIT ? access.createHandle(pool) : CompletableFuture.completedFuture(null);   // (null: vanilla's no-channel path)
        }
        if (p > pendingMax) pendingMax = p;
        PENDING.incrementAndGet();
        Access a = (Access) access;
        ChannelAccess.ChannelHandle h = access.new ChannelHandle(null);
        if (deferred++ % 5000 == 0)
            System.out.println("[sleep/opt] sounds started without waiting for the sound thread: " + deferred + " (no channel free: " + noChannel
                    + "; " + (OVERFLOW_WAIT ? "waited as vanilla's" : "dropped") + ", the sound thread " + MAX_PENDING + " behind: " + waited + "; most waiting at once " + pendingMax + ")");
        a.sleep$executor().execute(() -> {   // the sound thread: what createHandle's task did, into this handle
            PENDING.decrementAndGet();
            Channel c = a.sleep$library().acquireChannel(pool);
            if (c != null) {
                ((Handle) h).sleep$setChannel(c);
                a.sleep$channels().add(h);
            } else {
                noChannel++;
                ((Handle) h).sleep$setStopped(true);   // (the engine's tick removes it at its delete time)
            }
        });
        return CompletableFuture.completedFuture(h);
    }
}
