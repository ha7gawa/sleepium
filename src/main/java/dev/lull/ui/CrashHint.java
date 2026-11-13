package dev.lull.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * After a crash in Sleepium (notes/73): the newest crash report, when it is new since the last start and names Sleepium's
 * classes or mixins, is matched to the settings by name (a stack frame dev.lull...EntitySleep -> lab.opt.EntitySleep,
 * dev.lull.opt.RenderCacheMixin -> lab.opt.RenderCacheMixin); the title screen then offers to start with that part off,
 * or to find the cause by halves. Each report is offered once (config/sleepium-crash.properties keeps its name).
 */
public final class CrashHint extends Screen {
    private final Screen parent;
    private final String report;
    private final List<String> keys;

    private CrashHint(Screen parent, String report, List<String> keys) {
        super(Component.translatable("sleepium.ui.crash.title"));
        this.parent = parent;
        this.report = report;
        this.keys = keys;
    }

    private static boolean checked;

    /** the title screen, once a run: a new crash report that names Sleepium -> this screen (else null) */
    static Screen check(Screen title) {
        if (checked) return null;
        checked = true;
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("crash-reports");
            if (!Files.isDirectory(dir)) return null;
            Path newest = null;
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator)
                    if (p.toString().endsWith(".txt") && (newest == null || Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(newest)) > 0)) newest = p;
            }
            if (newest == null || System.currentTimeMillis() - Files.getLastModifiedTime(newest).toMillis() > 3L * 24 * 3600 * 1000) return null;
            Path seenFile = dev.lull.optsupport.SleepConfig.file().resolveSibling("sleepium-crash.properties");
            String name = newest.getFileName().toString();
            if (Files.exists(seenFile) && Files.readString(seenFile, StandardCharsets.UTF_8).contains("seen=" + name)) return null;
            Files.writeString(seenFile, "# the last crash report Sleepium looked at\nseen=" + name + "\n", StandardCharsets.UTF_8);
            String text = new String(Files.readAllBytes(newest), StandardCharsets.UTF_8);
            if (!text.contains("dev.lull.")) return null;
            List<String> keys = match(text);
            System.out.println("[sleep] the last crash (" + name + ") went through Sleepium; settings named in it: " + keys);
            return new CrashHint(title, name, keys);
        } catch (Exception e) {
            System.out.println("[sleep] crash hint: " + e);
            return null;
        }
    }

    /** the settings whose names the report's Sleepium classes carry, in the order they appear (a part goes to its parent) */
    static List<String> match(String text) {
        Set<String> classes = new LinkedHashSet<>();
        Matcher m = Pattern.compile("dev\\.lull\\.[a-z.]*?\\.?([A-Z]\\w*)").matcher(text);
        while (m.find()) classes.add(m.group(1).replaceAll("\\$.*", ""));
        Set<String> out = new LinkedHashSet<>();
        for (String c : classes) {
            String base = c.replaceAll("Mixin$", "");
            for (SleepOptions.Option o : SleepOptions.all()) {
                String last = o.key().substring(o.key().lastIndexOf('.') + 1), lastBase = last.replaceAll("Mixin$", "");
                if (lastBase.length() < 4) continue;
                if (last.equals(c) || lastBase.equals(base) || base.startsWith(lastBase)) {
                    out.add(o.partOf().isEmpty() ? o.key() : o.partOf().get(0));
                }
            }
            if (out.size() >= 3) break;
        }
        return new ArrayList<>(out);
    }

    @Override
    protected void init() {
        int y = height / 2 + 10, w = 200;
        if (!keys.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("sleepium.ui.crash.off"), b -> {
                var saved = dev.lull.optsupport.SleepConfig.saved();
                for (String k : keys) if (!dev.lull.optsupport.SleepConfig.locked(k)) saved.put(k, "false");
                dev.lull.optsupport.SleepConfig.save(saved);
                minecraft.setScreen(parent);
            }).bounds(width / 2 - w / 2, y, w, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.translatable("sleepium.ui.crash.find"), b -> minecraft.setScreen(new SleepScreen(parent, 6))).bounds(width / 2 - w / 2, y, w, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("sleepium.ui.crash.ignore"), b -> minecraft.setScreen(parent)).bounds(width / 2 - w / 2, y, w, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.GOLD), width / 2, height / 2 - 70, 0xFFFFFF);
        String names = String.join(", ", keys.stream().map(k -> k.substring(k.lastIndexOf('.') + 1).replaceAll("Mixin$", "")).toList());
        Component body = keys.isEmpty() ? Component.translatable("sleepium.ui.crash.unknown", report) : Component.translatable("sleepium.ui.crash.body", report, names);
        int y = height / 2 - 50;
        for (FormattedCharSequence line : font.split(body, Math.min(360, width - 40))) {
            g.drawCenteredString(font, line, width / 2, y, 0xE0E0E0);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
