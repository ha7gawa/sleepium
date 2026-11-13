package dev.lull.optsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * What Sleepium measured on this machine, kept across launches (config/sleepium-calibration.properties): the choices
 * that depend on the hardware - the render cache or the helper threads for entities (CacheTune), model parts from
 * graphics memory on the helper threads (PartsTune), interpolated textures blended on the graphics card or the processor
 * (GpuAnim). A choice measured once is used from the next launch on, with no measuring: measuring meant a few seconds of
 * the slower way, which a player sees as a stutter. The file is keyed by the graphics card, its driver, the system, the
 * processor's core count and Sleepium's version: any of them changing means measuring once again. "Recalibrate" in the
 * settings (or deleting the file) forgets it.
 */
public final class Calibration {
    private Calibration() {}

    /** this jar's version from the mod list (a constant here stayed at 1.2.1 in 1.2.2) */
    private static String version() {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById("sleepium")
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }
    private static Properties props;
    private static String key;
    private static Path file;

    /** the machine's key (render thread: the GL strings) */
    private static String machine() {
        String r = org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_RENDERER);
        String v = org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_VERSION);
        String vendor = org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_VENDOR);
        return vendor + " | " + r + " | " + v + " | " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " | " + Runtime.getRuntime().availableProcessors() + " cores | Sleepium " + version() + " | mods " + mods();
    }

    /**
     * The loaded mods and their versions, hashed: mods change the drawing (a mod listening to the entity render events keeps
     * entities off the helper threads; another renders its own way) - another mod list, measured again.
     */
    private static String mods() {
        StringBuilder b = new StringBuilder();
        try {
            net.neoforged.fml.ModList.get().getMods().stream()
                    .map(m -> m.getModId() + "@" + m.getVersion())
                    .sorted()
                    .forEach(x -> b.append(x).append(';'));
        } catch (Throwable t) {
            return "?";
        }
        int n = b.toString().split(";").length;
        return n + " (" + Integer.toHexString(b.toString().hashCode()) + ")";
    }

    private static synchronized boolean load() {
        if (props != null) return true;
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return false;
        try {
            key = machine();
            file = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("sleepium-calibration.properties");
            Properties p = new Properties();
            if (Files.exists(file)) {
                try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(in);
                }
                if (!key.equals(p.getProperty("machine"))) {   // another card, driver or version: measured again
                    System.out.println("[sleep/opt] calibration: made for another machine or version (" + p.getProperty("machine") + "), measuring again");
                    p = new Properties();
                }
            }
            props = p;
            return true;
        } catch (Throwable t) {
            System.out.println("[sleep/opt] calibration: not readable (" + t + "), measured this session only");
            props = new Properties();
            return true;
        }
    }

    /** the kept choice, or null (then measure it) */
    public static synchronized String get(String name) {
        return load() ? props.getProperty(name) : null;
    }

    /** a measured choice kept, with the numbers that decided it (shown in the file) */
    public static synchronized void put(String name, String value, String why) {
        if (!load()) return;
        props.setProperty(name, value);
        props.setProperty(name + ".why", why);
        save();
    }

    private static void save() {
        try {
            props.setProperty("machine", key);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(out, " Sleepium's measurements on this machine - delete this file (or Options -> Sleepium -> Recalibrate)\n"
                        + " to measure again. Each choice: the value and why.");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.out.println("[sleep/opt] calibration: not saved (" + e + ")");
        }
    }

    /** the settings' overview: what this machine uses now, short */
    public static String summary() {
        String i = GpuAnim.interpChoice.startsWith("GPU") ? "GPU" : GpuAnim.interpChoice.startsWith("CPU") ? "CPU" : "?";
        String c = ParallelRender.CacheTune.last.startsWith("the workers") || ParallelRender.CacheTune.last.startsWith("workers") ? "threads"
                : ParallelRender.CacheTune.last.startsWith("the cache") || ParallelRender.CacheTune.last.startsWith("cache") ? "cache" : "?";
        String pt = ParallelRender.PartsTune.result.startsWith("in the jobs") ? "GPU" : ParallelRender.PartsTune.result.startsWith("the CPU") ? "CPU" : "?";
        String ch = ChunkMultiDraw.pathChoice.startsWith("indirect") ? "indirect" : ChunkMultiDraw.pathChoice.startsWith("direct") ? "direct" : "?";
        return "blend " + i + " · entities " + c + " · parts " + pt + " · chunks " + ch;
    }

    /** forget everything measured (the settings' Recalibrate): the choices are measured again */
    public static synchronized void reset() {
        props = new Properties();
        try {
            if (file != null) Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the next save overwrites it
        }
        GpuAnim.recalibrate();
        ParallelRender.recalibrate();
        ChunkMultiDraw.recalibrate();
    }
}
