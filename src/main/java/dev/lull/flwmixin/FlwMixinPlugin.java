package dev.lull.flwmixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** sleepium-flywheel.mixins.json, applied only when Flywheel is loaded (Create bundles it): FlwExecutorMixin. */
public final class FlwMixinPlugin implements IMixinConfigPlugin {
    static {
        dev.lull.optsupport.GameDefaults.ensure();   // a built jar's flags before they are read
    }

    /** FlwExecutorMixin: Flywheel's plans on the frame's workers, {@code -Dlab.opt.FlwExecutorMixin=true} */
    private static final boolean EXECUTOR = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.FlwExecutorMixin");

    static boolean flywheel() {
        var mods = net.minecraftforge.fml.loading.LoadingModList.get();
        return mods != null && mods.getModFileById("flywheel") != null;
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
        boolean on = mixinClassName.endsWith(".FlwExecutorMixin") && EXECUTOR && flywheel();
        if (on) System.out.println("[sleep/opt] applying " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " -> " + targetClassName);
        return on;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
