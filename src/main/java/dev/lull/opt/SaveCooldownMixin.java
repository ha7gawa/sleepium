package dev.lull.opt;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * -Dlab.save.cooldown=S (seconds, notes/88; option, off by default): a loaded chunk that keeps changing is saved at most
 * every S seconds instead of vanilla's 10 (ChunkMap.saveChunkIfNeeded's eager-save cooldown; the autosave's
 * saveAllChunks(false) goes through the same check). Game state is unchanged; a crash may lose up to S seconds of such
 * a chunk. Unloads, /save-all and the stop save as vanilla (no cooldown there).
 */
@Mixin(ChunkMap.class)
public abstract class SaveCooldownMixin {
    private static final long sleep$COOLDOWN = Math.max(1, Long.getLong("lab.save.cooldown", 10)) * 1000L;

    @ModifyConstant(method = "saveChunkIfNeeded", constant = @Constant(longValue = 10000L))
    private long sleep$cooldown(long vanilla) {
        return sleep$COOLDOWN;
    }
}
