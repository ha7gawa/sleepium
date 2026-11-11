package dev.lull.emfmixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** sleepium-emf.mixins.json, applied only when Entity Model Features is loaded: EmfAsmSplitMixin (unless -Dlab.opt.EmfSplit=false), EmfPartGpuMixin with its accessor (with PartBatch, unless -Dlab.opt.EmfPartGpu=false), EmfCarrierMixin with EmfAdapter on (EMF / ETF on render workers). */
public final class EmfMixinPlugin implements IMixinConfigPlugin {
    static {
        dev.lull.optsupport.GameDefaults.ensure();   // a built jar's flags before they are read
    }

    private static final boolean SPLIT = !"false".equals(System.getProperty("lab.opt.EmfSplit")) && !"false".equals(System.getProperty("lab.opt.defaults"));
    /** EMF's parts drawn from VRAM: with PartBatch on (its flag), unless -Dlab.opt.EmfPartGpu=false */
    private static final boolean GPU = !"false".equals(System.getProperty("lab.opt.EmfPartGpu"))
            && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.PartBatchMixin"));

    static boolean emf() {
        var mods = net.minecraftforge.fml.loading.LoadingModList.get();
        return mods != null && mods.getModFileById("entity_model_features") != null;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        boolean on = emf() && (simple.equals("EmfAsmSplitMixin") ? SPLIT : simple.startsWith("EmfCarrier") ? dev.lull.optsupport.EmfAdapter.ON
                : (simple.equals("EmfPartGpuMixin") || simple.equals("ModelPartCubesAccessor")) && GPU);
        if (on) System.out.println("[sleep/opt] applying " + simple + " -> " + targetClassName);
        return on;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
