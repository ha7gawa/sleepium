package dev.lull.sleepium.mixin;

import dev.lull.sleepium.server.TerrainPrefetch;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.MapGenCaves;
import net.minecraft.world.gen.MapGenRavine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** TerrainPrefetch: a carver's column biome on a worker comes from the worker's provider (the server thread: vanilla's call). */
@Mixin({MapGenCaves.class, MapGenRavine.class})
public abstract class TerrainCarverBiomeMixin {
    @Redirect(method = {"isTopBlock", "digBlock"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBiome(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/biome/Biome;"))
    private Biome sleep$biome(World world, BlockPos pos) {
        return TerrainPrefetch.biome(world, pos);
    }
}
