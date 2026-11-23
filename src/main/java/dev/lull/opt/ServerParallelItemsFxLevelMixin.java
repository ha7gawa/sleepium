package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * part of SpecTicks (lab): a run tick's sounds, kept for after the run in its order - before the draw of the sound's seed
 * from the level's threadSafeRandom (vanilla's order of draws).
 */
@Mixin(Level.class)
public abstract class ServerParallelItemsFxLevelMixin {
    @WrapMethod(method = "playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V")
    private void sleep$specSound(Player p, double x, double y, double z, SoundEvent s, SoundSource src, float v, float pitch, Operation<Void> original) {
        if (!dev.lull.optsupport.SpecTicks.deferFx(() -> original.call(p, x, y, z, s, src, v, pitch))) original.call(p, x, y, z, s, src, v, pitch);
    }

    @WrapMethod(method = "playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V")
    private void sleep$specSoundEntity(Player p, Entity e, SoundEvent s, SoundSource src, float v, float pitch, Operation<Void> original) {
        if (!dev.lull.optsupport.SpecTicks.deferFx(() -> original.call(p, e, s, src, v, pitch))) original.call(p, e, s, src, v, pitch);
    }
}
