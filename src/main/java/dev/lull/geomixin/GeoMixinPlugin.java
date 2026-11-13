package dev.lull.geomixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * probe-geo.mixins.json, applied only when GeckoLib is loaded: GeckoLib on ParallelRender's workers
 * ({@code -Dlab.opt.GeoParallelMixin=true} with ParallelEntitiesMixin) and bones from VRAM (GeoCubesMixin, below).
 */
public final class GeoMixinPlugin implements IMixinConfigPlugin {
    static {
        dev.lull.optsupport.GameDefaults.ensure();   // a built jar's flags before ON is read
    }

    private static final boolean ON = (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.GeoParallelMixin"))
            && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ParallelEntitiesMixin"));

    public static boolean geckoLib() {
        var mods = net.minecraftforge.fml.loading.LoadingModList.get();
        return mods != null && mods.getModFileById("geckolib") != null;
    }

    @Override public void onLoad(String mixinPackage) {
        dev.lull.optsupport.MixinYield.prepass(mixinPackage, this::sleep$decide);   // rules asking ours to win: before any config is prepared
    }
    @Override public String getRefMapperConfig() { return null; }

    /** GeoCubesMixin: GeckoLib bones from VRAM, {@code -Dlab.opt.GeoPartsMixin=true} with PartBatchMixin */
    private static final boolean PARTS = (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.GeoPartsMixin"))
            && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.PartBatchMixin"));

    /** MolangJitMixin: GeckoLib's Molang compiled to bytecode, {@code -Dlab.opt.MolangJitMixin=true} */
    private static final boolean JIT = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.MolangJitMixin");

    /** GeoMarkersMixin: always (a fix, not an optimisation: bones shared by entities no longer leak poses); GeoBlendMixin
     *  too (a blend turns each bone the short way round) */

    /** KeyframeInternMixin: equal keyframes and values of the baked animations shared, {@code -Dlab.opt.KeyframeInternMixin=true} */
    private static final boolean INTERN = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.KeyframeInternMixin");
    /** AnimStreamMixin: GeckoLib's animation files read as a stream, an animation at a time, {@code -Dlab.opt.AnimStreamMixin=true} */
    private static final boolean STREAM = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.AnimStreamMixin");

    /** GeoAnim*Mixin: the animation runtime, {@code -Dlab.opt.GeoAnimMixin=true} */
    private static final boolean ANIM = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.GeoAnimMixin");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return sleep$decide(targetClassName, mixinClassName) && !dev.lull.optsupport.MixinYield.yields(targetClassName, mixinClassName) && !dev.lull.optsupport.MixinYield.needsYielded(mixinClassName);
    }

    private boolean sleep$decide(String targetClassName, String mixinClassName) {
        boolean on = (mixinClassName.endsWith(".GeoMarkersMixin") || mixinClassName.endsWith(".GeoBlendMixin") ? true : mixinClassName.endsWith(".GeoCubesMixin") ? PARTS : mixinClassName.contains(".GeoAnim") ? ANIM
                : mixinClassName.endsWith(".MolangJitMixin") ? JIT : mixinClassName.endsWith(".KeyframeInternMixin") ? INTERN : mixinClassName.endsWith(".AnimStreamMixin") ? STREAM : ON) && geckoLib();
        if (on && mixinClassName.endsWith(".GeoModelWorkerMixin")) System.setProperty("sleep.adapter.geckolib", "true");   // ParallelRender may take GeckoLib renderers
        if (on) System.out.println("[sleep/opt] applying " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " -> " + targetClassName);
        return on;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
