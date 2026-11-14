package dev.lull.opt;

import dev.lull.optsupport.SectionLists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * exp/section-lists (client): renderLevel's block entity loop and renderSectionLayer's loops (translucent, tripwire)
 * walk SectionLists' compact lists instead of every visible section; a compiled section arriving bumps the generation.
 */
@Mixin(LevelRenderer.class)
public abstract class SectionListsMixin {
    private static final String FIELD = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;";

    @Redirect(method = "renderLevel", at = @At(value = "FIELD", target = FIELD, opcode = Opcodes.GETFIELD))
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> sleep$blockEntities(LevelRenderer self) {
        return SectionLists.blockEntities(((LevelRendererVisibleAccessor) self).sleep$visible());
    }

    @Redirect(method = "renderSectionLayer", at = @At(value = "FIELD", target = FIELD, opcode = Opcodes.GETFIELD))
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> sleep$layer(LevelRenderer self) {
        return SectionLists.forLayer(((LevelRendererVisibleAccessor) self).sleep$visible());
    }

    @Redirect(method = "compileSections", at = @At(value = "FIELD", target = FIELD, opcode = Opcodes.GETFIELD))
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> sleep$dirty(LevelRenderer self) {
        return SectionLists.dirtyVisible(((LevelRendererVisibleAccessor) self).sleep$visible());
    }

    @Inject(method = "addRecentlyCompiledSection", at = @At("HEAD"))
    private void sleep$compiled(SectionRenderDispatcher.RenderSection section, CallbackInfo ci) {
        SectionLists.changed(section);
    }
}
