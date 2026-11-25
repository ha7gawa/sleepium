package dev.lull;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Fabric (lab): the statics the engine reads; the entry point is SleepFabric. */
public final class SleepMod {
    private SleepMod() {}

    public static final String MOD_ID = "sleepium";
    public static final boolean DEV = !Loader.production();
    public static final Logger LOGGER = LogUtils.getLogger();
}
