package dev.lull.optsupport;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * AllocTrim (lab.opt.AllocTrim): temporary arrays vanilla allocates per call in hot paths, reused per thread instead.
 * PalettedContainer.pack's int[size] (7.2 % of all allocation during teleports, 10-08: every section of every saved
 * chunk, twice) - unpacked into, remapped in place, packed into a new storage, never kept: a per-thread array of
 * exactly that size (swapPalette walks the whole array, so the size must match), fully overwritten by unpack.
 */
public final class AllocTrim {
    /** StateIdMixin: a block state's registry id, kept on the state */
    public interface StateId {
        int sleep$id();
    }

    /** a state's id - kept on it when StateIdMixin is applied, else Block.getId */
    public static int id(net.minecraft.world.level.block.state.BlockState s) {
        return s instanceof StateId k ? k.sleep$id() : net.minecraft.world.level.block.Block.getId(s);
    }

    private AllocTrim() {}

    /** Block.shouldRenderFace's occlusion cache keyed by a long (AllocTrimFaceMixin): vanilla's size, load factor, no rehash */
    public static final ThreadLocal<it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap> FACES = ThreadLocal.withInitial(AllocTrim::newFaces);

    private static it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap newFaces() {
        it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap map = new it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap(2048, 0.25F) {
            @Override
            protected void rehash(int newN) {
            }
        };
        map.defaultReturnValue((byte) 127);
        return map;
    }

    /** this thread's face cache: on a pool thread its slot, else the ThreadLocal (one map a thread either way) */
    public static it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap faces() {
        Object[] s = ThreadSlots.mine();
        if (s == null) return FACES.get();
        Object m = s[ThreadSlots.FACES];
        if (m == null) s[ThreadSlots.FACES] = m = newFaces();
        return (it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap) m;
    }

    /**
     * -Dlab.faceTable (11-18): shouldRenderFace's answers in a direct-mapped table of the thread (65 536 entries: the
     * key, the answer) instead of the linked LRU of 2048 (Long2ByteLinkedOpenHashMap.getAndMoveToFirst: 3.3 % of the
     * meshing workers in flight). Same key, same computation on a miss. For pairs whose face shapes do not depend on the
     * position (all vanilla blocks) every cache gives vanilla's answer; where a mod's shape does depend on it, vanilla's
     * own answer depends on which thread meshed what before (its LRU is per thread) - no order to be exact to.
     */
    public static final boolean FACE_TABLE = Boolean.getBoolean("lab.faceTable");

    public static final class FaceTable {
        public final long[] keys = new long[1 << 16];
        public final byte[] vals = new byte[1 << 16];

        FaceTable() {
            java.util.Arrays.fill(keys, -1L);
        }

        public static int slot(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h >>> 48);
        }
    }

    private static final ThreadLocal<FaceTable> FACE_TABLES = ThreadLocal.withInitial(FaceTable::new);

    public static FaceTable faceTable() {
        Object[] s = ThreadSlots.mine();
        if (s == null) return FACE_TABLES.get();
        Object m = s[ThreadSlots.FACE_TABLE];
        if (m == null) s[ThreadSlots.FACE_TABLE] = m = new FaceTable();
        return (FaceTable) m;
    }

    private static final ThreadLocal<int[][]> INTS = ThreadLocal.withInitial(() -> new int[4][]);

    /** an int[n] for one use on this thread (its content is garbage: the caller overwrites all of it) */
    public static int[] intScratch(int n) {
        int[][] s = INTS.get();
        for (int k = 0; k < s.length; k++) {
            int[] a = s[k];
            if (a == null) return s[k] = new int[n];
            if (a.length == n) return a;
        }
        return new int[n];
    }

    /** PalettedContainer.pack: its one NEWARRAY int becomes intScratch (only if there is exactly one) */
    public static void transformPack(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("pack") || !m.desc.endsWith(")Lnet/minecraft/world/level/chunk/PalettedContainerRO$PackedData;")) continue;
            AbstractInsnNode na = null;
            int count = 0;
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext())
                if (i.getOpcode() == Opcodes.NEWARRAY && ((IntInsnNode) i).operand == Opcodes.T_INT) {
                    na = i;
                    count++;
                }
            if (count != 1) {
                System.out.println("[sleep/opt] AllocTrim: PalettedContainer.pack has " + count + " int arrays, left alone");
                return;
            }
            m.instructions.set(na, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/lull/optsupport/AllocTrim", "intScratch", "(I)[I", false));
            System.out.println("[sleep/opt] AllocTrim: PalettedContainer.pack's int[] from a per-thread array");
        }
    }
}
