package dev.lull.bots;

import java.util.List;
import java.util.Map;

/** Fabric (lab): bot players are NeoForge-only for now; the registries lab features add to stay. */
public final class BotLoad {
    private BotLoad() {}

    public interface Window {
        void start();

        String end(long ticks);
    }

    public static final List<Window> WINDOWS = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static final Map<String, java.util.function.Consumer<Boolean>> AB = new java.util.concurrent.ConcurrentHashMap<>();
}
