package dev.lull.sleepium.render;

import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Client-side Forge events of the render caches. */
public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void modelBake(ModelBakeEvent e) {
        ItemModelCache.clear();
        BlockModelListCache.clear();   // new model objects: the old lists belong to models no longer drawn
    }
}
