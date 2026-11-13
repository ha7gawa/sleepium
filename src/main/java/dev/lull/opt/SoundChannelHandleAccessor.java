package dev.lull.opt;

import com.mojang.blaze3d.audio.Channel;
import dev.lull.optsupport.SoundNoWait;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** SoundNoWaitMixin: a handle's channel and stopped flag, set on the sound thread. */
@Mixin(ChannelAccess.ChannelHandle.class)
public abstract class SoundChannelHandleAccessor implements SoundNoWait.Handle {
    @Shadow Channel channel;
    @Shadow private boolean stopped;

    @Override
    public void sleep$setChannel(Channel c) {
        this.channel = c;
    }

    @Override
    public void sleep$setStopped(boolean s) {
        this.stopped = s;
    }
}
