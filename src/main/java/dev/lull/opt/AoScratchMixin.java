package dev.lull.opt;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lull.optsupport.AoScratch;
import java.util.BitSet;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * AoScratch: tesselateWithAO as vanilla (NeoForge's), its per-block scratch - the shape array, the shape flags, the AO
 * face, the neighbour position - taken from the thread's and cleared to what new ones hold (zeros, an empty set)
 * instead of allocated for every block meshed (~9 % of the garbage in a flight over the zoo, 11-18). A nested call on
 * the same thread allocates as vanilla.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class AoScratchMixin {
    @Shadow @Final static Direction[] DIRECTIONS;

    @Shadow
    private void renderModelFaceAO(BlockAndTintGetter level, BlockState state, BlockPos pos, PoseStack poseStack, VertexConsumer consumer,
                                   List<BakedQuad> quads, float[] shape, BitSet shapeFlags, ModelBlockRenderer.AmbientOcclusionFace aoFace, int packedOverlay) {
        throw new AssertionError();
    }

    /**
     * @author Sleepium (AoScratch)
     * @reason the per-block scratch reused per thread - the same calls, the same results
     */
    @Overwrite
    public void tesselateWithAO(BlockAndTintGetter level, BakedModel model, BlockState state, BlockPos pos, PoseStack poseStack, VertexConsumer consumer,
                                boolean checkSides, RandomSource random, long seed, int packedOverlay, ModelData modelData, RenderType renderType) {
        if (dev.lull.optsupport.Enclosed.ON && checkSides && level != null) {   // lab.enclosedSkip: a block that draws nothing
            BlockPos.MutableBlockPos em = dev.lull.optsupport.Enclosed.scratch();
            if (dev.lull.optsupport.Enclosed.skip(level, model, state, pos, em)) return;
        }
        AoScratch sc = AoScratch.take();
        float[] afloat;
        BitSet bitset;
        ModelBlockRenderer.AmbientOcclusionFace face;
        BlockPos.MutableBlockPos mpos;
        if (sc != null) {
            afloat = sc.shape;
            bitset = sc.flags;
            face = (ModelBlockRenderer.AmbientOcclusionFace) sc.face;
            java.util.Arrays.fill(face.brightness, 0f);
            java.util.Arrays.fill(face.lightmap, 0);
            mpos = sc.pos.set(pos);
        } else {
            afloat = new float[DIRECTIONS.length * 2];
            bitset = new BitSet(3);
            face = new ModelBlockRenderer.AmbientOcclusionFace();
            mpos = pos.mutable();
        }
        try {
            for (Direction direction : DIRECTIONS) {
                random.setSeed(seed);
                List<BakedQuad> list = model.getQuads(state, direction, random, modelData, renderType);
                if (!list.isEmpty()) {
                    mpos.setWithOffset(pos, direction);
                    if (!checkSides || Block.shouldRenderFace(state, level, pos, direction, mpos)) {
                        this.renderModelFaceAO(level, state, pos, poseStack, consumer, list, afloat, bitset, face, packedOverlay);
                    }
                }
            }
            random.setSeed(seed);
            List<BakedQuad> list1 = model.getQuads(state, (Direction) null, random, modelData, renderType);
            if (!list1.isEmpty()) {
                this.renderModelFaceAO(level, state, pos, poseStack, consumer, list1, afloat, bitset, face, packedOverlay);
            }
        } finally {
            if (sc != null) AoScratch.give(sc);
        }
    }
}
