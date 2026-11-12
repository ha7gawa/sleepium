package dev.lull.ui;

import dev.lull.optsupport.SleepConfig;
import dev.lull.shaders.ShaderPipeline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

/**
 * Sleepium's settings: a sidebar of pages - the overview (live numbers: frames, the server's tick, what sleeps, the
 * shaderpack; presets; the feature groups; a report to copy), one page per feature group, the shaderpacks, every flag
 * with search - and under the page a panel describing what the pointer is on. Changes go to
 * config/sleepium.properties on Done; flags read at start say "restart". Launch flags are shown locked; flags a
 * plugin switches by another flag are shown as part of it.
 * <p>
 * 1.20.1: the lists are 1.20.1's (top and bottom edges instead of a position and height; they draw no
 * background of their own here: the screen's, which 1.20.1's Screen.render leaves to the screen).
 */
public final class SleepScreen extends Screen {
    private static final int OVERVIEW = 0, SHADERS = 100, FLAGS = 101;
    private static final int SIDEBAR_W = 116, TOP = 26, BOTTOM = 30, DESC_H = 42;
    private static int page = OVERVIEW;   // remembered while the game runs
    private final Screen parent;
    private final Map<String, Boolean> desired = new HashMap<>();
    private String filter = "";
    private OptList side, list;
    private Component hoverTip, flash;
    private long flashUntil;
    private String pack;
    private final String packAtOpen;

    public SleepScreen(Screen parent) {
        super(Component.translatable("sleepium.ui.title"));
        this.parent = parent;
        for (SleepOptions.Option o : SleepOptions.all()) desired.put(o.key(), Boolean.parseBoolean(System.getProperty(o.key())));
        pack = ShaderPipeline.currentName();
        if (pack == null) {
            String p = System.getProperty("sleep.shaders");
            pack = p == null || p.isBlank() ? null : p;
        }
        packAtOpen = pack;
    }

    /** opened on a page (the dev shots: 0 overview, 1 the first group, 2 shaders, 3 flags) */
    SleepScreen(Screen parent, int shot) {
        this(parent);
        page = switch (shot) {
            case 1 -> 1;
            case 2 -> SHADERS;
            case 3 -> FLAGS;
            default -> OVERVIEW;
        };
    }

    /** the dev probe (-Dsleep.ui.saveTest): a flag switched as its button would, then Done */
    void probeToggleAndClose(String key) {
        desired.put(key, !desired.get(key));
        onClose();
    }

    /** the dev probe (-Dsleep.ui.presetTest): a preset chosen as its button would, then Done */
    void probePresetAndClose(String p) {
        preset(p);
        onClose();
    }

    /** the dev probe (-Dsleep.ui.packUseTest): the pack's Use button on the shaders page pressed, then Done */
    boolean probeUsePackAndClose(String name) {
        Button b = useButtons.get(name);
        if (b == null) return false;
        b.onPress();
        onClose();
        return true;
    }

    /** the shaders page's Use buttons by pack (null: none), for the probe */
    private final Map<String, Button> useButtons = new HashMap<>();

    /** the Flags page, searching for name */
    private void goTo(String name) {
        page = FLAGS;
        filter = name;
        rebuildWidgets();
    }

    /** the dev probe (-Dsleep.ui.partofTest): Flags showing the first part whose parent is on the screen; that parent's name */
    String probeShowPart() {
        for (SleepOptions.Option o : SleepOptions.all()) {
            if (o.partOf().isEmpty() || SleepConfig.launchFlag(o.key())) continue;
            String parent = o.partOf().get(0);
            if (SleepOptions.all().stream().noneMatch(p -> p.key().equals(parent))) continue;
            goTo(o.shortName());
            return parent.substring(parent.lastIndexOf('.') + 1).replaceAll("Mixin$", "");
        }
        return null;
    }

    /** the dev probe: what clicking that part's switch does */
    void probeClickPart(String parentName) {
        goTo(parentName);
    }

    Screen parent() {
        return parent;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        side = new OptList(minecraft, SIDEBAR_W, height - TOP - BOTTOM, TOP, 22, SIDEBAR_W - 12);
        side.setLeftPos(0);
        addRenderableWidget(side);
        sidebar();
        int contentTop = TOP;
        if (page == FLAGS) {
            EditBox search = new EditBox(font, SIDEBAR_W + 10, TOP, width - SIDEBAR_W - 20, 18, Component.translatable("sleepium.ui.search"));
            search.setHint(Component.translatable("sleepium.ui.search").withStyle(ChatFormatting.DARK_GRAY));
            search.setValue(filter);
            search.setResponder(s -> {
                filter = s;
                fill();
            });
            addRenderableWidget(search);
            contentTop = TOP + 24;
        }
        list = new OptList(minecraft, width - SIDEBAR_W, height - contentTop - BOTTOM - DESC_H, contentTop, 24, Math.min(400, width - SIDEBAR_W - 24));
        list.setLeftPos(SIDEBAR_W);
        addRenderableWidget(list);
        fill();
        int bx = SIDEBAR_W + (width - SIDEBAR_W) / 2;
        addRenderableWidget(Button.builder(Component.translatable("sleepium.ui.reset"), x -> {
            preset("recommended");
            fill();
        }).bounds(bx - 154, height - 26, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, x -> onClose()).bounds(bx + 4, height - 26, 150, 20).build());
    }

    private void sidebar() {
        side.clear();
        sideEntry(OVERVIEW, Component.translatable("sleepium.ui.page.overview"));
        int i = 1;
        var groups = SleepOptions.byGroup();
        for (String g : SleepOptions.GROUPS) {
            int p = i++;
            if (!groups.get(g).isEmpty()) sideEntry(p, Component.translatable("sleepium.ui.side." + g));   // 1.20.1: animation waits for GeckoLib's port
        }
        sideEntry(SHADERS, Component.translatable("sleepium.ui.side.shaders"));
        sideEntry(FLAGS, Component.translatable("sleepium.ui.side.flags"));
    }

    private void sideEntry(int p, Component name) {
        Button b = Button.builder(name, x -> {
            page = p;
            rebuildWidgets();
        }).size(SIDEBAR_W - 16, 20).build();
        b.active = page != p;
        side.add(new Row(null, null, null, b));
    }

    private void fill() {
        list.clear();
        if (page == OVERVIEW) fillOverview();
        else if (page == SHADERS) fillShaders();
        else if (page == FLAGS) fillFlags(null);
        else fillFlags(SleepOptions.GROUPS[page - 1]);
    }

    // ------------------------------------------------------------------ the overview

    private void fillOverview() {
        for (int i = 0; i < 5; i++) {
            final int k = i;
            list.add(new Row(() -> Component.literal(SleepStats.lines()[k]).withStyle(k == 0 ? ChatFormatting.WHITE : ChatFormatting.GRAY), null,
                    Component.translatable("sleepium.ui.stats.tip"), new AbstractWidget[0]));
        }
        // the presets
        List<AbstractWidget> ps = new ArrayList<>();
        for (String p : new String[] {"maximum", "recommended", "compatible", "off"}) {
            Button b = Button.builder(Component.translatable("sleepium.ui.preset." + p), x -> preset(p)).size(68, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("sleepium.ui.preset." + p + ".tip"))).build();
            ps.add(b);
        }
        list.add(new Row(null, () -> {
            String now = currentPreset();
            for (int i = 0; i < ps.size(); i++) ps.get(i).active = !(new String[] {"maximum", "recommended", "compatible", "off"}[i]).equals(now);
            return null;
        }, Component.translatable("sleepium.ui.presets.tip"), ps.toArray(new AbstractWidget[0])));
        for (var e : SleepOptions.byGroup().entrySet()) {
            List<SleepOptions.Option> opts = own(e.getValue()).stream().filter(SleepOptions.Option::byDefault).toList();
            if (opts.isEmpty()) continue;
            Button toggle = Button.builder(Component.empty(), x -> {
                boolean allOn = opts.stream().filter(o -> !SleepConfig.launchFlag(o.key())).allMatch(o -> desired.get(o.key()));
                for (SleepOptions.Option o : opts) if (!SleepConfig.launchFlag(o.key())) desired.put(o.key(), !allOn);
            }).size(70, 20).build();
            String g = e.getKey();
            list.add(new Row(() -> Component.translatable("sleepium.ui.group." + g), () -> {
                long on = opts.stream().filter(o -> desired.get(o.key())).count();
                toggle.setMessage(on == opts.size() ? on(true) : on == 0 ? on(false) : Component.translatable("sleepium.ui.partly").withStyle(ChatFormatting.YELLOW));
                return Component.translatable("sleepium.ui.count", on, opts.size()).withStyle(ChatFormatting.GRAY);
            }, Component.translatable("sleepium.ui.group." + g + ".desc"), toggle));
        }
        Button hud = Button.builder(Component.empty(), x -> SleepStats.hud = !SleepStats.hud).size(70, 20).build();
        list.add(new Row(() -> Component.translatable("sleepium.ui.hud"), () -> {
            hud.setMessage(on(SleepStats.hud));
            return null;
        }, Component.translatable("sleepium.ui.hud.tip"), hud));
        Button recal = Button.builder(Component.translatable("sleepium.ui.calibration.again"), x -> {
            dev.lull.optsupport.Calibration.reset();
            flash = Component.translatable("sleepium.ui.calibration.done").withStyle(ChatFormatting.GREEN);
            flashUntil = System.currentTimeMillis() + 3500;
        }).size(110, 20).build();
        list.add(new Row(() -> Component.translatable("sleepium.ui.calibration"),
                () -> Component.literal(dev.lull.optsupport.Calibration.summary()).withStyle(ChatFormatting.GRAY),
                Component.translatable("sleepium.ui.calibration.tip"), recal));
        Button copy = Button.builder(Component.translatable("sleepium.ui.report"), x -> {
            minecraft.keyboardHandler.setClipboard(report());
            flash = Component.translatable("sleepium.ui.report.done").withStyle(ChatFormatting.GREEN);
            flashUntil = System.currentTimeMillis() + 2500;
        }).size(110, 20).build();
        list.add(new Row(() -> Component.translatable("sleepium.ui.report.label"), null, Component.translatable("sleepium.ui.report.tip"), copy));
    }

    private static List<SleepOptions.Option> own(List<SleepOptions.Option> opts) {
        return opts.stream().filter(o -> o.partOf().isEmpty()).toList();
    }

    private static final Set<String> CLIENT_RENDER = Set.of("chunks", "entities_render", "lighting");

    /**
     * maximum: the tested set and the experimental flags; recommended: the tested set; compatible: the tested set but
     * the client's rendering groups (for other rendering mods); off: vanilla. Launch flags are left as they are.
     */
    private void preset(String p) {
        for (SleepOptions.Option o : SleepOptions.all()) {
            if (!o.partOf().isEmpty() || SleepConfig.launchFlag(o.key())) continue;
            boolean v = switch (p) {
                case "maximum" -> true;
                case "compatible" -> o.byDefault() && !CLIENT_RENDER.contains(o.group());
                case "off" -> false;
                default -> o.byDefault();
            };
            desired.put(o.key(), v);
        }
    }

    private String currentPreset() {
        outer:
        for (String p : new String[] {"maximum", "recommended", "compatible", "off"}) {
            for (SleepOptions.Option o : SleepOptions.all()) {
                if (!o.partOf().isEmpty() || SleepConfig.launchFlag(o.key())) continue;
                boolean v = switch (p) {
                    case "maximum" -> true;
                    case "compatible" -> o.byDefault() && !CLIENT_RENDER.contains(o.group());
                    case "off" -> false;
                    default -> o.byDefault();
                };
                if (desired.get(o.key()) != v) continue outer;
            }
            return p;
        }
        return null;
    }

    /** for a bug report: versions, the machine, the pack, the flags changed from the defaults (clipboard) */
    private String report() {
        StringBuilder sb = new StringBuilder("Sleepium report\n");
        var sleepium = net.minecraftforge.fml.ModList.get().getModContainerById("sleepium");
        sb.append("Sleepium ").append(sleepium.map(c -> c.getModInfo().getVersion().toString()).orElse("?")).append(", Minecraft ")
                .append(SharedConstants.getCurrentVersion().getName()).append(", Forge ")
                .append(net.minecraftforge.fml.ModList.get().getModContainerById("forge").map(c -> c.getModInfo().getVersion().toString()).orElse("?")).append('\n');
        sb.append("Java ").append(System.getProperty("java.version")).append(", ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(", ").append(Runtime.getRuntime().availableProcessors()).append(" cores, heap ")
                .append(Runtime.getRuntime().maxMemory() >> 20).append(" MB\n");
        sb.append("GPU ").append(SleepStats.gpu()).append(", GL ").append(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION)).append('\n');
        sb.append("Shaderpack: ").append(SleepStats.shaderState()).append('\n');
        sb.append("Mods: ").append(net.minecraftforge.fml.ModList.get().size()).append('\n');
        for (String l : SleepStats.lines()) sb.append(l).append('\n');
        sb.append("Changed flags:");
        int n = 0;
        for (SleepOptions.Option o : SleepOptions.all()) {
            boolean now = Boolean.parseBoolean(System.getProperty(o.key()));
            if (o.partOf().isEmpty() && now != o.byDefault()) {
                sb.append(' ').append(o.key()).append('=').append(now).append(SleepConfig.launchFlag(o.key()) ? " (launch)" : "");
                n++;
            }
        }
        if (n == 0) sb.append(" none");
        return sb.append('\n').toString();
    }

    // ------------------------------------------------------------------ flags (a group's, or all with search)

    private void fillFlags(String group) {
        String f = filter.toLowerCase(Locale.ROOT);
        List<SleepOptions.Option> opts = new ArrayList<>(SleepOptions.all());
        if (group != null) {
            opts.removeIf(o -> !group.equals(o.group()));
            opts.sort((a, b) -> Boolean.compare(!a.partOf().isEmpty(), !b.partOf().isEmpty()));   // its own flags first
            list.add(new Row(() -> Component.translatable("sleepium.ui.group." + group).withStyle(ChatFormatting.BOLD), null,
                    Component.translatable("sleepium.ui.group." + group + ".desc"), new AbstractWidget[0]));
        }
        for (SleepOptions.Option o : opts) {
            if (group == null && !f.isEmpty() && !o.key().toLowerCase(Locale.ROOT).contains(f) && !o.description().toLowerCase(Locale.ROOT).contains(f))
                continue;
            boolean locked = SleepConfig.launchFlag(o.key());
            boolean part = !o.partOf().isEmpty();
            String parent = part ? o.partOf().get(0) : null;
            String parentName = part ? parent.substring(parent.lastIndexOf('.') + 1).replaceAll("Mixin$", "") : null;
            // a part is switched by its parent: clicking it goes there (Flags, searching for the parent); a parent not on
            // the screen (an experimental flag set only at launch) leaves the part locked
            boolean parentShown = part && SleepOptions.all().stream().anyMatch(p -> p.key().equals(parent));
            Button toggle = Button.builder(Component.empty(), x -> {
                if (part) goTo(parentName);
                else desired.put(o.key(), !desired.get(o.key()));
            }).size(70, 20).build();
            toggle.active = !locked && (!part || parentShown);
            MutableComponent tip = Component.literal(o.shortName()).withStyle(ChatFormatting.WHITE).append(" ")
                    .append(Component.literal(o.key()).withStyle(ChatFormatting.DARK_GRAY)).append("\n")
                    .append(o.description().isEmpty() ? Component.translatable("sleepium.ui.nodesc") : Component.literal(o.description()))
                    .append(" ").append(Component.translatable(o.byDefault() ? "sleepium.ui.default.on" : "sleepium.ui.default.off").withStyle(ChatFormatting.DARK_GRAY));
            if (locked) tip.append(" ").append(Component.translatable("sleepium.ui.locked", o.key()).withStyle(ChatFormatting.GOLD));
            String parts = String.join(", ", o.partOf().stream().map(k -> k.substring(k.lastIndexOf('.') + 1).replaceAll("Mixin$", "")).toList());
            if (part) tip.append(" ").append((parentShown ? Component.translatable("sleepium.ui.partof.tip", parts)
                    : Component.translatable("sleepium.ui.partof.hidden", parts, parent)).withStyle(ChatFormatting.AQUA));
            MutableComponent label = Component.literal(o.shortName());
            if (part) label.append(Component.translatable("sleepium.ui.partof", parts).withStyle(ChatFormatting.DARK_GRAY));
            if (!o.byDefault()) label.append(Component.translatable("sleepium.ui.experimental").withStyle(ChatFormatting.DARK_AQUA));
            list.add(new Row(() -> label, () -> {
                toggle.setMessage(on(part ? effective(o.partOf().get(0)) : desired.get(o.key())));
                return !part && restartNeeded(o.key()) ? Component.translatable("sleepium.ui.restart.short").withStyle(ChatFormatting.YELLOW) : null;
            }, tip, toggle));
        }
    }

    // ------------------------------------------------------------------ shaderpacks

    private void fillShaders() {
        Path dir = minecraft.gameDirectory.toPath().resolve("shaderpacks");
        List<String> packs = new ArrayList<>();
        try (Stream<Path> s = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            s.filter(p -> Files.isDirectory(p) || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .map(p -> p.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).forEach(packs::add);
        } catch (IOException e) {
            System.out.println("[sleep/ui] shaderpacks: " + e);
        }
        list.add(packRow(null, Component.translatable("sleepium.ui.shaders.none")));
        for (String p : packs) list.add(packRow(p, Component.literal(p)));
        Button folder = Button.builder(Component.translatable("sleepium.ui.shaders.folder"), x -> {
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
            }
            Util.getPlatform().openFile(dir.toFile());   // (1.21: openPath)
        }).size(150, 20).build();
        list.add(new Row(() -> packs.isEmpty() ? Component.translatable("sleepium.ui.shaders.empty").withStyle(ChatFormatting.GRAY) : Component.empty(), null,
                Component.translatable("sleepium.ui.shaders.note"), folder));
    }

    private Row packRow(String name, Component label) {
        Button use = Button.builder(Component.empty(), x -> {
            pack = name;
            ShaderPipeline.request(name);   // live: prepared over frames, then on
        }).size(80, 20).build();
        useButtons.put(name, use);
        if (name != null) {
            Button opts = Button.builder(Component.translatable("sleepium.ui.packopts.open"), x -> {
                try {
                    minecraft.setScreen(new PackOptionsScreen(this, dev.lull.shaders.PackScreenModel.load(minecraft.gameDirectory.toPath(), name,
                            minecraft.getLanguageManager().getSelected())));
                } catch (Exception ex) {
                    System.out.println("[sleep/ui] pack options of " + name + ": " + ex);
                }
            }).size(70, 20).build();
            return new Row(() -> label, () -> {
                boolean selected = name.equals(pack);
                use.active = !selected;
                use.setMessage(selected ? Component.translatable("sleepium.ui.shaders.selected").withStyle(ChatFormatting.GREEN)
                        : Component.translatable("sleepium.ui.shaders.use"));
                return null;
            }, Component.translatable("sleepium.ui.shaders.note"), opts, use);
        }
        return new Row(() -> label, () -> {
            boolean selected = name == null ? pack == null : name.equals(pack);
            use.active = !selected;
            use.setMessage(selected ? Component.translatable("sleepium.ui.shaders.selected").withStyle(ChatFormatting.GREEN)
                    : Component.translatable("sleepium.ui.shaders.use"));
            return null;
        }, Component.translatable("sleepium.ui.shaders.note"), use);
    }

    private static Component on(boolean on) {
        return on ? CommonComponents.OPTION_ON.copy().withStyle(ChatFormatting.GREEN) : CommonComponents.OPTION_OFF.copy().withStyle(ChatFormatting.RED);
    }

    /** a flag's value as this screen would leave it (its own button's, else the property's) */
    private boolean effective(String key) {
        Boolean d = desired.get(key);
        return d != null ? d : Boolean.parseBoolean(System.getProperty(key));
    }

    private boolean restartNeeded(String key) {
        return desired.get(key) != Boolean.parseBoolean(SleepConfig.startup(key));
    }

    // ------------------------------------------------------------------ drawing, closing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hoverTip = null;
        renderBackground(g);   // 1.20.1: the screen's own (1.21's super.render draws it)
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, 8, 9, 0xFFFFFF);
        g.fill(SIDEBAR_W, TOP, SIDEBAR_W + 1, height - BOTTOM, 0x60FFFFFF);
        // the description panel: what the pointer is on
        int x0 = SIDEBAR_W + 10, y0 = height - BOTTOM - DESC_H + 4, w = width - SIDEBAR_W - 20;
        g.fill(x0 - 4, y0 - 3, x0 + w + 4, height - BOTTOM - 2, 0x50000000);
        Component desc = hoverTip;
        long n = SleepOptions.all().stream().filter(o -> o.partOf().isEmpty() && restartNeeded(o.key())).count();
        if (desc == null && flash != null && System.currentTimeMillis() < flashUntil) desc = flash;
        if (desc == null && n > 0) desc = Component.translatable("sleepium.ui.restart", n).withStyle(ChatFormatting.YELLOW);
        if (desc == null) desc = Component.translatable("sleepium.ui.hover").withStyle(ChatFormatting.DARK_GRAY);
        int y = y0;
        for (FormattedCharSequence line : font.split(desc, w)) {
            if (y > height - BOTTOM - 10) break;
            g.drawString(font, line, x0, y, 0xE0E0E0);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        save();
        minecraft.setScreen(parent);
    }

    /** the file keeps what differs from the defaults (or from what this run started with: dev runs set no defaults) */
    private void save() {
        Map<String, String> out = new TreeMap<>(SleepConfig.saved());
        for (SleepOptions.Option o : SleepOptions.all()) {
            if (!o.partOf().isEmpty()) continue;   // switched by its own flag's line
            boolean v = desired.get(o.key());
            if (v != o.byDefault() || v != Boolean.parseBoolean(SleepConfig.startup(o.key()))) out.put(o.key(), Boolean.toString(v));
            else out.remove(o.key());
        }
        if (pack != null) out.put("sleep.shaders", pack);
        else out.remove("sleep.shaders");
        if (SleepStats.hud) out.put("sleep.ui.hud", "true");
        else out.remove("sleep.ui.hud");
        SleepConfig.save(out);
    }

    // ------------------------------------------------------------------ the lists

    private static final class OptList extends ContainerObjectSelectionList<Row> {
        private final int rowWidth;

        OptList(Minecraft mc, int width, int height, int y, int itemHeight, int rowWidth) {
            super(mc, width, height, y, y + height, itemHeight);   // 1.20.1: top and bottom edges
            this.rowWidth = rowWidth;
            setRenderBackground(false);   // 1.20.1 lists paint dirt over the world: the screen's background shows instead
            setRenderTopAndBottom(false);
        }

        void add(Row r) {
            addEntry(r);
        }

        void clear() {
            clearEntries();
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return rowWidth;
        }

        @Override
        protected int getScrollbarPosition() {
            return x0 + width - 6;
        }
    }

    /** a label (computed each frame, as the status line beside it, which also updates the buttons), a description, buttons at the right */
    private final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final Supplier<Component> label, status;
        private final Component tip;
        private final List<AbstractWidget> widgets;

        Row(Supplier<Component> label, Supplier<Component> status, Component tip, AbstractWidget... widgets) {
            this.label = label;
            this.status = status;
            this.tip = tip;
            this.widgets = List.of(widgets);
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            Component st = status == null ? null : status.get();
            int x = left + width;
            if (label == null && widgets.size() == 1) x = left + (width + widgets.get(0).getWidth()) / 2;   // a sidebar button: centred
            for (int i = widgets.size() - 1; i >= 0; i--) {
                AbstractWidget w = widgets.get(i);
                x -= w.getWidth();
                w.setX(x);
                w.setY(top);
                w.render(g, mouseX, mouseY, partialTick);
                x -= 4;
            }
            int textRight = x - 4;
            if (label != null) {
                Component l = label.get();
                int room = textRight - left - 2 - (st == null ? 0 : font.width(st) + 6);
                g.drawString(font, font.width(l) > room ? Component.literal(font.substrByWidth(l, Math.max(0, room - 6)).getString() + "…") : l, left + 2, top + 6, 0xFFFFFF);
            }
            if (st != null) g.drawString(font, st, textRight - font.width(st), top + 6, 0xAAAAAA);
            if (tip != null && hovering) hoverTip = tip;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}
