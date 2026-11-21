package dev.lull.optsupport;

/**
 * ShapeFullMemo: Block.isShapeFullBlock(shape) is a pure function of an immutable VoxelShape, kept by vanilla in a
 * Guava LoadingCache (weak keys by identity, at most 512): every read appends to the cache's recency queue (a
 * ConcurrentLinkedQueue node) and drains it under the segment lock every 64 reads - garbage and a lock shared by all
 * threads (features place vines / multiface blocks through isFaceFull). Here the answer is kept on the shape itself:
 * 0 not known yet, 1 full, 2 not full. Racing threads compute and store the same value.
 */
public interface ShapeFullMemo {
    byte sleep$fullBlock();

    void sleep$setFullBlock(byte v);
}
