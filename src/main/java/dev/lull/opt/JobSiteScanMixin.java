package dev.lull.opt;

import dev.lull.optsupport.JobSiteIndex;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.behavior.PoiCompetitorScan;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * lab.opt.JobSiteIndex: PoiCompetitorScan's walk over the nearest living entities (lambda$create$4: the job site's
 * POI type, then every other villager's brain read) skipped when JobSiteIndex says only this villager holds the job
 * site: the type lookup answers "none", vanilla's own way of doing nothing here.
 */
@Mixin(PoiCompetitorScan.class)
public abstract class JobSiteScanMixin {
    @Redirect(method = {"lambda$create$4", "method_47010"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;getType(Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;"))
    private static Optional<Holder<PoiType>> sleep$type(PoiManager poi, BlockPos pos) {
        return JobSiteIndex.alone(pos) ? Optional.empty() : poi.getType(pos);
    }

    @Inject(method = "competesForSameJobsite", at = @At("RETURN"))
    private static void sleep$verify(GlobalPos jobSite, Holder<PoiType> poi, Villager other, CallbackInfoReturnable<Boolean> cir) {
        if (JobSiteIndex.VERIFY && cir.getReturnValueZ()) JobSiteIndex.competitor(jobSite.pos());
    }
}
