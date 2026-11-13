package dev.lull.veilmixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

/** sleepium-veil.mixins.json, applied only when Veil is loaded (Sable / Create Aeronautics bring it). */
public final class VeilMixinPlugin implements IMixinConfigPlugin {
    static {
        dev.lull.optsupport.GameDefaults.ensure();   // a built jar's flags before they are read
    }

    static boolean veil() {
        var mods = net.neoforged.fml.loading.LoadingModList.get();
        return mods != null && mods.getModFileById("veil") != null;
    }

    @Override public void onLoad(String mixinPackage) {
        dev.lull.optsupport.MixinYield.prepass(mixinPackage, this::sleep$decide);   // rules asking ours to win: before any config is prepared
    }
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return sleep$decide(targetClassName, mixinClassName) && !dev.lull.optsupport.MixinYield.yields(targetClassName, mixinClassName) && !dev.lull.optsupport.MixinYield.needsYielded(mixinClassName);
    }

    private boolean sleep$decide(String targetClassName, String mixinClassName) {
        boolean on = veil();
        if (on) System.out.println("[sleep/opt] applying " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " -> " + targetClassName);
        return on;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
