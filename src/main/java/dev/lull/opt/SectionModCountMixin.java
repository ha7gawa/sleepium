package dev.lull.opt;

import dev.lull.optsupport.BlockCollisionMemo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Goes with BlockCollisionMemoMixin: a modification counter per chunk section, bumped at every write of its block
 * states (setBlockState, both paths, and the client packet read); these are the only writers of the container.
 */
@Mixin(LevelChunkSection.class)
public abstract class SectionModCountMixin implements BlockCollisionMemo.Counted, dev.lull.optsupport.CollisionMask.Holder {
    @Unique private int sleep$mod;
    /** CollisionMask: which cells can collide, built at counter value sleep$maskAt */
    @Unique private long[] sleep$maskBits;
    @Unique private int sleep$maskAt;

    @Override
    public long[] sleep$mask() {
        long[] m = sleep$maskBits;
        if (m == null) {
            m = sleep$maskBits = new long[64];
            sleep$maskAt = sleep$mod - 1;
        }
        if (sleep$maskAt != sleep$mod) {
            dev.lull.optsupport.CollisionMask.build((net.minecraft.world.level.chunk.LevelChunkSection) (Object) this, m);
            sleep$maskAt = sleep$mod;
        }
        return m;
    }

    @Override
    public long[] sleep$maskIfCurrent() {
        long[] m = sleep$maskBits;
        return m != null && sleep$maskAt == sleep$mod ? m : null;
    }

    @Override
    public int sleep$mod() {
        return sleep$mod;
    }

    @Redirect(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/PalettedContainer;getAndSet(IIILjava/lang/Object;)Ljava/lang/Object;"))
    private Object sleep$set(PalettedContainer<Object> states, int x, int y, int z, Object state) {
        sleep$mod++;
        return states.getAndSet(x, y, z, state);
    }

    @Redirect(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/PalettedContainer;getAndSetUnchecked(IIILjava/lang/Object;)Ljava/lang/Object;"))
    private Object sleep$setUnchecked(PalettedContainer<Object> states, int x, int y, int z, Object state) {
        sleep$mod++;
        return states.getAndSetUnchecked(x, y, z, state);
    }

    @Redirect(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/PalettedContainer;read(Lnet/minecraft/network/FriendlyByteBuf;)V"))
    private void sleep$read(PalettedContainer<?> container, FriendlyByteBuf buf) {
        sleep$mod++;
        container.read(buf);
    }
}
