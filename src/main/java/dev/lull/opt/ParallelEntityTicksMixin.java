package dev.lull.opt;

import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** exp/parallel-entities (client): the client's entity walk through ParallelEntities (its tail on the frame pool). */
@Mixin(ClientLevel.class)
public abstract class ParallelEntityTicksMixin {
    @Redirect(method = "tickEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void sleep$walk(EntityTickList list, Consumer<Entity> tick) {
        dev.lull.optsupport.ParallelEntities.forEach(list, tick);
    }
}
