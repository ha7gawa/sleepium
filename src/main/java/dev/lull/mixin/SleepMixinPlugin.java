package dev.lull.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Measurement mixins are always on, except the expensive read probes (ReadProbe*), which need -Dsleep.beReads=true. */
public final class SleepMixinPlugin implements IMixinConfigPlugin {
    static {
        dev.lull.optsupport.GameDefaults.ensure();   // a built jar's flags before any is read
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return sleep$decide(targetClassName, mixinClassName) && !dev.lull.optsupport.MixinYield.yields(targetClassName, mixinClassName) && !dev.lull.optsupport.MixinYield.needsYielded(mixinClassName);
    }

    private boolean sleep$decide(String targetClassName, String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        boolean sleep = Boolean.getBoolean("lab.opt.SleepMixin") || Boolean.getBoolean("lab.opt.EntitySleep");
        if (simple.startsWith("SleepProbeUnobserved")) return sleep && !Boolean.getBoolean("lab.sleep.noUnobserved");   // off only for measurements
        if (simple.startsWith("SleepProbe")) return sleep;
        if (simple.startsWith("FrameHash")) return Boolean.getBoolean("sleep.clientAuto.framehash");
        if (simple.startsWith("DrawStats")) return Boolean.getBoolean("sleep.drawStats");
        if (simple.startsWith("NetStats")) return Boolean.getBoolean("sleep.net.stats");
        if (simple.startsWith("GenTrace") || simple.equals("DataLayerMapAccessor")) return Boolean.getBoolean("lab.pgen.trace");
        if (simple.startsWith("IoScout")) return Boolean.getBoolean("sleep.ioScout");
        if (simple.startsWith("GlTrace")) return Boolean.getBoolean("sleep.glTrace");
        if (simple.startsWith("MobQuiet")) return Boolean.getBoolean("sleep.mobQuiet");
        if (simple.startsWith("BrainTime")) return Boolean.getBoolean("sleep.brainTime");
        if (simple.startsWith("ComponentShadow")) return Boolean.getBoolean("sleep.componentShadow");
        if (simple.startsWith("MoveShadow")) return Boolean.getBoolean("sleep.moveShadow");
        if (simple.startsWith("MoveTime")) return Boolean.getBoolean("sleep.moveTime");
        if (simple.startsWith("Knock")) return System.getProperty("sleep.knock") != null;
        if (simple.equals("ClientAutoMouseMixin")) return System.getProperty("sleep.clientAuto") != null;
        if (simple.equals("ClientEntSleepTickMixin"))   // the client's entity sleep: only when asked for (a redirect other mods could meet)
            return Boolean.getBoolean("lab.opt.EntitySleep") && Boolean.getBoolean("lab.opt.ClientEntitySleep");
        if (simple.startsWith("EntSleep")) return Boolean.getBoolean("lab.opt.EntitySleep") || System.getProperty("lab.entsleep.diag") != null;
        return !simple.startsWith("ReadProbe") || Boolean.getBoolean("sleep.beReads") || sleep;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
