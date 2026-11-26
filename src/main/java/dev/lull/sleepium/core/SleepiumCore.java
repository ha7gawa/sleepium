package dev.lull.sleepium.core;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

/** Sleepium's coremod (1.7.10): the GL11 redirect (GlRedirect). Mixins load through UniMixins. */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions({"dev.lull.sleepium.core.", "dev.lull.sleepium.gl."})
public final class SleepiumCore implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[] {"dev.lull.sleepium.core.GlRedirect"};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
