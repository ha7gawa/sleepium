package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.lull.optsupport.SavedDataName;
import java.io.File;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** SavedDataName (notes/88): a failed SavedData write names its class, file and null keys; behaviour unchanged. */
@Mixin(SavedData.class)
public abstract class SavedDataNameMixin {
    @WrapOperation(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/nbt/CompoundTag;copy()Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag sleep$copied(CompoundTag tag, Operation<CompoundTag> original) {
        CompoundTag c = original.call(tag);
        SavedDataName.copied(c);
        return c;
    }

    @WrapOperation(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/IOUtilities;withIOWorker(Ljava/lang/Runnable;)V"))
    private void sleep$named(Runnable task, Operation<Void> original, @Local(argsOnly = true) File file) {
        original.call(SavedDataName.wrap(task, this, file));
    }
}
