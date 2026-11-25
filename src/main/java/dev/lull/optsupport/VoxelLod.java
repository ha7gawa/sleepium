package dev.lull.optsupport;

/** Fabric (lab): the GPU terrain (voxel) path is not in the Fabric build yet; what other classes read stays, off. */
public final class VoxelLod {
    private VoxelLod() {}

    public static volatile boolean cutting;

    public static void camera(double x, double z) {}

    public static boolean far(net.minecraft.core.BlockPos origin) {
        return false;
    }
}
