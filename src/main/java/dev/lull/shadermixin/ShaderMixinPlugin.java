package dev.lull.shadermixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * sleepium-shaders.mixins.json: the hooks of sleep's own shaderpack pipeline. Not applied when Iris or OptiFine is loaded
 * (they run packs themselves); with no pack loaded the hooks return at their first check.
 */
public final class ShaderMixinPlugin implements IMixinConfigPlugin {
    static boolean otherShaderMod() {
        return dev.lull.shaders.OtherShaderMods.present();
    }

    @Override public void onLoad(String mixinPackage) {
        dev.lull.optsupport.MixinYield.prepass(mixinPackage, (t, m) -> !otherShaderMod());   // rules asking ours to win: before any config is prepared
    }
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !otherShaderMod() && !dev.lull.optsupport.MixinYield.yields(targetClassName, mixinClassName) && !dev.lull.optsupport.MixinYield.needsYielded(mixinClassName);
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
