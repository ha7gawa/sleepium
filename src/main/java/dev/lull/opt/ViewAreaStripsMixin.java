package dev.lull.opt;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dlab.viewAreaStrips (11-17): ViewArea.repositionCamera visits only the columns whose origin changes. Vanilla
 * compares every section's origin with its column's (~101 000 at distance 32: 0.7-0.8 ms of the frame thread at each
 * camera chunk crossing, among the hardest orbit's slowest frames); a section's x depends on its x column only and its
 * z on its z column, so a crossing changes one strip. The columns are visited in vanilla's order and a section is
 * moved exactly when vanilla would move it - given that only this method moves sections (the last call's columns are
 * kept; a new ViewArea starts with vanilla's full pass). -Dlab.viewAreaStrips.verify=true: vanilla's comparisons after
 * each pass, counting sections left at another origin.
 */
@Mixin(ViewArea.class)
public abstract class ViewAreaStripsMixin {
    @Shadow @Final protected Level level;
    @Shadow protected int chunkGridSizeX;
    @Shadow protected int chunkGridSizeY;
    @Shadow protected int chunkGridSizeZ;
    @Shadow public ChunkRenderDispatcher.RenderChunk[] chunks;
    @Unique private int[] sleep$lastX, sleep$lastZ;
    @Unique private static final boolean VERIFY = Boolean.getBoolean("lab.viewAreaStrips.verify");

    @Unique private int sleep$passes;
    @Unique private static final java.util.Random SLEEP$PICK = new java.util.Random(0x51ee9);

    /**
     * (11-17: one 1.20.1 start drew ~160 sections a frame for the whole run - a grid left at wrong origins is never
     * repaired by the strips, which trust the last pass's columns; vanilla's pass compares every section each time.
     * So: one pass at a time on this view area, every 32nd pass vanilla's whole one, and after each strips pass 8
     * sections picked at random checked - any at another origin and vanilla's whole pass runs at once.)
     */
    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void sleep$strips(double viewX, double viewZ, CallbackInfo ci) {
        synchronized (this) {
            sleep$strips0(viewX, viewZ, ci);
        }
    }

    @Unique
    private void sleep$strips0(double viewX, double viewZ, CallbackInfo ci) {
        int gx = chunkGridSizeX, gy = chunkGridSizeY, gz = chunkGridSizeZ;
        int i = Mth.ceil(viewX), j = Mth.ceil(viewZ);
        int lx = gx * 16, i1 = i - 8 - lx / 2, lz = gz * 16, i2 = j - 8 - lz / 2;
        int[] nx = new int[gx], nz = new int[gz];
        for (int k = 0; k < gx; k++) nx[k] = i1 + Math.floorMod(k * 16 - i1, lx);
        for (int k1 = 0; k1 < gz; k1++) nz[k1] = i2 + Math.floorMod(k1 * 16 - i2, lz);
        int[] ox = sleep$lastX, oz = sleep$lastZ;
        sleep$lastX = nx;
        sleep$lastZ = nz;
        if (ox == null || ox.length != gx || oz.length != gz || ++sleep$passes % 32 == 0) return;   // the first pass, and every 32nd: vanilla's
        ci.cancel();
        dev.lull.optsupport.FrustumRegions.vaPasses++;
        int minY = level.getMinBuildHeight();
        for (int k = 0; k < gx; k++) {
            boolean xMoved = nx[k] != ox[k];
            for (int k1 = 0; k1 < gz; k1++) {
                if (!xMoved && nz[k1] == oz[k1]) continue;
                dev.lull.optsupport.FrustumRegions.vaColumns++;
                for (int k2 = 0; k2 < gy; k2++) {
                    int y = minY + k2 * 16;
                    ChunkRenderDispatcher.RenderChunk s = chunks[(k1 * gy + k2) * gx + k];
                    var o = s.getOrigin();
                    if (nx[k] != o.getX() || y != o.getY() || nz[k1] != o.getZ()) s.setOrigin(nx[k], y, nz[k1]);
                }
            }
        }
        for (int t = 0; t < 8; t++) {   // a few sections where they should be, or vanilla's whole pass now
            int k = SLEEP$PICK.nextInt(gx), k1 = SLEEP$PICK.nextInt(gz), k2 = SLEEP$PICK.nextInt(gy);
            var o = chunks[(k1 * gy + k2) * gx + k].getOrigin();
            if (nx[k] != o.getX() || minY + k2 * 16 != o.getY() || nz[k1] != o.getZ()) {
                dev.lull.optsupport.FrustumRegions.vaHealed++;
                for (int a = 0; a < gx; a++)
                    for (int c = 0; c < gz; c++)
                        for (int b = 0; b < gy; b++) {
                            var s = chunks[(c * gy + b) * gx + a];
                            var so = s.getOrigin();
                            if (nx[a] != so.getX() || minY + b * 16 != so.getY() || nz[c] != so.getZ()) s.setOrigin(nx[a], minY + b * 16, nz[c]);
                        }
                break;
            }
        }
        if (VERIFY)
            for (int k = 0; k < gx; k++)
                for (int k1 = 0; k1 < gz; k1++)
                    for (int k2 = 0; k2 < gy; k2++) {
                        var o = chunks[(k1 * gy + k2) * gx + k].getOrigin();
                        if (nx[k] != o.getX() || minY + k2 * 16 != o.getY() || nz[k1] != o.getZ()) {
                            if (dev.lull.optsupport.FrustumRegions.vaVerifyMoved++ < 5) System.out.println("[sleep/opt] view area strips: a section left at " + o + " (column " + k + "," + k1 + ")");
                        }
                    }
    }
}
