package dev.lull.optsupport;

/**
 * A control-flow signal thrown through mods' code on purpose and caught by Sleepium (a render worker's refusal: the job
 * goes to the render thread). Not an error: CompositeEventBus passes it on without the loader's exception handler, which
 * would log a stack for it. Common code - no client classes here.
 */
public abstract class QuietSignal extends RuntimeException {
    protected QuietSignal(String why) {
        super(why, null, false, false);
    }
}
