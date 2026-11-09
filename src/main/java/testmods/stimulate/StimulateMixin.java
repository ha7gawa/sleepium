package testmods.stimulate;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test only (-Dsleep.test.stimulate=x1,z1,x2,z2): testmods.stimulus.Stimulator after every server tick; at the stop its
 * counts and Sleepium's sleep coverage per class (-Dsleep.test.coverage) go to the console for tools/mod_exactness.py.
 */
@Mixin(MinecraftServer.class)
public abstract class StimulateMixin {
    @Inject(method = "tickChildren", at = @At("TAIL"))
    private void sleep$stimulate(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        testmods.stimulus.Stimulator.tick((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void sleep$report(CallbackInfo ci) {
        System.out.println(testmods.stimulus.Stimulator.report());
        System.out.print(dev.lull.optsupport.sleep.SleepCoverage.report());
    }
}
