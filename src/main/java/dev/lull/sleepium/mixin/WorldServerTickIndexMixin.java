package dev.lull.sleepium.mixin;

import dev.lull.sleepium.server.IndexedTickSet;
import dev.lull.sleepium.server.TickIndex;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** TickIndex: the scheduled-tick set indexed by chunk, and getPendingBlockUpdates answered from the index. */
@Mixin(WorldServer.class)
public abstract class WorldServerTickIndexMixin {
    @Shadow @Final private Set<NextTickListEntry> pendingTickListEntriesHashSet;
    @Shadow @Final private TreeSet<NextTickListEntry> pendingTickListEntriesTreeSet;
    @Shadow @Final private List<NextTickListEntry> pendingTickListEntriesThisTick;

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "java/util/TreeSet"))
    private TreeSet<NextTickListEntry> sleep$indexed() {
        return TickIndex.ON ? new IndexedTickSet() : new TreeSet<>();
    }

    @Inject(method = "getPendingBlockUpdates(Lnet/minecraft/world/gen/structure/StructureBoundingBox;Z)Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void sleep$fromIndex(StructureBoundingBox bb, boolean remove, CallbackInfoReturnable<List<NextTickListEntry>> cir) {
        if (!(pendingTickListEntriesTreeSet instanceof IndexedTickSet) || !IndexedTickSet.small(bb)) return;
        IndexedTickSet set = (IndexedTickSet) pendingTickListEntriesTreeSet;
        List<NextTickListEntry> vanilla = TickIndex.VERIFY ? sleep$scan(bb) : null;   // before any removal
        List<NextTickListEntry> list = set.inBox(bb, remove);
        if (remove && list != null)
            for (NextTickListEntry e : list) pendingTickListEntriesHashSet.remove(e);
        // the entries of this tick: vanilla's own walk
        Iterator<NextTickListEntry> it = pendingTickListEntriesThisTick.iterator();
        while (it.hasNext()) {
            NextTickListEntry e = it.next();
            BlockPos p = e.position;
            if (p.getX() >= bb.minX && p.getX() < bb.maxX && p.getZ() >= bb.minZ && p.getZ() < bb.maxZ) {
                if (remove) it.remove();
                if (list == null) list = new ArrayList<>();
                list.add(e);
            }
        }
        if (vanilla != null) TickIndex.compare(vanilla, list);
        TickIndex.ANSWERED++;
        cir.setReturnValue(list);
    }

    /** vanilla's answer, without removing (verify mode) */
    private List<NextTickListEntry> sleep$scan(StructureBoundingBox bb) {
        List<NextTickListEntry> out = null;
        for (int i = 0; i < 2; i++)
            for (NextTickListEntry e : i == 0 ? pendingTickListEntriesTreeSet : pendingTickListEntriesThisTick) {
                BlockPos p = e.position;
                if (p.getX() >= bb.minX && p.getX() < bb.maxX && p.getZ() >= bb.minZ && p.getZ() < bb.maxZ) {
                    if (out == null) out = new ArrayList<>();
                    out.add(e);
                }
            }
        return out;
    }
}
