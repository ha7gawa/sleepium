package dev.lull.optsupport;

import java.util.concurrent.atomic.LongAdder;

/**
 * The entity tracker's per-pair check, cheaper and exact ({@code -Dlab.opt.TrackFast=true}, opt/TrackFastMixin): vanilla's
 * TrackedEntity.updatePlayer(player) runs for every tracked entity on every movement packet a player sends (ChunkMap.move:
 * O(entities) per packet, O(players x entities) a tick) and for every player when an entity changes section; each call
 * builds two Vec3 and walks the entity's passengers through a stream (getEffectiveRange) before it looks at the distance.
 * Here the distance comes first against an upper bound of vanilla's range - min(effective range, view distance x 16) is
 * at most view distance x 16 - so a pair beyond it gets vanilla's answer (not seen) without the range; a pair inside it
 * gets the range without the stream when the entity carries no passengers (the field vanilla's stream walks is empty and
 * the class keeps Entity's getIndirectPassengers: vanilla's loop has nothing to visit).
 * Same doubles, same decisions, same seenBy changes and pairing packets in the same order.
 * {@code -Dlab.opt.TrackFastVerify=true}: every pair also through vanilla's expression, mismatches counted (expected 0).
 */
public final class TrackFast {
    public static final boolean VERIFY = Boolean.getBoolean("lab.opt.TrackFastVerify");
    public static final LongAdder FAR = new LongAdder(), NEAR = new LongAdder(), CHECKED = new LongAdder(), MISMATCH = new LongAdder();

    /** the fast path in use (on with the flag; /sleep bots ab trackfast on|off flips it for interleaved A/B) */
    public static volatile boolean active = true;

    private TrackFast() {}

    /** the class keeps Entity's getIndirectPassengers (vanilla's stream over the passengers field) */
    private static final ClassValue<Boolean> VANILLA_PASSENGERS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            try {
                return c.getMethod("getIndirectPassengers").getDeclaringClass() == net.minecraft.world.entity.Entity.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };

    /** getEffectiveRange's loop would visit nothing: no passengers in the field its stream walks, the method not overridden */
    public static boolean noPassengers(net.minecraft.world.entity.Entity e, java.util.List<?> passengersField) {
        return passengersField.isEmpty() && VANILLA_PASSENGERS.get(e.getClass());
    }

    public static void register() {
        dev.lull.bots.BotLoad.AB.put("trackfast", v -> active = v);
        dev.lull.bots.BotLoad.WINDOWS.add(new dev.lull.bots.BotLoad.Window() {
            public void start() {}

            public String end(long ticks) { return report(); }
        });
    }

    public static String report() {
        return "trackfast: far " + FAR.sum() + ", near " + NEAR.sum() + (VERIFY ? ", verified " + CHECKED.sum() + ", mismatches " + MISMATCH.sum() : "");
    }
}
