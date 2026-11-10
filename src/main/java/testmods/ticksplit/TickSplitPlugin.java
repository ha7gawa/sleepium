package testmods.ticksplit;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the tick-split test mixin only with -Dsleep.meter.tickSplit=true. */
public final class TickSplitPlugin implements IMixinConfigPlugin {
    /** TickSplitMixin: -Dsleep.meter.tickSplit; ItemSplit / MoveSplit: -Dsleep.meter.itemSplit; Census*: -Dsleep.meter.census */
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        String m = mixin.substring(mixin.lastIndexOf('.') + 1);
        if (m.startsWith("Census")) return Boolean.getBoolean("sleep.meter.census");
        if (m.equals("ItemSplitMixin") || m.equals("MoveSplitMixin")) return Boolean.getBoolean("sleep.meter.itemSplit") || Boolean.getBoolean("sleep.meter.census");
        return Boolean.getBoolean("sleep.meter.tickSplit");
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
