package dev.lull.optsupport;

import java.util.concurrent.atomic.LongAdder;

/**
 * The entity tracker's per-pair check, cheaper and exact ({@code -Dlab.opt.TrackFast=true}, opt/TrackFastMixin; the
 * 1.21.1 lab-pipe port, notes/91): vanilla's TrackedEntity.updatePlayer(player) runs for every tracked entity on every
 * movement packet a player sends (ChunkMap.move: O(entities) per packet, O(players x entities) a tick) and for every
 * player when an entity changes section; each call builds two Vec3 and walks the entity's passengers through a stream
 * (getEffectiveRange) before it looks at the distance. Here the distance comes first against an upper bound of
 * vanilla's range - min(effective range, the server's view distance x 16) is at most view distance x 16 - so a pair
 * beyond it gets vanilla's answer (not seen) without the range; a pair inside it gets the range without the stream when
 * the entity carries no passengers (the field vanilla's stream walks is empty and the class keeps Entity's
 * getIndirectPassengers). Same doubles, same decisions, same seenBy changes and pairing packets in the same order.
 * Gives way (MixinYield) when another mod's mixin changes updatePlayer (Steam 'n' Rails' conductor possession).
 * {@code -Dlab.opt.TrackFastVerify=true}: every pair also through vanilla's expression, mismatches counted and logged.
 */
public final class TrackFast {
    public static final boolean VERIFY = Boolean.getBoolean("lab.opt.TrackFastVerify");
    /** server thread only: plain counters */
    public static long far, near;
    public static final LongAdder CHECKED = new LongAdder(), MISMATCH = new LongAdder();
    private static long nextReport = System.nanoTime() + 60_000_000_000L;

    /** the fast path in use (on with the flag; /sleep bots ab trackfast on|off flips it for interleaved A/B) */
    public static volatile boolean active = true;

    private TrackFast() {}

    /** the A/B switch and the counters with BotLoad's measure window */
    public static void register() {
        dev.lull.bots.BotLoad.AB.put("trackfast", v -> active = v);
        dev.lull.bots.BotLoad.WINDOWS.add(new dev.lull.bots.BotLoad.Window() {
            public void start() {}

            public String end(long ticks) { return report(); }
        });
    }

    /** the class keeps Entity's getIndirectPassengers (vanilla's stream over the passengers field); dev and SRG names */
    private static final ClassValue<Boolean> VANILLA_PASSENGERS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            for (Class<?> k = c; k != null && k != net.minecraft.world.entity.Entity.class; k = k.getSuperclass())
                for (java.lang.reflect.Method m : k.getDeclaredMethods())
                    if (m.getParameterCount() == 0 && m.getReturnType() == Iterable.class
                            && (m.getName().equals("getIndirectPassengers") || m.getName().equals("m_146897_"))) return false;
            return true;
        }
    };

    /** getEffectiveRange's loop would visit nothing: no passengers in the field its stream walks, the method not overridden */
    public static boolean noPassengers(net.minecraft.world.entity.Entity e, java.util.List<?> passengersField) {
        return passengersField.isEmpty() && VANILLA_PASSENGERS.get(e.getClass());
    }

    public static String report() {
        return "trackfast: far " + far + ", near " + near + (VERIFY ? ", verified " + CHECKED.sum() + ", mismatches " + MISMATCH.sum() : "");
    }

    /** server thread, verify mode: a log line a minute */
    public static void maybeReport() {
        long now = System.nanoTime();
        if (now < nextReport) return;
        nextReport = now + 60_000_000_000L;
        System.out.println("[sleep/opt] " + report());
    }
}
