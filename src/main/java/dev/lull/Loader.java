package dev.lull;

/**
 * Fabric (lab-fabric-1.21.1): what the NeoForge branch asks FML / NeoForge for - the side, dev or production, the mod
 * list, the config folder, the running server.
 */
public final class Loader {
    private Loader() {}

    public static boolean client() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
    }

    public static boolean production() {
        return !net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    public static boolean modLoaded(String id) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(id);
    }

    /** "id@version" of every loaded mod */
    public static java.util.List<String> mods() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var m : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods())
            out.add(m.getMetadata().getId() + "@" + m.getMetadata().getVersion().getFriendlyString());
        return out;
    }

    public static String version(String id) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    public static java.nio.file.Path configDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
    }

    /** the running server (set by SleepFabric's lifecycle listeners) */
    public static volatile net.minecraft.server.MinecraftServer server;
}
