package testmods.stimulate;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test only (-Dsleep.test.saveAllData=true): at each save every loaded saved data is written once into nothing, changed
 * or not, and one that cannot be written fails the save. A mod's saved data that breaks only once it was changed
 * (Functional Storage's ender drawer placed without its item's frequency: a null key, written only after items went in)
 * then fails at the first save-all of mod_exactness' check, not at a scene's autosave - the check leaves that block out.
 * Not by marking everything changed: some data answer null when asked to write unchanged (vanilla never asks them) and
 * that failed every save (11-11).
 */
@Mixin(DimensionDataStorage.class)
public abstract class SaveAllDataMixin {
    @Shadow @Final private Map<String, SavedData> cache;

    @Inject(method = "save", at = @At("HEAD"))
    private void sleep$probe(CallbackInfo ci) {
        for (Map.Entry<String, SavedData> e : cache.entrySet()) {
            SavedData d = e.getValue();
            if (d == null) continue;
            CompoundTag t = d.save(new CompoundTag());
            if (t == null) continue;   // writes only when changed: what vanilla asks of it
            try {
                NbtIo.write(t, new DataOutputStream(OutputStream.nullOutputStream()));
            } catch (Exception x) {
                throw new IllegalStateException("saved data " + e.getKey() + " (" + d.getClass().getName() + ") cannot be written: " + x, x);
            }
        }
    }
}
