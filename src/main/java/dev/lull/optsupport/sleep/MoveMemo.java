package dev.lull.optsupport.sleep;

import dev.lull.optsupport.BlockCollisionMemo;
import dev.lull.optsupport.CompositeEventBus;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * lab.opt.MoveMemo: Entity.move skipped for a living entity whose inputs are those of its last move (a standing mob:
 * MoveShadow, 68-70 % of farm animals' moves). The key is every instance field except the ones that change every tick
 * without move reading them - tickCount, Mob.ambientSoundTime, LivingEntity.noActionTime and the random's seed - plus
 * the mover type and the movement; on a hit the fields the recorded move changed are written back as it left them.
 * <p>Why a hit gives vanilla's result. The recorded move read nothing excluded: the entity's own code reachable from
 * move (its class chain, and the entity methods BlockBehaviour's default callbacks and EntityCollisionContext call)
 * is scanned for reads of those fields once per class, and at runtime the recorded move must have drawn nothing from
 * the entity's random, posted no event anybody listens to (the composite bus' full path), written nothing to the
 * world (BeTickStats: blocks, block entities, sounds, game events, entities), read nothing the sleep engine counts as
 * unobserved, and met only blocks with static collision shapes and BlockBehaviour's own entityInside / stepOn /
 * fallOn / updateEntityAfterFallOn. A move with the same remaining inputs over the same blocks (the sections around
 * the box unchanged: modification counters) then runs the same path and reads the same values, so it ends the same.
 * Collidable entities (boats, shulkers, minecarts) near the box refuse the record (getEntityCollisions reads them).
 * Values only, never identities: move's Vec3 / AABB / BlockPos results are compared by value, and no vanilla code
 * compares them by reference. -Dlab.movememo.verify=true: a hit still runs the move and is compared with the record.
 */
public final class MoveMemo {
    public static final boolean VERIFY = Boolean.getBoolean("lab.movememo.verify");
    private static final Set<String> EXCLUDED = Set.of("tickCount", "ambientSoundTime", "noActionTime", "random", "run", "oRun", "yHeadRot", "yHeadRotO", "yBodyRot", "yBodyRotO");
    /** a record that changed these is refused: written back they would skip setPos' section bookkeeping */
    private static final Set<String> MOVED = Set.of("position", "blockPosition", "chunkPosition", "bb", "feetBlockState");
    public static long hits, records, refusedRecords, misses, mismatches, verified;
    /** -Dlab.movememo.diag=true: misses by reason */
    private static final boolean DIAG = Boolean.getBoolean("lab.movememo.diag");
    private static final Map<String, long[]> MISS = new HashMap<>();
    private static final Map<String, long[]> REFUSALS = new HashMap<>();

    /** per class */
    static final class K {
        Field[] f;
        SnapCompiler.Snapper snap;
        boolean[] skip;
        String refuse;
        int random = -1;

        K(Class<?> c) {
            List<Field> fl = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
                for (Field x : k.getDeclaredFields()) {
                    if (x.getName().equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                    if (Modifier.isStatic(x.getModifiers())) continue;
                    try {
                        x.setAccessible(true);
                        fl.add(x);
                    } catch (RuntimeException e) {
                        refuse = "field not accessible: " + x.getName();
                    }
                }
            if (refuse == null) refuse = ownCodeReads(c);
            // v2: only the fields move's code reads or writes (the scan's sets) - the rest it never touches
            Set<String> rw = new HashSet<>(READ_SETS.getOrDefault(c, Set.of()));
            rw.addAll(WRITE_SETS.getOrDefault(c, Set.of()));
            fl.removeIf(x -> !rw.contains(x.getName()));
            // synched data: by its modification counter (MoveMemoDataMixin), not a deep fingerprint of every item
            fl.removeIf(x -> x.getName().equals("entityData") && x.getDeclaringClass() == Entity.class);
            f = fl.toArray(new Field[0]);
            skip = new boolean[f.length];
            for (int i = 0; i < f.length; i++) {
                skip[i] = EXCLUDED.contains(f[i].getName());
                if (f[i].getName().equals("random") && f[i].getDeclaringClass() == Entity.class) random = i;
            }
            snap = refuse == null ? SnapCompiler.compile(fl, true, new boolean[f.length]) : null;
        }
    }

    /** per entity */
    static final class S {
        long[] key, before, after;
        Object[] afterRefs;
        int[] diff = new int[0];
        MoverType type;
        double mx, my, mz;
        boolean valid;
        final BlockCollisionMemo region = new BlockCollisionMemo();
        // the record in progress
        long draws0, posts0, writes0, unobserved0, readers0;
        int attrEpoch, dataEpoch;
        long collidableEpoch = -1;
        double border0, border1, border2, border3;
    }

    private static final Map<Class<?>, K> KINDS = new HashMap<>();
    /** MoveMemoMixin: the entity's record, on the entity itself (gone with it) */
    public interface Holder {
        Object sleep$moveMemo();

        void sleep$setMoveMemo(Object s);
    }
    /** the entity whose move is being recorded (server thread), for the draw hook */
    static Entity recording;
    public static long draws;
    private static Thread thread;

    private MoveMemo() {}

    private static boolean eligible(Entity e) {
        if (e.level().isClientSide || e instanceof Player || !(e instanceof LivingEntity) || e.isPassenger() || e.isVehicle()) return false;
        if (thread == null) {
            if (!Thread.currentThread().getName().equals("Server thread")) return false;
            thread = Thread.currentThread();
        }
        return Thread.currentThread() == thread && BlockCollisionMemo.usable(e.level());
    }

    /** Move's HEAD: true = skipped (the recorded result written back). */
    static long tHit, tMissCheck, tMove, tRecord, nHit, nMiss;
    private static long tStart, tMoveStart;

    public static boolean before(Entity e, MoverType type, Vec3 v) {
        if (recording != null || !eligible(e)) return false;
        if (DIAG) tStart = System.nanoTime();
        if (((hits + misses + verified) & 0x3FFFF) == 0x3FFFF) System.out.println("[sleep/opt] " + report());
        K k = KINDS.get(e.getClass());
        if (k == null) {
            KINDS.put(e.getClass(), k = new K(e.getClass()));
            System.out.println("[sleep/movememo] " + e.getClass().getSimpleName() + ": " + (k.refuse == null ? "ok, " + k.f.length + " fields" : "no: " + k.refuse)
                    + (DIAG ? "; move reads " + READ_SETS.getOrDefault(e.getClass(), Set.of()).size() + ": " + READ_SETS.get(e.getClass()) : ""));
        }
        if (k.refuse != null) return false;
        S s = (S) ((Holder) e).sleep$moveMemo();
        if (s == null) ((Holder) e).sleep$setMoveMemo(s = new S());
        if (s.before == null) s.before = new long[k.f.length];
        k.snap.snap(e, s.before);
        if (s.valid && type == s.type && v.x == s.mx && v.y == s.my && v.z == s.mz && attrEpoch(e) == s.attrEpoch && dataEpoch(e) == s.dataEpoch && sameKey(k, s.before, s.key)
                && BlockCollisionMemo.unchanged(e.level(), s.region, regionBox(e, v)) && sameBorder(e, s)
                && (dev.lull.optsupport.EntityCollide.COLLIDABLE_EPOCH.get() == s.collidableEpoch || e.level().getEntityCollisions(e, regionBox(e, v)).isEmpty())) {
            if (VERIFY) {
                verified++;
                return false;   // the real move runs; after() compares
            }
            if (WB.TX) SpecTx.touch(e);   // (written past the barrier: the transaction copies the entity first)
            for (int i : s.diff) k.snap.put(e, i, s.after[i], s.afterRefs[i]);
            hits++;
            if (DIAG) {
                tHit += System.nanoTime() - tStart;
                nHit++;
            }
            return true;
        }
        misses++;
        if (DIAG) {
            String why;
            if (!s.valid) why = "no record";
            else if (type != s.type || v.x != s.mx || v.y != s.my || v.z != s.mz) why = "other movement";
            else if (!sameKey(k, s.before, s.key)) {
                why = "key";
                for (int i = 0; i < s.before.length; i++)
                    if (!k.skip[i] && s.before[i] != s.key[i]) {
                        why = "key: " + k.f[i].getName();
                        break;
                    }
            } else if (!sameBorder(e, s)) why = "world border changed";
            else if (!e.level().getEntityCollisions(e, regionBox(e, v)).isEmpty()) why = "collidable entity near";
            else why = "blocks around changed";
            MISS.computeIfAbsent(why, x -> new long[1])[0]++;
        }
        s.valid = false;
        recording = e;
        s.type = type;
        s.mx = v.x;
        s.my = v.y;
        s.mz = v.z;
        s.draws0 = draws;
        s.posts0 = CompositeEventBus.fullPosts;
        s.writes0 = dev.lull.BeTickStats.writes;
        s.unobserved0 = SleepEngine.unobservedAll;
        s.attrEpoch = attrEpoch(e);
        s.dataEpoch = dataEpoch(e);
        s.readers0 = readerRuns;
        if (DIAG) {
            long t = System.nanoTime();
            tMissCheck += t - tStart;
            nMiss++;
            tMoveStart = t;
        }
        return false;
    }

    /** Move's RETURN (not reached for a skipped move). */
    public static void after(Entity e, Vec3 v) {
        S s = (S) ((Holder) e).sleep$moveMemo();
        if (s == null) return;
        K k = KINDS.get(e.getClass());
        if (recording != e) {
            if (VERIFY && s.valid && k != null) check(e, k, s);
            return;
        }
        recording = null;
        long tRec = DIAG ? System.nanoTime() : 0;
        if (DIAG) tMove += tRec - tMoveStart;
        try {
            recordAfter(e, v, s, k);
        } finally {
            if (DIAG) tRecord += System.nanoTime() - tRec;
        }
    }

    private static void recordAfter(Entity e, Vec3 v, S s, K k) {
        if (s.after == null) {
            s.after = new long[k.f.length];
            s.afterRefs = new Object[k.f.length];
        }
        k.snap.snap(e, s.after);
        k.snap.refs(e, s.afterRefs);
        String why = null;
        if (draws != s.draws0) why = "drew from its random";
        else if (CompositeEventBus.fullPosts != s.posts0) why = "posted an event with listeners";
        else if (dev.lull.BeTickStats.writes != s.writes0) why = "wrote to the world";
        else if (SleepEngine.unobservedAll != s.unobserved0) why = "unobserved read";
        else if (readerRuns != s.readers0) why = "ran a reader of an excluded field";
        else if (attrEpoch(e) != s.attrEpoch) why = "changed its attributes";
        else if (dataEpoch(e) != s.dataEpoch) why = "changed its synched data";
        else if (e.isRemoved() || e.isPassenger() || e.isVehicle()) why = "removed / riding";
        int nd = 0;
        int[] diff = new int[8];
        for (int i = 0; why == null && i < s.after.length; i++) {
            if (s.after[i] == s.before[i]) continue;
            if (k.skip[i]) why = "wrote " + k.f[i].getName();
            else if (MOVED.contains(k.f[i].getName()) && k.f[i].getDeclaringClass() == Entity.class) why = "moved";   // section membership: setPos' callback
            else if (!k.f[i].getType().isPrimitive() && !valueType(s.afterRefs[i])) why = "changed " + k.f[i].getName() + " (" + typeName(s.afterRefs[i]) + ")";
            else {
                if (nd == diff.length) diff = java.util.Arrays.copyOf(diff, nd * 2);
                diff[nd++] = i;
            }
        }
        long ce = dev.lull.optsupport.EntityCollide.COLLIDABLE_EPOCH.get();   // read before the check: a later change shows as a new epoch
        if (why == null && !e.level().getEntityCollisions(e, regionBox(e, v)).isEmpty()) why = "collidable entity near";
        s.collidableEpoch = why == null ? ce : -1;
        if (why == null) why = recordRegion(e, v, s);
        if (why != null) {
            refusedRecords++;
            REFUSALS.computeIfAbsent(e.getClass().getSimpleName() + ": " + why, x -> new long[1])[0]++;
            return;
        }
        s.diff = java.util.Arrays.copyOf(diff, nd);
        if (s.key == null) s.key = new long[k.f.length];
        System.arraycopy(s.before, 0, s.key, 0, s.key.length);
        var wb = e.level().getWorldBorder();
        s.border0 = wb.getMinX();
        s.border1 = wb.getMaxX();
        s.border2 = wb.getMinZ();
        s.border3 = wb.getMaxZ();
        s.valid = true;
        records++;
    }

    /** verify mode: a hit's real move against the record */
    private static void check(Entity e, K k, S s) {
        long[] now = new long[k.f.length];
        k.snap.snap(e, now);
        for (int i = 0; i < now.length; i++) {
            if (k.skip[i]) continue;
            if (now[i] != s.after[i]) {
                mismatches++;
                if (mismatches <= 20) System.out.println("[sleep/movememo] MISMATCH " + e.getClass().getSimpleName() + "." + k.f[i].getName());
                s.valid = false;
                return;
            }
        }
    }

    /** AttributeMap's modification counter (MoveMemoAttributesMixin): attributes are read by value through it */
    public interface AttrEpoch {
        int sleep$attrEpoch();
    }

    /** SynchedEntityData's modification counter (MoveMemoDataMixin) */
    public interface DataEpoch {
        int sleep$dataEpoch();
    }

    private static int dataEpoch(Entity e) {
        return ((DataEpoch) e.getEntityData()).sleep$dataEpoch();
    }

    private static int attrEpoch(Entity e) {
        return ((AttrEpoch) ((LivingEntity) e).getAttributes()).sleep$attrEpoch();
    }

    /** collide reads the world border's shape near it: as it was at the record */
    private static boolean sameBorder(Entity e, S s) {
        var wb = e.level().getWorldBorder();
        return wb.getMinX() == s.border0 && wb.getMaxX() == s.border1 && wb.getMinZ() == s.border2 && wb.getMaxZ() == s.border3;
    }

    private static boolean sameKey(K k, long[] a, long[] b) {
        if (b == null) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i] && !k.skip[i]) return false;
        return true;
    }

    /** every block the move can read: the box grown by the movement, one block around (collisions, inside blocks, the
     * fire check), and down to the block under the feet (the supporting block, the speed factor, getOnPosLegacy) */
    private static AABB regionBox(Entity e, Vec3 v) {
        return e.getBoundingBox().expandTowards(v).inflate(1.0).expandTowards(0, -1.0, 0);
    }

    /** null when recorded, else why not */
    private static String recordRegion(Entity e, Vec3 v, S s) {
        AABB box = regionBox(e, v);
        if (!BlockCollisionMemo.record(e.level(), s.region, box)) return "blocks without a static shape (or > 8 sections)";
        // callbacks move makes on blocks: BlockBehaviour's own only
        int x0 = net.minecraft.util.Mth.floor(box.minX), x1 = net.minecraft.util.Mth.floor(box.maxX);
        int y0 = net.minecraft.util.Mth.floor(box.minY), y1 = net.minecraft.util.Mth.floor(box.maxY);
        int z0 = net.minecraft.util.Mth.floor(box.minZ), z1 = net.minecraft.util.Mth.floor(box.maxZ);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                {
                    Block b = e.level().getBlockState(p.set(x, y, z)).getBlock();
                    if (!DEFAULT_CALLBACKS.get(b.getClass())) return "block callbacks of " + b.getClass().getSimpleName();
                }
        return null;
    }

    private static final ClassValue<Boolean> DEFAULT_CALLBACKS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            if (c == Block.class || c == BlockBehaviour.class) return true;   // where the defaults are
            for (Method m : c.getMethods()) {
                String n = m.getName();
                if ((n.equals("entityInside") || n.equals("stepOn") || n.equals("fallOn") || n.equals("updateEntityAfterFallOn"))
                        && m.getDeclaringClass() != Block.class && m.getDeclaringClass() != BlockBehaviour.class) return false;
            }
            try {
                for (Method m : c.getDeclaredMethods()) {   // protected entityInside overrides are not in getMethods
                    if (m.getName().equals("entityInside") || m.getName().equals("stepOn") || m.getName().equals("fallOn")
                            || m.getName().equals("updateEntityAfterFallOn")) return false;
                }
                for (Class<?> s = c.getSuperclass(); s != null && s != Block.class && s != BlockBehaviour.class; s = s.getSuperclass())
                    for (Method m : s.getDeclaredMethods())
                        if (m.getName().equals("entityInside") || m.getName().equals("stepOn") || m.getName().equals("fallOn")
                                || m.getName().equals("updateEntityAfterFallOn")) return false;
            } catch (RuntimeException e) {
                return false;
            }
            return true;
        }
    };

    private static boolean valueType(Object o) {
        return o == null || o instanceof Vec3 || o instanceof AABB || o instanceof BlockPos && !(o instanceof BlockPos.MutableBlockPos)
                || o instanceof Optional<?> opt && (opt.isEmpty() || valueType(opt.get())) || o instanceof Boolean || o instanceof Number
                || o instanceof String || o instanceof Enum<?>;
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    /** the draw hook: a draw from the recorded entity's random */
    public static void draw(Object random) {
        Entity r = recording;
        if (r != null && random == r.getRandom()) draws++;
    }

    // ---------------------------------------------------------------- the entity's own code: no excluded field read

    /**
     * The vanilla methods on move's paths that read an excluded field (the scan lists them): MoveMemoReadersMixin and
     * MoveMemoHurtMixin mark their runs, and a record during which one ran is refused. Any other reader refuses the class.
     */
    static final Set<String> INSTRUMENTED = Set.of("net/minecraft/world/entity/LivingEntity.setLastHurtByMob", "net/minecraft/world/entity/LivingEntity.hurt",
            "net/minecraft/world/entity/Entity.shouldPlayAmethystStepSound", "net/minecraft/world/entity/Entity.playAmethystStepSound");
    static long readerRuns;

    /** an instrumented reader ran on entity e */
    public static void readerRan(Entity e) {
        if (recording == e) readerRuns++;
    }

    /** entity methods the default block callbacks and EntityCollisionContext call: scanned with move */
    private static final String[][] EXTRA_ROOTS = {
            {"causeFallDamage", "(FFLnet/minecraft/world/damagesource/DamageSource;)Z"},
            {"setDeltaMovement", "(Lnet/minecraft/world/phys/Vec3;)V"},
            {"getDeltaMovement", "()Lnet/minecraft/world/phys/Vec3;"},
            {"isDescending", "()Z"},
            {"getMainHandItem", "()Lnet/minecraft/world/item/ItemStack;"},
            {"canStandOnFluid", "(Lnet/minecraft/world/level/material/FluidState;)Z"},
            {"damageSources", "()Lnet/minecraft/world/damagesource/DamageSources;"},
    };

    /** null if no method of the class chain reachable from move (and the extra roots) reads an excluded field */
    /** every reader found (diagnosis): -Dlab.movememo.allReaders=true */
    static String ownCodeReads(Class<?> c) {
        if (Boolean.getBoolean("lab.movememo.allReaders")) {
            List<String> all = new ArrayList<>();
            String r;
            Set<String> skipM = new HashSet<>();
            while ((r = ownCodeReads(c, skipM)) != null && all.size() < 40) {
                all.add(r);
                skipM.add(r.substring(r.lastIndexOf(' ') + 1));
            }
            return all.isEmpty() ? null : String.join(" | ", all);
        }
        return ownCodeReads(c, Set.of());
    }

    /** the last scan's read set: fields of the class chain read by the scanned methods (owner.name) */
    static final Map<Class<?>, Set<String>> READ_SETS = new HashMap<>();
    static final Map<Class<?>, Set<String>> WRITE_SETS = new HashMap<>();

    static String ownCodeReads(Class<?> c, Set<String> skipMethods) {
        Set<String> reads = new java.util.TreeSet<>(), writes = new java.util.TreeSet<>();
        READ_SETS.put(c, reads);
        WRITE_SETS.put(c, writes);
        List<Class<?>> chain = new ArrayList<>();
        Set<String> owners = new HashSet<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            chain.add(k);
            owners.add(Type.getInternalName(k));
        }
        Map<String, ClassNode> nodes = new HashMap<>();
        ArrayDeque<String[]> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        todo.add(new String[] {"move", "(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"});
        for (String[] r : EXTRA_ROOTS) todo.add(r);
        while (!todo.isEmpty()) {
            String[] nm = todo.poll();
            if (!seen.add((nm.length > 2 ? nm[2] + "." : "") + nm[0] + nm[1])) continue;
            MethodNode m = null;
            String at = null;
            if (nm.length > 2) {   // a static method: exact
                ClassNode cn = node(nodes, nm[2]);
                if (cn != null) for (MethodNode mm : cn.methods)
                    if (mm.name.equals(nm[0]) && mm.desc.equals(nm[1])) {
                        m = mm;
                        at = cn.name;
                    }
            } else for (Class<?> k : chain) {   // virtual dispatch from the concrete class
                ClassNode cn = node(nodes, Type.getInternalName(k));
                if (cn == null) return "class bytes unavailable: " + k.getName();
                for (MethodNode mm : cn.methods)
                    if (mm.name.equals(nm[0]) && mm.desc.equals(nm[1])) {
                        m = mm;
                        at = cn.name;
                        break;
                    }
                if (m != null) break;
            }
            if (m == null) continue;   // declared outside the chain (an interface default): not entity code
            if (skipMethods.contains(at + "." + m.name) || INSTRUMENTED.contains(at + "." + m.name)) continue;
            if (dev.lull.ClassBytes.unseen(at, m.name)) return "mixin-changed " + at + "." + m.name;   // the scan reads the original bytes
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof FieldInsnNode fw && fw.getOpcode() == Opcodes.PUTFIELD && owners.contains(fw.owner)) {
                    writes.add(fw.name);
                    if (EXCLUDED.contains(fw.name)) return "writes " + fw.name + " in " + at + "." + m.name;
                }
                if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD && owners.contains(fi.owner)) {
                    reads.add(fi.name);
                    if (EXCLUDED.contains(fi.name) && !fi.name.equals("random")) return "reads " + fi.name + " in " + at + "." + m.name;
                }
                if (in instanceof MethodInsnNode mi) {
                    if (owners.contains(mi.owner)) {
                        if (mi.name.equals("getNoActionTime")) return "calls getNoActionTime in " + at + "." + m.name;
                        if (mi.getOpcode() == Opcodes.INVOKESTATIC) todo.add(new String[] {mi.name, mi.desc, mi.owner});
                        else todo.add(new String[] {mi.name, mi.desc});
                    } else if (mi.getOpcode() == Opcodes.INVOKESTATIC && (mi.owner.startsWith("net/minecraft/") || mi.owner.startsWith("net/neoforged/"))) {
                        todo.add(new String[] {mi.name, mi.desc, mi.owner});
                    }
                }
            }
        }
        return null;
    }

    private static ClassNode node(Map<String, ClassNode> nodes, String internal) {
        return nodes.computeIfAbsent(internal, n -> {
            try {
                return dev.lull.ClassBytes.read(Entity.class.getClassLoader(), n, ClassReader.SKIP_FRAMES);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public static String report() {
        StringBuilder sb = new StringBuilder((DIAG ? String.format(java.util.Locale.ROOT, "costs (us): hit %.2f, miss check %.2f, recorded move %.2f, record %.2f; ",
                tHit / 1e3 / Math.max(1, nHit), tMissCheck / 1e3 / Math.max(1, nMiss), tMove / 1e3 / Math.max(1, nMiss), tRecord / 1e3 / Math.max(1, nMiss)) : "")
                + "move memo: hits " + hits + ", misses " + misses + ", records " + records + ", refused records " + refusedRecords
                + (VERIFY ? ", verified " + verified + ", MISMATCHES " + mismatches : ""));
        for (var en : KINDS.entrySet()) if (en.getValue().refuse != null) sb.append("\n   class ").append(en.getKey().getSimpleName()).append(": ").append(en.getValue().refuse);
        MISS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(10)
                .forEach(x -> sb.append("\n   miss ").append(x.getValue()[0]).append("x ").append(x.getKey()));
        REFUSALS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(10)
                .forEach(x -> sb.append("\n   refused ").append(x.getValue()[0]).append("x ").append(x.getKey()));
        return sb.toString();
    }
}
