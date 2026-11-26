package dev.lull.sleepium.mixin;

import dev.lull.sleepium.server.IndexedTickSet;
import dev.lull.sleepium.server.TickIndex;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** TickIndex: the scheduled-tick set indexed by chunk, and getPendingBlockUpdates answered from the index. */
@Mixin(WorldServer.class)
public abstract class WorldServerTickIndexMixin {
    @Shadow private Set pendingTickListEntriesHashSet;
    @Shadow private TreeSet pendingTickListEntriesTreeSet;
    @Shadow private List pendingTickListEntriesThisTick;

    @Redirect(method = {"<init>", "initialize"}, at = @At(value = "NEW", target = "java/util/TreeSet"))
    private TreeSet sleep$indexed() {
        return TickIndex.ON ? new IndexedTickSet() : new TreeSet();
    }

    @Inject(method = "getPendingBlockUpdates", at = @At("HEAD"), cancellable = true)
    private void sleep$fromIndex(Chunk chunk, boolean remove, CallbackInfoReturnable<List<NextTickListEntry>> cir) {
        if (!(pendingTickListEntriesTreeSet instanceof IndexedTickSet)) return;
        ChunkCoordIntPair c = chunk.getChunkCoordIntPair();
        int x0 = (c.chunkXPos << 4) - 2, x1 = x0 + 16 + 2, z0 = (c.chunkZPos << 4) - 2, z1 = z0 + 16 + 2;   // vanilla's box
        List<NextTickListEntry> vanilla = TickIndex.VERIFY ? sleep$scan(x0, x1, z0, z1) : null;   // before any removal
        List<NextTickListEntry> list = ((IndexedTickSet) pendingTickListEntriesTreeSet).inBox(x0, x1, z0, z1, remove);
        if (remove && list != null)
            for (NextTickListEntry e : list) pendingTickListEntriesHashSet.remove(e);
        // the entries of this tick: vanilla's own walk
        Iterator<?> it = pendingTickListEntriesThisTick.iterator();
        while (it.hasNext()) {
            NextTickListEntry e = (NextTickListEntry) it.next();
            if (e.xCoord >= x0 && e.xCoord < x1 && e.zCoord >= z0 && e.zCoord < z1) {
                if (remove) {
                    pendingTickListEntriesHashSet.remove(e);
                    it.remove();
                }
                if (list == null) list = new ArrayList<>();
                list.add(e);
            }
        }
        if (vanilla != null) TickIndex.compare(vanilla, list);
        TickIndex.ANSWERED++;
        cir.setReturnValue(list);
    }

    /** vanilla's answer, without removing (verify mode) */
    private List<NextTickListEntry> sleep$scan(int x0, int x1, int z0, int z1) {
        List<NextTickListEntry> out = null;
        for (int i = 0; i < 2; i++)
            for (Object o : i == 0 ? pendingTickListEntriesTreeSet : pendingTickListEntriesThisTick) {
                NextTickListEntry e = (NextTickListEntry) o;
                if (e.xCoord >= x0 && e.xCoord < x1 && e.zCoord >= z0 && e.zCoord < z1) {
                    if (out == null) out = new ArrayList<>();
                    out.add(e);
                }
            }
        return out;
    }
}
