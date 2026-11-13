package dev.lull.catmixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** sleepium-catnip.mixins.json, applied only when Ponder (catnip: Create's render library) is loaded: SbbPartsMixin. */
public final class CatnipMixinPlugin implements IMixinConfigPlugin {
    static {
        dev.lull.optsupport.GameDefaults.ensure();   // a built jar's flags before they are read
    }

    /** SbbPartsMixin: SuperByteBuffer templates drawn from VRAM, {@code -Dlab.opt.SbbPartsMixin=true} with PartBatchMixin */
    private static final boolean PARTS = (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.SbbPartsMixin"))
            && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.PartBatchMixin"));

    /** KineticRotationMixin / KineticBeMixin / OverStressAccessor: Create's rotating parts without their renderer,
     *  {@code -Dlab.opt.KineticMemoMixin=true} with SbbPartsMixin; Create must be loaded */
    private static final boolean MEMO = PARTS && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.KineticMemoMixin"));

    /** KineticLevelMixin: resident kinetic parts turned on the GPU, {@code -Dlab.opt.KineticResidentMixin=true} with
     *  KineticMemoMixin (and ChunkSectionMixin, BlockEntityCullNoAllocMixin: the section epoch, the loop's hook) */
    private static final boolean RESIDENT = MEMO && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.KineticResidentMixin"));

    /** level-lit SuperByteBuffers on the GPU (contraptions, actors), {@code -Dlab.opt.LevelLitPartsMixin=true} with SbbPartsMixin */
    private static final boolean LIT = PARTS && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.LevelLitPartsMixin"));

    static boolean create() {
        var mods = net.minecraftforge.fml.loading.LoadingModList.get();
        return mods != null && mods.getModFileById("create") != null;
    }

    static boolean ponder() {
        var mods = net.minecraftforge.fml.loading.LoadingModList.get();
        return mods != null && mods.getModFileById("ponder") != null;
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
        boolean kinetic = mixinClassName.endsWith(".KineticRotationMixin") || mixinClassName.endsWith(".KineticBeMixin") || mixinClassName.endsWith(".OverStressAccessor")
                || mixinClassName.endsWith(".ChainConveyorMemoMixin");
        if (mixinClassName.endsWith(".SbbCacheWorkerMixin")) {   // a safety of the parallel render, whatever else is on
            boolean on = ponder() && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ParallelEntitiesMixin"));
            if (on) System.out.println("[sleep/opt] applying SbbCacheWorkerMixin -> " + targetClassName);
            return on;
        }
        if (mixinClassName.endsWith(".CreateTimeTestMixin")) return System.getProperty("sleep.test.createTime") != null && ponder();
        boolean on = mixinClassName.endsWith(".KineticLevelMixin") ? (RESIDENT && create() || LIT && ponder())
                : mixinClassName.endsWith(".ViewAreaInvoker") ? RESIDENT && create() : kinetic ? MEMO && create() : PARTS && ponder();
        if (on) System.out.println("[sleep/opt] applying " + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1) + " -> " + targetClassName);
        return on;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
