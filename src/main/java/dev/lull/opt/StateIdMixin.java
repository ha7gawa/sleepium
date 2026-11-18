package dev.lull.opt;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * A block state's registry id kept on the state (Block.getId: a hash lookup in BLOCK_STATE_REGISTRY - AllocTrimFace's
 * occlusion key asks it twice for every face meshed: 13 % of AO tesselation in a flight, 11-18). Block states are
 * registered at start and never re-numbered; an id not known (-1) is asked again next time.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class StateIdMixin implements dev.lull.optsupport.AllocTrim.StateId {
    @Unique private int sleep$id = -1;

    @Override
    public int sleep$id() {
        int i = sleep$id;
        if (i < 0) sleep$id = i = Block.getId((BlockState) (Object) this);
        return i;
    }
}
