package dev.lull.opt;

import dev.lull.optsupport.LazySections;
import java.util.List;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** LazySections: a chunk copy's sections copied for the regions made from it (see there). */
@Mixin(RenderChunk.class)
public abstract class LazySectionCopyMixin implements LazySections.Chunk {
    @Shadow @Final private List<PalettedContainer<BlockState>> sections;
    @Shadow @Final LevelChunk wrapped;
    /** section index -> the live container not copied yet */
    @Unique private PalettedContainer<BlockState>[] sleep$live;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/PalettedContainer;copy()Lnet/minecraft/world/level/chunk/PalettedContainer;"))
    @SuppressWarnings("unchecked")
    private PalettedContainer<BlockState> sleep$later(PalettedContainer<BlockState> live) {
        if (!LazySections.ON) return live.copy();
        if (sleep$live == null) sleep$live = new PalettedContainer[wrapped.getSections().length];
        sleep$live[sections.size()] = live;   // (the add after this call fills index size())
        LazySections.SKIPPED.incrementAndGet();
        return null;
    }

    @Override
    public synchronized void sleep$ensureAround(int sectionY) {
        if (sleep$live == null) return;
        int c = wrapped.getSectionIndexFromSectionY(sectionY);
        for (int i = Math.max(0, c - LazySections.RADIUS), e = Math.min(sleep$live.length - 1, c + LazySections.RADIUS); i <= e; i++) {
            if (sleep$live[i] != null) {
                sections.set(i, sleep$live[i].copy());
                sleep$live[i] = null;
                LazySections.COPIED.incrementAndGet();
            }
        }
    }

    @Override
    public int sleep$uncopied() {
        int n = 0;
        if (sleep$live != null) for (Object o : sleep$live) if (o != null) n++;
        return n;
    }

    @Redirect(method = "getBlockState", at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;"))
    private Object sleep$get(List<?> list, int i) {
        if (sleep$live != null && sleep$live[i] != null) sleep$late(i);
        return list.get(i);
    }

    @Unique
    private synchronized void sleep$late(int i) {
        if (sleep$live[i] == null) return;
        sections.set(i, sleep$live[i].copy());
        sleep$live[i] = null;
        LazySections.LATE.incrementAndGet();
    }
}
