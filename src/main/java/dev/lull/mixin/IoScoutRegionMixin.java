package dev.lull.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.IoScout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Measurement only (-Dsleep.ioScout=true): the disk part of a chunk write (sectors + header) and the header: the whole
 * writeHeader (with ChunkIo: force, 8 KB write, force) and its 8 KB write alone, per storage type.
 */
@Mixin(RegionFile.class)
public abstract class IoScoutRegionMixin {
    @Shadow @Final RegionStorageInfo info;

    @WrapMethod(method = "write")
    private void sleep$write(ChunkPos pos, ByteBuffer data, Operation<Void> original) {
        long t = System.nanoTime();
        try {
            original.call(pos, data);
        } finally {
            IoScout.disk(System.nanoTime() - t);
        }
    }

    @WrapMethod(method = "writeHeader")
    private void sleep$header(Operation<Void> original) {
        long t = System.nanoTime();
        IoScout.takeHeaderWrite();
        try {
            original.call();
        } finally {
            IoScout.header(info.type(), System.nanoTime() - t, IoScout.takeHeaderWrite());
        }
    }

    @WrapOperation(method = "writeHeader", at = @At(value = "INVOKE", target = "Ljava/nio/channels/FileChannel;write(Ljava/nio/ByteBuffer;J)I"))
    private int sleep$headerWrite(FileChannel file, ByteBuffer b, long at, Operation<Integer> original) {
        long t = System.nanoTime();
        try {
            return original.call(file, b, at);
        } finally {
            IoScout.headerWrite(System.nanoTime() - t);
        }
    }
}
