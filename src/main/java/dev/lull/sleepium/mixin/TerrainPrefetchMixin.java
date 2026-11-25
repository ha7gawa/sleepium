package dev.lull.sleepium.mixin;

import dev.lull.sleepium.server.TerrainPrefetch;
import java.util.Map;
import java.util.Random;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.ChunkGeneratorSettings;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.structure.MapGenMineshaft;
import net.minecraft.world.gen.structure.MapGenScatteredFeature;
import net.minecraft.world.gen.structure.MapGenStronghold;
import net.minecraft.world.gen.structure.MapGenVillage;
import net.minecraft.world.gen.structure.StructureOceanMonument;
import net.minecraft.world.gen.structure.WoodlandMansion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** TerrainPrefetch: generateChunk takes a worker's terrain and does the rest as vanilla. */
@Mixin(ChunkGeneratorOverworld.class)
public abstract class TerrainPrefetchMixin {
    @Shadow @Final private World world;
    @Shadow @Final private boolean mapFeaturesEnabled;
    @Shadow private ChunkGeneratorSettings settings;
    @Shadow private Biome[] biomesForGeneration;
    @Shadow @Final private Random rand;
    @Shadow private net.minecraft.world.gen.MapGenBase caveGenerator;
    @Shadow private net.minecraft.world.gen.MapGenBase ravineGenerator;
    @Shadow public abstract void setBlocksInChunk(int x, int z, ChunkPrimer primer);
    @Shadow public abstract void replaceBiomeBlocks(int x, int z, ChunkPrimer primer, Biome[] biomesIn);
    @Shadow private MapGenStronghold strongholdGenerator;
    @Shadow private MapGenVillage villageGenerator;
    @Shadow private MapGenMineshaft mineshaftGenerator;
    @Shadow private MapGenScatteredFeature scatteredFeatureGenerator;
    @Shadow private StructureOceanMonument oceanMonumentGenerator;
    @Shadow private WoodlandMansion woodlandMansionGenerator;

    @Redirect(method = "setBlocksInChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBiomeProvider()Lnet/minecraft/world/biome/BiomeProvider;"))
    private BiomeProvider sleep$provider(World w) {
        return TerrainPrefetch.provider(w);
    }

    @Inject(method = "generateChunk", at = @At("HEAD"), cancellable = true)
    private void sleep$prefetched(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        ChunkGeneratorOverworld self = (ChunkGeneratorOverworld) (Object) this;
        if (!TerrainPrefetch.usable(self, world)) return;
        ChunkProviderServer cps = (ChunkProviderServer) world.getChunkProvider();
        TerrainPrefetch.requestAround(x, z, cps::isChunkGeneratedAt);
        TerrainPrefetch.Result r = TerrainPrefetch.take(x, z);
        if (r == null) {
            TerrainPrefetch.vanilla++;
            return;
        }
        TerrainPrefetch.used++;
        if (TerrainPrefetch.VERIFY && r.stages != null) {   // vanilla's own pass, compared stage by stage; vanilla's result goes on
            rand.setSeed((long) x * 341873128712L + (long) z * 132897987541L);
            ChunkPrimer v = new ChunkPrimer();
            setBlocksInChunk(x, z, v);
            boolean bad = !java.util.Arrays.equals(TerrainPrefetch.data(v), r.stages[0]);
            if (bad) TerrainPrefetch.STAGE_MISMATCH[0]++;
            biomesForGeneration = world.getBiomeProvider().getBiomes(biomesForGeneration, x * 16, z * 16, 16, 16);
            if (!java.util.Arrays.equals(biomesForGeneration, r.biomes)) TerrainPrefetch.STAGE_MISMATCH[1]++;
            replaceBiomeBlocks(x, z, v, biomesForGeneration);
            if (!java.util.Arrays.equals(TerrainPrefetch.data(v), r.stages[1])) TerrainPrefetch.STAGE_MISMATCH[2]++;
            if (settings.useCaves) caveGenerator.generate(world, x, z, v);
            if (!java.util.Arrays.equals(TerrainPrefetch.data(v), r.stages[2])) TerrainPrefetch.STAGE_MISMATCH[3]++;
            if (settings.useRavines) ravineGenerator.generate(world, x, z, v);
            if (!java.util.Arrays.equals(TerrainPrefetch.data(v), r.stages[3])) TerrainPrefetch.STAGE_MISMATCH[4]++;
            TerrainPrefetch.verified++;
            r = new TerrainPrefetch.Result(v, biomesForGeneration.clone(), java.util.Collections.emptyMap());
        }
        // what vanilla's pass over the chunk leaves behind: the biomes array, the shared biomes' surface blocks
        biomesForGeneration = r.biomes;
        for (Map.Entry<Biome, IBlockState[]> e : r.states.entrySet()) {
            e.getKey().topBlock = e.getValue()[0];
            e.getKey().fillerBlock = e.getValue()[1];
        }
        ChunkPrimer chunkprimer = r.primer;
        // vanilla's generateChunk from the structures on
        if (mapFeaturesEnabled) {
            if (settings.useMineShafts) mineshaftGenerator.generate(world, x, z, chunkprimer);
            if (settings.useVillages) villageGenerator.generate(world, x, z, chunkprimer);
            if (settings.useStrongholds) strongholdGenerator.generate(world, x, z, chunkprimer);
            if (settings.useTemples) scatteredFeatureGenerator.generate(world, x, z, chunkprimer);
            if (settings.useMonuments) oceanMonumentGenerator.generate(world, x, z, chunkprimer);
            if (settings.useMansions) woodlandMansionGenerator.generate(world, x, z, chunkprimer);
        }
        Chunk chunk = new Chunk(world, chunkprimer, x, z);
        byte[] abyte = chunk.getBiomeArray();
        for (int i = 0; i < abyte.length; ++i) abyte[i] = (byte) Biome.getIdForBiome(biomesForGeneration[i]);
        chunk.generateSkylightMap();
        cir.setReturnValue(chunk);
    }
}
