package testmods.ieform;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Test only (/sleep ieform x y z): every Immersive Engineering multiblock built from its template in a row from x y z
 * (a grid 6 wide, one every 14 blocks along x and z) and formed the way the engineer's hammer forms it (IMultiblock.createStructure, a fake
 * player, each horizontal side tried) - a scene of formed multiblocks for render and tick measurements. Reflection only:
 * Immersive Engineering is not a dependency of Sleepium.
 */
public final class IeForm {
    private IeForm() {}

    /** every block of namespace ns (the registry's order) placed in its default state, a square grid step blocks apart from base */
    public static String placeAll(ServerLevel level, String ns, BlockPos base, int step) {
        java.util.List<net.minecraft.world.level.block.Block> blocks = new java.util.ArrayList<>();
        for (var e : net.minecraft.core.registries.BuiltInRegistries.BLOCK.entrySet())
            if (e.getKey().location().getNamespace().equals(ns)) blocks.add(e.getValue());
        int wide = Math.max(1, (int) Math.ceil(Math.sqrt(blocks.size())));
        int placed = 0;
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos at = base.offset(i % wide * step, 0, i / wide * step);
            try {
                if (level.setBlock(at, blocks.get(i).defaultBlockState(), 3)) placed++;
            } catch (RuntimeException e) {
                // a block that cannot stand alone: left out
            }
        }
        return "placeall: " + ns + " " + placed + " of " + blocks.size() + " blocks, a grid " + wide + " wide";
    }

    /** count copies of an item used as a player uses it on the top of the block below base + i * (dx, 0, dz), looking along +z */
    public static String useItem(ServerLevel level, String id, BlockPos base, int dx, int dz, int count) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
        if (item == net.minecraft.world.item.Items.AIR) return "useitem: no item " + id;
        Player fake = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        fake.setYRot(0);
        fake.setXRot(0);
        int ok = 0;
        for (int i = 0; i < count; i++) {
            BlockPos at = base.offset(i * dx, 0, i * dz);
            fake.setPos(at.getX() + 0.5, at.getY(), at.getZ() - 3.5);
            var stack = new net.minecraft.world.item.ItemStack(item);
            fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(at.below()).add(0, 0.5, 0), Direction.UP, at.below(), false);
            var r = stack.useOn(new net.minecraft.world.item.context.UseOnContext(fake, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
            ok += r.consumesAction() ? 1 : 0;
            if (i == 0) {   // where the block and its parts went (the first copy): the block entities around, with their offset
                StringBuilder parts = new StringBuilder();
                for (BlockPos q : BlockPos.betweenClosed(at.offset(-3, -4, -3), at.offset(3, 4, 3))) {
                    var be = level.getBlockEntity(q);
                    if (be == null) continue;
                    String off = "";
                    try {
                        var f = be.getClass().getField("offset");
                        off = java.util.Arrays.toString((int[]) f.get(be));
                    } catch (ReflectiveOperationException | ClassCastException e) {
                        // no offset field
                    }
                    if (off.equals("[0, 0, 0]") || off.equals("[0, 0]") || off.isEmpty()) parts.append(" master@").append(q.toShortString()).append(off);
                    else parts.append(" ").append(q.getY());
                }
                System.out.println("[sleep] useitem parts:" + parts);
            }
        }
        return "useitem: " + id + " placed " + ok + " of " + count;
    }

    public static String formAll(ServerLevel level, BlockPos base) {
        return formAll(level, base, "", 1, 14);
    }

    /** only: multiblocks whose name contains it ("" all); copies of each, in a grid (6 wide, or square for many), step blocks apart */
    public static String formAll(ServerLevel level, BlockPos base, String only, int copies, int step) {
        try {
            Class<?> handler = Class.forName("blusunrize.immersiveengineering.api.multiblocks.MultiblockHandler");
            Class<?> imb = Class.forName("blusunrize.immersiveengineering.api.multiblocks.MultiblockHandler$IMultiblock");
            Method all = handler.getMethod("getMultiblocks");
            Method name = imb.getMethod("getUniqueName"), structure = imb.getMethod("getStructure", Level.class), trigger = imb.getMethod("getTriggerOffset");
            Method create = imb.getMethod("createStructure", Level.class, BlockPos.class, Direction.class, Player.class);
            Player fake = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
            List<?> mbs = (List<?>) all.invoke(null);
            StringBuilder sb = new StringBuilder();
            int formed = 0, i = 0;
            int total = 0;
            for (Object mb : mbs) if (only.isEmpty() || String.valueOf(name.invoke(mb)).contains(only)) total += copies;
            int wide = Math.max(6, (int) Math.ceil(Math.sqrt(total)));
            for (Object mb : mbs) for (int copy = 0; copy < copies; copy++) {
                if (!only.isEmpty() && !String.valueOf(name.invoke(mb)).contains(only)) continue;
                BlockPos origin = base.offset(i % wide * step, 0, i / wide * step);
                i++;
                List<?> blocks = (List<?>) structure.invoke(mb, level);
                for (Object info : blocks) {
                    Record r = (Record) info;   // StructureBlockInfo(pos, state, nbt)
                    BlockPos p = null;
                    BlockState st = null;
                    for (var c : r.getClass().getRecordComponents()) {
                        Object v = c.getAccessor().invoke(r);
                        if (v instanceof BlockPos bp) p = bp;
                        else if (v instanceof BlockState bs) st = bs;
                    }
                    if (p != null && st != null) level.setBlock(origin.offset(p), st, 3);
                }
                BlockPos t = origin.offset((BlockPos) trigger.invoke(mb));
                boolean ok = false;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    if ((boolean) create.invoke(mb, level, t, d, fake)) {
                        ok = true;
                        break;
                    }
                }
                formed += ok ? 1 : 0;
                sb.append(name.invoke(mb)).append(ok ? " formed; " : " NOT formed; ");
            }
            return "ieform: " + formed + " of " + i + " formed: " + (sb.length() > 600 ? sb.substring(0, 600) + "..." : sb);
        } catch (ClassNotFoundException e) {
            return "ieform: Immersive Engineering is not installed";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "ieform: failed: " + e;
        }
    }
}
