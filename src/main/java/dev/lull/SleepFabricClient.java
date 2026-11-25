package dev.lull;

/** Fabric (lab): the client part - the render cache's frame hook. */
public final class SleepFabricClient implements net.fabricmc.api.ClientModInitializer {
    @Override
    public void onInitializeClient() {
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(ctx -> dev.lull.optsupport.RenderCache.frame());
        FabricBench.register();
    }
}
