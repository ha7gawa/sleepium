package testmods.foreign;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the foreign test mixins only with -Dsleep.foreignTest=true. */
public final class ForeignPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.endsWith("ForeignChunkGenOverwriteMixin")) return Boolean.getBoolean("sleep.test.overwriteChunkGen");   // (issue #16)
        if (mixin.endsWith("ForeignIoWorkerTouchMixin")) return Boolean.getBoolean("sleep.test.touchIoWorker");   // (issue #18)
        return Boolean.getBoolean("sleep.foreignTest");
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) {}
}
