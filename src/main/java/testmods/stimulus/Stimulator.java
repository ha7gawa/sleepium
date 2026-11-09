package testmods.stimulus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Test only (-Dsleep.test.stimulate=x1,z1,x2,z2): every block entity in the area is driven the way other mods' pipes and
 * cables drive it - through NeoForge's item / energy / fluid capabilities - plus a redstone block switched next to it,
 * on a schedule of the game time (both runs of an exactness comparison do the same). tools/mod_exactness.py places a
 * mod's blocks in the area; whether Sleepium's sleep keeps them exact shows in the comparison.
 *
 * <p>The schedule repeats every 1200 ticks from tick 300 (the scenes' start): 0-200 quiet (machines may fall asleep),
 * 200-600 fed (an item every 10 ticks from the top, energy every tick, water every 20), 600-800 quiet, 800-1100 drained
 * (an item out of every slot every 5 ticks from the bottom) and a redstone block east of it switched every 40 ticks,
 * 1100-1200 quiet. Positions in sorted order: the same actions in the same order every run.
 *
 * <p>-Dsleep.test.stimulate.work=true instead: the machines kept working (client scenes of working machines, such as
 * sleep:client_ie) - fed from every side from the start and all the time, never drained, no redstone; what they drop
 * as items (a crusher's output) removed every second, or thousands of item entities would measure their physics instead
 * (-Dsleep.test.stimulate.keepItems=true keeps them: the client's entity collisions under that load).
 */
public final class Stimulator {
    private Stimulator() {}

    private static final int[] AREA = area(System.getProperty("sleep.test.stimulate", ""));
    private static final boolean WORK = Boolean.getBoolean("sleep.test.stimulate.work"), KEEP_ITEMS = Boolean.getBoolean("sleep.test.stimulate.keepItems");
    private static final Direction[] SIDES = Direction.values();
    private static final Item[] ITEMS = {Items.COAL, Items.RAW_IRON, Items.IRON_ORE, Items.COBBLESTONE, Items.OAK_LOG, Items.WHEAT_SEEDS,
            Items.BONE_MEAL, Items.SAND, Items.WHEAT, Items.REDSTONE, Items.IRON_INGOT, Items.GLASS_BOTTLE, Items.BLAZE_POWDER, Items.DIAMOND};
    private static long inserted, energy, fluid, extracted, toggles, ticks;
    private static int seen;
    private static boolean listed;

    private static int[] area(String v) {
        if (v.isEmpty()) return null;
        String[] p = v.split(",");
        return new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim())};
    }

    public static void tick(MinecraftServer server) {
        if (AREA == null) return;
        ServerLevel level = server.overworld();
        long t = level.getGameTime() - 300;
        if (t < 0 && !WORK) return;
        int p = WORK ? (int) (level.getGameTime() % 1200) : (int) (t % 1200);
        boolean feed = WORK || p >= 200 && p < 600, drain = !WORK && p >= 800 && p < 1100;
        if (!feed && !drain) return;
        List<BlockEntity> bes = blockEntities(level);
        seen = Math.max(seen, bes.size());
        if (!listed) {   // which class sits where: the tool joins the block-entity rows (by position) with the coverage (by class)
            listed = true;
            for (BlockEntity be : bes)
                System.out.println("[sleep/stimulus] be " + be.getBlockPos().getX() + "," + be.getBlockPos().getY() + "," + be.getBlockPos().getZ() + " " + be.getClass().getName());
        }
        ticks++;
        for (int i = 0; i < bes.size(); i++) {
            BlockEntity be = bes.get(i);
            BlockPos pos = be.getBlockPos();
            if (be.isRemoved()) continue;
            if (WORK) {
                for (Direction d : SIDES) feed(level, pos, d, p, i);
                if (i == 0 && p % 20 == 0 && !KEEP_ITEMS) {
                    var box = new net.minecraft.world.phys.AABB(AREA[0], level.getMinBuildHeight(), AREA[1], AREA[2] + 1, level.getMaxBuildHeight(), AREA[3] + 1);
                    for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) e.discard();
                }
            } else if (feed) {
                if (p % 10 == 0) {
                    IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
                    if (h != null) insert(h, new ItemStack(ITEMS[(p / 10 + i) % ITEMS.length]));
                }
                IEnergyStorage e = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
                if (e != null) energy += e.receiveEnergy(2000, false);
                if (p % 20 == 0) {
                    IFluidHandler f = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.UP);
                    if (f != null) fluid += f.fill(new FluidStack(Fluids.WATER, 250), IFluidHandler.FluidAction.EXECUTE);
                }
            } else {
                if (p % 5 == 0) {
                    IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
                    if (h != null)
                        for (int s = 0; s < h.getSlots(); s++) if (!h.extractItem(s, 1, false).isEmpty()) extracted++;
                }
                if (p % 40 == 0) {
                    BlockPos east = pos.east();
                    BlockState st = level.getBlockState(east);
                    if (st.isAir()) {
                        level.setBlockAndUpdate(east, Blocks.REDSTONE_BLOCK.defaultBlockState());
                        toggles++;
                    } else if (st.is(Blocks.REDSTONE_BLOCK)) {
                        level.setBlockAndUpdate(east, Blocks.AIR.defaultBlockState());
                        toggles++;
                    }
                }
            }
        }
    }

    private static void feed(ServerLevel level, BlockPos pos, Direction d, int p, int i) {
        if (p % 10 == 0) {
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, d);
            if (h != null) insert(h, new ItemStack(ITEMS[(p / 10 + i + d.ordinal()) % ITEMS.length]));
        }
        IEnergyStorage e = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, d);
        if (e != null) energy += e.receiveEnergy(2000, false);
        if (p % 20 == 0) {
            IFluidHandler f = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, d);
            if (f != null) fluid += f.fill(new FluidStack(Fluids.WATER, 250), IFluidHandler.FluidAction.EXECUTE);
        }
    }

    private static void insert(IItemHandler h, ItemStack stack) {
        for (int s = 0; s < h.getSlots() && !stack.isEmpty(); s++) {
            ItemStack rest = h.insertItem(s, stack, false);
            if (rest.getCount() < stack.getCount()) inserted += stack.getCount() - rest.getCount();
            stack = rest;
        }
    }

    /** the area's block entities, sorted by position (the chunks' maps have no stable order) */
    private static List<BlockEntity> blockEntities(ServerLevel level) {
        List<BlockEntity> out = new ArrayList<>();
        for (int cx = AREA[0] >> 4; cx <= AREA[2] >> 4; cx++)
            for (int cz = AREA[1] >> 4; cz <= AREA[3] >> 4; cz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c == null) continue;
                for (BlockEntity be : c.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (p.getX() >= AREA[0] && p.getX() <= AREA[2] && p.getZ() >= AREA[1] && p.getZ() <= AREA[3]) out.add(be);
                }
            }
        out.sort(Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        return out;
    }

    public static String report() {
        return "[sleep/stimulus] block entities " + seen + ", active ticks " + ticks + ", items in " + inserted + ", items out " + extracted
                + ", energy " + energy + ", water mB " + fluid + ", redstone toggles " + toggles;
    }
}
