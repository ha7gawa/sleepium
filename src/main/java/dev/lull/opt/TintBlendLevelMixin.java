package dev.lull.opt;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dlab.tintBlend: the level's tint caches (vanilla's three and the mods' from NeoForge's ColorResolverManager) learn
 * their level and resolver - each was made as pos -> calculateBlockTint(pos, resolver) (TintBlend).
 */
@Mixin(ClientLevel.class)
public abstract class TintBlendLevelMixin {
    @Shadow
    @Final
    private Object2ObjectArrayMap<ColorResolver, BlockTintCache> tintCaches;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$bindTints(CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        tintCaches.forEach((r, c) -> ((dev.lull.optsupport.TintBlend.Bound) c).sleep$bind(self, r));
    }
}
