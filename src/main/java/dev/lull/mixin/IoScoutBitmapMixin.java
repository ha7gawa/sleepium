package dev.lull.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.IoScout;
import java.util.BitSet;
import net.minecraft.world.level.chunk.storage.RegionBitmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Measurement only (-Dsleep.ioScout=true): sectors a writer allocates (bytes written, in 4 KB sectors) and the file's growth. */
@Mixin(RegionBitmap.class)
public abstract class IoScoutBitmapMixin {
    @Shadow @Final private BitSet used;

    @WrapMethod(method = "allocate")
    private int sleep$allocate(int sectors, Operation<Integer> original) {
        int before = used.length();
        int r = original.call(sectors);
        IoScout.allocated(sectors, used.length() - before);
        return r;
    }
}
