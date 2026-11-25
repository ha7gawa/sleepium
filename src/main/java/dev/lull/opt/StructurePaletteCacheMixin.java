package dev.lull.opt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GenGraph / parallel worldgen: a structure template's palette caches its blocks by type in a plain HashMap filled on
 * demand (blocks(Block) -> computeIfAbsent). Vanilla fills it from one worldgen thread; with STRUCTURE_STARTS of
 * different chunks running at once, two jigsaw structures sharing a template filled it together - a
 * ConcurrentModificationException out of HashMap.computeIfAbsent ("Exception generating new chunk"). A concurrent map:
 * the mapping (a filter of the palette's own list) never touches the map, so the cached lists are the same.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructurePaletteCacheMixin {
    @Shadow
    @Final
    @Mutable
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$concurrent(CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>();
    }
}
