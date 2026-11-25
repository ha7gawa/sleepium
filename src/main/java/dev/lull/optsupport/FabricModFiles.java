package dev.lull.optsupport;

/**
 * Fabric: the shape MixinTouch reads FML's mod list in (mod files, their mixin configs, a resource by path), built from
 * FabricLoader's mods and their fabric.mod.json "mixins". In a dev run Loom has remapped the mods to Mojang's names, so
 * their mixins' targets match the index's keys; in production they are intermediary names (not mapped yet - lab).
 */
public final class FabricModFiles {
    private FabricModFiles() {}

    public record Cfg(String config) {}

    public record ModId(String getModId) {}

    public static final class FileView {
        final net.fabricmc.loader.api.ModContainer c;
        final java.util.List<Cfg> configs = new java.util.ArrayList<>();

        FileView(net.fabricmc.loader.api.ModContainer c) {
            this.c = c;
            try {
                java.nio.file.Path p = c.findPath("fabric.mod.json").orElse(null);
                if (p == null) return;
                var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(p)).getAsJsonObject();
                if (!o.has("mixins") || !o.get("mixins").isJsonArray()) return;
                for (var e : o.getAsJsonArray("mixins")) {
                    if (e.isJsonPrimitive()) configs.add(new Cfg(e.getAsString()));
                    else if (e.isJsonObject() && e.getAsJsonObject().has("config")) configs.add(new Cfg(e.getAsJsonObject().get("config").getAsString()));
                }
            } catch (Throwable t) {
                System.out.println("[sleep/opt] fabric.mod.json of " + c.getMetadata().getId() + " not read: " + t);
            }
        }

        public java.util.List<Cfg> getMixinConfigs() {
            return configs;
        }

        public java.nio.file.Path findResource(String path) {
            return c.findPath(path).orElse(null);
        }

        public FileView getSecureJar() {
            return this;
        }

        public java.nio.file.Path getRootPath() {
            return c.getRootPaths().get(0);
        }

        public String getFileName() {
            return c.getMetadata().getId();
        }
    }

    public static final class Info {
        final FileView file;
        final String id;

        Info(net.fabricmc.loader.api.ModContainer c) {
            this.file = new FileView(c);
            this.id = c.getMetadata().getId();
        }

        public FileView getFile() {
            return file;
        }

        public String moduleName() {
            return id;
        }

        public java.util.List<ModId> getMods() {
            return java.util.List.of(new ModId(id));
        }
    }

    public static final class Mods {
        public java.util.List<Info> getModFiles() {
            java.util.List<Info> out = new java.util.ArrayList<>();
            for (var c : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) out.add(new Info(c));
            return out;
        }
    }

    public static Mods get() {
        return new Mods();
    }
}
