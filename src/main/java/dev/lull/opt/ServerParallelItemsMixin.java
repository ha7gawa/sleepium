package dev.lull.opt;

import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** lab (notes/89, off by default): the server's entity walk through ServerParallelItems (runs of dropped items on the server pool). */
@Mixin(ServerLevel.class)
public abstract class ServerParallelItemsMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void sleep$walk(EntityTickList list, Consumer<Entity> consumer) {
        dev.lull.optsupport.ServerParallelItems.forEach(list, consumer, (ServerLevel) (Object) this);
    }
}
