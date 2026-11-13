package dev.lull.opt;

import com.mojang.blaze3d.audio.Library;
import dev.lull.optsupport.SoundNoWait;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** SoundNoWait: play() gets its channel handle at once (the channel is taken by the handle's first task on the sound thread). */
@Mixin(SoundEngine.class)
public abstract class SoundNoWaitMixin {
    @Redirect(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/ChannelAccess;createHandle(Lcom/mojang/blaze3d/audio/Library$Pool;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChannelAccess.ChannelHandle> sleep$handle(ChannelAccess access, Library.Pool pool) {
        return SoundNoWait.createHandle(access, pool);
    }
}
