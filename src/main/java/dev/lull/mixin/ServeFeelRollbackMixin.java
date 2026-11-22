package dev.lull.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ServeFeel (notes/92): an acknowledgement that changes a predicted block (the server's state the client heard of is not
 * what it shows) - a visible rollback. With answers in the right order (updates, then the acknowledgement) and
 * actions the server accepts there are none.
 */
@Mixin(ClientLevel.class)
public abstract class ServeFeelRollbackMixin {
    @Inject(method = "syncBlockState", at = @At("HEAD"))
    private void sleep$sync(BlockPos pos, BlockState state, Vec3 playerPos, CallbackInfo ci) {
        if (((ClientLevel) (Object) this).getBlockState(pos) != state) dev.lull.ServeFeel.rollback();
    }
}
