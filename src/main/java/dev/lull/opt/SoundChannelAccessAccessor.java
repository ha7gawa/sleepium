package dev.lull.opt;

import com.mojang.blaze3d.audio.Library;
import dev.lull.optsupport.SoundNoWait;
import java.util.Set;
import java.util.concurrent.Executor;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** SoundNoWaitMixin: ChannelAccess' channels, library and executor. */
@Mixin(ChannelAccess.class)
public abstract class SoundChannelAccessAccessor implements SoundNoWait.Access {
    @Shadow @Final private Set<ChannelAccess.ChannelHandle> channels;
    @Shadow @Final Library library;
    @Shadow @Final java.util.concurrent.Executor executor;

    @Override
    public Set<ChannelAccess.ChannelHandle> sleep$channels() {
        return channels;
    }

    @Override
    public Library sleep$library() {
        return library;
    }

    @Override
    public Executor sleep$executor() {
        return executor;
    }
}
