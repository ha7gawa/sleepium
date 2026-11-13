package dev.lull.optsupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * More than one Sleepium jar in the mods folder (GitHub issue #7: sleepium-1.1.11 and sleepium-1.2.1 on both the
 * client and the server; the loader takes one and says little). Read once at start from the jars whose file name has
 * "sleep" in it (their mods.toml only): a warning in the log, and on the client a chat line when a world opens.
 */
public final class DuplicateJars {
    private DuplicateJars() {}

    private static volatile List<String> found;
    private static boolean told;

    /** the Sleepium jars in the mods folder (by their mods.toml's modId), found once */
    public static synchronized List<String> find() {
        if (found != null) return found;
        List<String> out = new ArrayList<>();
        try {
            Path mods = net.neoforged.fml.loading.FMLPaths.MODSDIR.get();
            try (var s = Files.list(mods)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!n.endsWith(".jar") || !n.contains("sleep")) continue;
                    try (var z = new java.util.zip.ZipFile(p.toFile())) {
                        for (String toml : new String[] {"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                            var e = z.getEntry(toml);
                            if (e == null) continue;
                            String t = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                            if (t.matches("(?s).*modId\\s*=\\s*\"sleepium\".*")) {
                                out.add(p.getFileName().toString());
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                        // (not a readable jar)
                    }
                }
            }
        } catch (Throwable t) {
            // no mods folder (dev runs)
        }
        found = out;
        if (out.size() > 1)
            System.out.println("[sleep] WARNING: " + out.size() + " Sleepium jars in the mods folder: " + out + " - the loader uses one (" + used() + "); delete the others");
        return out;
    }

    /** the jar the loader took */
    public static String used() {
        try {
            return net.neoforged.fml.ModList.get().getModFileById("sleepium").getFile().getFilePath().getFileName().toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** the client, once a world is open: the chat line (null when there is nothing to say or it was said) */
    public static synchronized List<String> tellOnce() {
        if (told) return null;
        told = true;
        List<String> f = find();
        return f.size() > 1 ? f : null;
    }
}
