package dev.lull.opt;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** FrameThread: the client tick an entity was last ticked in, on the entity itself (FrameThread.partial). */
@Mixin(Entity.class)
public abstract class FrameThreadEntityMarkMixin implements dev.lull.optsupport.FrameThread.Marked {
    @Unique private volatile int sleep$ftRound;

    @Override
    public int sleep$ftRound() {
        return sleep$ftRound;
    }

    @Override
    public void sleep$setFtRound(int r) {
        sleep$ftRound = r;
    }
}
