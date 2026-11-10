package dev.lull.optsupport.sleep;

import dev.lull.optsupport.MixinTouch;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Runtime bytecode analysis of a block-entity class (once per class): which of its fields are clocks and which
 * conditions on them decide when something happens. Port of experiments/be-analysis, returning a structured
 * {@link Plan} instead of text, and deliberately conservative: anything it cannot prove makes the class ineligible.
 */
final class SleepAnalysis {
    static final String BE = "net/minecraft/world/level/block/entity/BlockEntity";
    static final String TICKER_RET = ")Lnet/minecraft/world/level/block/entity/BlockEntityTicker;";
    /** own call graph depth; deeper own calls are refused (their conditions would go unseen) */
    static final int MAX_DEPTH = 64;
    /** Owned-object types whose method calls cannot hide state from the field snapshot (immutable or snapshotted deeply). */
    static final Set<String> SAFE_OWNED = Set.of("net/minecraft/core/NonNullList", "net/minecraft/world/item/ItemStack",
            "net/minecraft/core/Direction", "net/minecraft/core/BlockPos", "net/minecraft/world/level/block/state/BlockState",
            "java/lang/Integer", "java/lang/Boolean", "java/lang/String", "net/minecraft/resources/ResourceKey",
            // memoization caches: their state never changes a result, only how fast it is found
            "net/minecraft/world/item/crafting/RecipeManager$CachedCheck");
    static final Set<String> BASE_FIELDS = Set.of("level", "worldPosition", "blockState", "remove", "components", "hasSavedData");

    /** A condition "clock ? threshold": the engine wakes before its outcome can change. */
    /**
     * clock ? const/field; or gameTime % mod ? const (clock null); or (clock + x) % mod ? y (clock set, mod > 0,
     * {@code clockMod}): a periodic condition on a clock, whose outcome can change every tick.
     */
    record Cond(Field clock, Field thresholdField, long thresholdConst, long mod, String text, boolean clockMod, boolean peer, boolean age, boolean band,
            long bandHi) {
        Cond(Field clock, Field thresholdField, long thresholdConst, long mod, String text) {
            this(clock, thresholdField, thresholdConst, mod, text, false, false, false, false, 0);
        }

        Cond(Field clock, Field thresholdField, long thresholdConst, long mod, String text, boolean clockMod) {
            this(clock, thresholdField, thresholdConst, mod, text, clockMod, false, false, false, 0);
        }

        Cond(Field clock, Field thresholdField, long thresholdConst, long mod, String text, boolean clockMod, boolean peer) {
            this(clock, thresholdField, thresholdConst, mod, text, clockMod, peer, false, false, 0);
        }

        Cond(Field clock, Field thresholdField, long thresholdConst, long mod, String text, boolean clockMod, boolean peer, boolean age) {
            this(clock, thresholdField, thresholdConst, mod, text, clockMod, peer, age, false, 0);
        }

        /**
         * A value computed from the clock varies while the clock is inside (lo, hi) and is constant outside: no sleep
         * inside, a deadline before entering (clamp / min / max of an affine expression of the clock).
         */
        static Cond band(Field clock, long lo, long hi, String text) {
            return new Cond(clock, null, lo, 0, text, false, false, false, true, hi);
        }

        /** entity mode: the clock compared with the same field of another instance (a neighbour read by the tick) */
        static Cond peer(Field clock, String text) {
            return new Cond(clock, null, 0, 0, text, false, true);
        }

        /** gameTime - stamp compared with a constant: the age grows by 1 a tick, its outcome flips at most once */
        static Cond age(Field stamp, long c, String text) {
            return new Cond(stamp, null, c, 0, text, false, false, true);
        }
    }

    /** What the engine may do with a class. */
    /** What the engine may do with a class; {@code watch} = the only fields a tick can change (clocks included). */
    /**
     * {@code nulls} (entity mode): object fields that must be null while asleep. Code that would only run with such a
     * field set (behind a null test on it, or after a call on it) is excluded from the analysis.
     */
    record Plan(boolean eligible, String reason, List<Field> clocks, List<Cond> conds, List<Field> watch, long period, List<Field> nulls, List<Field> zeros,
            List<DrawCond> draws, MobPlan mob) {
        Plan(boolean eligible, String reason, List<Field> clocks, List<Cond> conds, List<Field> watch, long period, List<Field> nulls, List<Field> zeros) {
            this(eligible, reason, clocks, conds, watch, period, nulls, zeros, List.of(), null);
        }

        Plan(boolean eligible, String reason, List<Field> clocks, List<Cond> conds) {
            this(eligible, reason, clocks, conds, List.of(), 1, List.of(), List.of());
        }

        Plan(boolean eligible, String reason, List<Field> clocks, List<Cond> conds, List<Field> watch) {
            this(eligible, reason, clocks, conds, watch, 1, List.of(), List.of());
        }
    }

    /**
     * A draw of the entity's own random number (Entity.random, however reached: getRandom(), a goal's mob.getRandom())
     * compared in a jump (mob sleep, step C, notes/47). {@code site}: owner.method+desc@line#k (the k-th draw on that
     * line), what a stack frame of the draw call names. The comparison: the jump opcode, the compare instruction (0: the
     * jump tests the draw itself, nextInt(n) != 0), which side the draw is, and the other side - a constant
     * ({@code constant}) or a clock (path + field, plus {@code constant}).
     */
    /**
     * Mob sleep (-Dlab.sleep.components with -Dlab.sleep.mobRuntime): what the runtime needs beyond the entity's own
     * fields. Paths name objects from the entity ("" itself, "navigation", "goalSelector.availableGoals[3].goal").
     * {@code clockPaths} / {@code condPaths}: the context of Plan.clocks / Plan.conds, in their order. Assumptions are
     * "path.name" (a field, or a boolean method for {@code falseCalls}, "name#n" with an int argument).
     */
    record MobPlan(List<String> contexts, List<String> clockPaths, List<String> condPaths, Set<String> nulls, Set<String> zeros, Set<String> le0,
            Set<String> ge0, Set<String> falseCalls, Set<String> empty, List<DrawCond> draws, Set<String> captured, List<long[]> inlineTicks,
            List<String> stillClocks) {}

    record DrawCond(String site, String draw, int jumpOp, int cmpOp, boolean drawFirst, double constant, int other, String path, Field field, String text) {
        static final int CONST = 0, CLOCK = 1, FINAL = 2, UNKNOWN = 3;
    }

    private final List<DrawCond> draws = new ArrayList<>();
    /** owned objects handed to foreign code in lambdas: their whole state must be in the runtime snapshot */
    private final Set<String> capturedContexts = new java.util.TreeSet<>();
    private Object sampleRandom;

    private final Map<String, ClassNode> nodes = new HashMap<>();
    private final ClassLoader loader;
    private final Map<String, Field> clocks = new LinkedHashMap<>();
    private final List<Cond> conds = new ArrayList<>();
    /** the context path of each condition's clock ("" the entity), in the order of conds */
    private final List<String> condPaths = new ArrayList<>();

    private void addCond(Cond c) {
        conds.add(c);
        condPaths.add(condCtx != null ? condCtx : ctxPath);
    }
    /** fields the followed code writes, plus owned containers whose contents it may change in place */
    private final Map<String, Field> watch = new LinkedHashMap<>();
    private String reject;
    private Set<String> chain;      // the block entity's class chain (internal names), for provenance of values
    /**
     * Entity mode, mob sleep (notes/47): a sample instance gives the runtime classes of the entity's AI objects; the
     * analysis follows calls into them in their own context (ctxPath from the entity, ctxObj the sample's object).
     */
    private Object sample;
    private String ctxPath = "";
    private Object ctxObj;
    /** contexts entered (paths), for the verdict */
    private final Set<String> contexts = new java.util.TreeSet<>();
    private Class<?> beCls;

    /** entity mode: the engine snapshots every field of the entity, so owned objects whose state it sees are allowed */
    private boolean entityMode;
    /** an entity class analysed for the client's levels: isClientSide is true (the server's: false) */
    private boolean clientMode;
    /** diagnostics (-Dlab.sleep.allReasons): keep going after a refusal and collect every reason */
    private static final boolean ALL_REASONS = Boolean.getBoolean("lab.sleep.allReasons");
    private static final boolean TRACE = NULL_WHY_OR_TRACE();
    private static boolean NULL_WHY_OR_TRACE() {
        return Boolean.getBoolean("lab.sleep.nullWhy") || !System.getProperty("lab.sleep.nullTrace", "").isEmpty();
    }
    private final java.util.ArrayDeque<String> STACK = new java.util.ArrayDeque<>();
    private static final String NULL_TRACE = System.getProperty("lab.sleep.nullTrace", "");
    private static final String WHO_WRITES = System.getProperty("lab.sleep.whoWrites", "");
    private static final boolean NULL_WHY = Boolean.getBoolean("lab.sleep.nullWhy");
    private final java.util.Map<String, Integer> reasons = new java.util.LinkedHashMap<>();

    private boolean ok() {
        return ok(null);
    }

    private boolean ok(String here) {
        if ((ALL_REASONS || collect) && reject != null) {
            String key = here == null ? reject : reject + " @" + here;
            reasons.merge(key, 1, Integer::sum);
            if (collect) {
                Set<String> g = reasonGuards.get(key);
                if (g == null) reasonGuards.put(key, new HashSet<>(curGuards));
                else g.retainAll(curGuards);
            }
            reject = null;
        }
        return reject == null;
    }

    /**
     * Mob analysis (a sample given): every reason is collected with its guards - the assumptions that would make the
     * code giving it unreachable: "zero:f" (an own int / boolean field is 0), "false:m" (an own boolean method returns
     * false: isUsingItem, a goal's isRunning), "null:f" (an own object field is null). Paths name fields of contexts
     * (navigation.path). "rv:..." guards are on a local's boolean call, resolved where that local is a call's receiver.
     */
    private boolean collect;
    private final Map<String, Set<String>> reasonGuards = new HashMap<>();
    /** where each null candidate arose: the guards that would remove that code at any of its places */
    private final Map<String, Set<String>> nullGuards = new HashMap<>();

    private void nullGuard(String key) {
        if (!collect) return;
        if (key.equals(NULL_TRACE)) System.out.println("[sleep/sleep] null " + key + " guards " + curGuards + " assumed " + zeroFields + falseCalls + emptyFields + " stack " + STACK);
        // the union: each place needs one of its own guards (the search keeps neutral steps until all are covered)
        nullGuards.computeIfAbsent(key, k -> new HashSet<>()).addAll(curGuards);
    }
    private Set<String> chainGuards = Set.of(), curGuards = Set.of();
    private final Set<String> falseCalls = new java.util.TreeSet<>(), forcedNulls = new java.util.TreeSet<>(), emptyFields = new java.util.TreeSet<>();
    /** sign assumptions: an own int field <= 0 (tested with > 0: remainingFireTicks is -1 at rest) or >= 0 */
    private final Set<String> le0Fields = new java.util.TreeSet<>(), ge0Fields = new java.util.TreeSet<>();

    private boolean signField(Operand o, Set<String> set) {
        if (set.isEmpty() || !entityMode || o == null || o.kind != Kind.FIELD || !o.self) return false;
        if (!ctxPath.isEmpty()) return set.contains(ctxPath + "." + o.name);
        return chain != null && chain.contains(o.owner) && set.contains(o.name);
    }
    /** flow(): one jump forced to one successor (the guards' reachability) */
    private int forceAt = -1, forceTo;

    private String curHere = "";

    private String pathKey(String name) {
        return ctxPath.isEmpty() ? name : ctxPath + "." + name;
    }

    /** the clock key of a field operand, in its own context (the entity's when read through a goal's mob field) */
    private String keyOf(Operand o) {
        if (o.ctx == null) return fkey(o.owner, o.name);
        Field f = sample == null ? null : field(sample.getClass(), o.name);
        return f != null ? internal(f.getDeclaringClass()) + "." + o.name : o.owner + "." + o.name;
    }

    /** the path of a field operand's slot ("" context: the entity's own field) */
    private String pathOf(Operand o) {
        if (o.ctx == null) return pathKey(o.name);
        return o.ctx.isEmpty() ? o.name : o.ctx + "." + o.name;
    }

    /** the context of the first field operand in an expression read on another context, null if none */
    private static String ctxOf(Operand o) {
        if (o == null) return null;
        if (o.kind == Kind.FIELD && o.ctx != null) return o.ctx;
        String a = ctxOf(o.a);
        return a != null ? a : ctxOf(o.b);
    }

    /** the context path a condition being added belongs to (null: the current one) */
    private String condCtx;

    /** entity mode: own object fields assumed null (see Plan.nulls); grown by candidates until a fixpoint */
    private final Set<String> nullFields = new HashSet<>();
    /**
     * Entity mode: clocks assumed to be 0 while asleep (code behind a "clock > 0" / "!= 0" test on them is excluded; at
     * runtime the entity sleeps only with them at 0). Chosen one by one, kept when the assumption removes a reason:
     * lerpSteps and its kind are 0 on a server (only packets set them on clients).
     */
    private final Set<String> zeroFields = new HashSet<>();

    private boolean zeroField(Operand o) {
        if (!entityMode || o == null || o.kind != Kind.FIELD || !o.self) return false;
        if (!ctxPath.isEmpty()) return zeroFields.contains(ctxPath + "." + o.name);
        return chain != null && chain.contains(o.owner) && zeroFields.contains(o.name);
    }

    /** a level's isClientSide (the field, or the method): false wherever an entity can sleep (server levels only) */
    private static boolean clientSide(Operand o) {
        return o != null && (o.kind == Kind.FIELD && "isClientSide".equals(o.name) && "net/minecraft/world/level/Level".equals(o.owner)
                || o.kind == Kind.CALL && o.name != null && o.name.endsWith(".isClientSide"));
    }
    private final Set<String> nullCandidates = new HashSet<>();

    private boolean nullField(Operand o) {
        if (!entityMode || o == null || o.kind != Kind.FIELD || !o.self) return false;
        if (!ctxPath.isEmpty()) return nullFields.contains(ctxPath + "." + o.name);
        return chain != null && chain.contains(o.owner) && nullFields.contains(o.name);
    }

    /** Frames plus the instructions that can run under the null assumptions (all reachable ones without assumptions). */
    /** decided: per jump the only successor the liveness pass followed (-2: not decided) */
    /**
     * A condition whose two successors meet before anything happens: from the fall-through, loads, Entity.level() and
     * branches the liveness pass decided (isClientSide, zero clocks) lead to the jump's target (or the other way round).
     * Its outcome changes nothing - e.g. on the client `tickCount % i == 0 && !level().isClientSide && ...`.
     */
    private static boolean deadCondition(AbstractInsnNode[] in, int i, MethodNode m, Flow flow) {
        if (flow.decided() == null) return false;
        int target = m.instructions.indexOf(((JumpInsnNode) in[i]).label);
        return reaches(in, i + 1, target, m, flow) || reaches(in, target, i + 1, m, flow);
    }

    private static boolean reaches(AbstractInsnNode[] in, int from, int to, MethodNode m, Flow flow) {
        int at = from;
        for (int steps = 0; steps < 64 && at >= 0 && at < in.length; steps++) {
            if (at == to) return true;
            AbstractInsnNode x = in[at];
            int op = x.getOpcode();
            int d = flow.decided()[at];
            if (op < 0 || op == Opcodes.ALOAD || op == Opcodes.ILOAD || op == Opcodes.LLOAD || op == Opcodes.GETFIELD || op == Opcodes.NOP) {
                at++;
            } else if (x instanceof MethodInsnNode mi && op == Opcodes.INVOKEVIRTUAL && mi.name.equals("level") && mi.desc.startsWith("()")) {
                at++;   // Entity.level(): the level field, nothing else
            } else if (op == Opcodes.GOTO) {
                at = m.instructions.indexOf(((JumpInsnNode) x).label);
            } else if (x instanceof JumpInsnNode && d >= 0) {
                at = d;   // decided: the only way on (its operands were loads)
            } else {
                return false;
            }
        }
        return false;
    }

    private record Flow(Frame<SourceValue>[] fr, boolean[] live, int[] decided) {}

    private record Frames(Frame<SourceValue>[] fr, List<int[]> edges) {}

    private final Map<MethodNode, Frames> framesCache = new java.util.IdentityHashMap<>();

    private Flow flow(String owner, MethodNode m) throws AnalyzerException {
        int n = m.instructions.size();
        Frames cached = framesCache.get(m);
        if (cached == null) {
            List<int[]> ed = new ArrayList<>();
            Analyzer<SourceValue> an = new Analyzer<>(new SourceInterpreter()) {
                @Override
                protected void newControlFlowEdge(int insn, int succ) {
                    ed.add(new int[] {insn, succ});
                }

                @Override
                protected boolean newControlFlowExceptionEdge(int insn, int succ) {
                    ed.add(new int[] {insn, succ, 1});
                    return true;
                }
            };
            framesCache.put(m, cached = new Frames(dev.lull.ClassBytes.analyze(an, owner, m), ed));
        }
        List<int[]> edges = cached.edges();
        Frame<SourceValue>[] fr = cached.fr();
        boolean[] live = new boolean[n];
        int[] decided = new int[n];
        java.util.Arrays.fill(decided, -2);
        if (nullFields.isEmpty() && zeroFields.isEmpty() && !entityMode) {
            for (int i = 0; i < n; i++) live[i] = fr[i] != null;
            return new Flow(fr, live, null);
        }
        int[] cnt = new int[n + 1];
        for (int[] e : edges) cnt[e[0] + 1]++;
        for (int i = 0; i < n; i++) cnt[i + 1] += cnt[i];
        int[] adj = new int[edges.size()], exc = new int[edges.size()], pos = cnt.clone();
        for (int[] e : edges) {
            exc[pos[e[0]]] = e.length > 2 ? 1 : 0;
            adj[pos[e[0]]++] = e[1];
        }
        AbstractInsnNode[] in = m.instructions.toArray();
        int[] stack = new int[n];
        int sp = 0;
        if (n > 0) {
            live[0] = true;
            stack[sp++] = 0;
        }
        while (sp > 0) {
            int i = stack[--sp];
            int op = in[i].getOpcode();
            int only = -2;   // -2: all normal successors; -1: none; else the only one
            String fc;
            if (i == forceAt) {
                only = forceTo;
            } else if ((op == Opcodes.IFLE || op == Opcodes.IFGT) && fr[i] != null && signField(operand(fr[i], 1, fr, m, 0), le0Fields)) {
                int target = m.instructions.indexOf(((JumpInsnNode) in[i]).label);
                only = op == Opcodes.IFLE ? target : i + 1;
            } else if ((op == Opcodes.IFLT || op == Opcodes.IFGE) && fr[i] != null && signField(operand(fr[i], 1, fr, m, 0), ge0Fields)) {
                int target = m.instructions.indexOf(((JumpInsnNode) in[i]).label);
                only = op == Opcodes.IFGE ? target : i + 1;
            } else if (!emptyFields.isEmpty() && (op == Opcodes.IFEQ || op == Opcodes.IFNE) && fr[i] != null
                    && (fc = emptyCall(fr, i, m)) != null && emptyFields.contains(fc.substring(6))) {
                int target = m.instructions.indexOf(((JumpInsnNode) in[i]).label);
                boolean value = fc.charAt(4) == '1';   // isEmpty() is true, hasNext() false
                only = (op == Opcodes.IFNE) == value ? target : i + 1;
            } else if (!falseCalls.isEmpty() && (op == Opcodes.IFEQ || op == Opcodes.IFNE) && fr[i] != null
                    && (fc = falseCall(fr, i, m)) != null && falseCalls.contains(fc.substring(6))) {
                int target = m.instructions.indexOf(((JumpInsnNode) in[i]).label);
                only = op == Opcodes.IFEQ ? target : i + 1;
            } else if ((op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) && nullField(operand(fr[i], 1, fr, m, 0))) {
                int target = m.instructions.indexOf(((JumpInsnNode) in[i]).label);
                only = op == Opcodes.IFNULL ? target : i + 1;
            } else if (entityMode && op >= Opcodes.IFEQ && op <= Opcodes.IFLE && fr[i] != null
                    && (zeroField(operand(fr[i], 1, fr, m, 0)) || clientSide(operand(fr[i], 1, fr, m, 0)))) {
                // the value is known: 0 for a zero clock and for isClientSide on the server, 1 for isClientSide on the client
                int target = m.instructions.indexOf(((JumpInsnNode) in[i]).label);
                int v = clientMode && !zeroField(operand(fr[i], 1, fr, m, 0)) ? 1 : 0;
                boolean taken = switch (op) {
                    case Opcodes.IFEQ -> v == 0;
                    case Opcodes.IFNE -> v != 0;
                    case Opcodes.IFLT -> v < 0;
                    case Opcodes.IFGE -> v >= 0;
                    case Opcodes.IFGT -> v > 0;
                    default -> v <= 0;
                };
                only = taken ? target : i + 1;
            } else if (in[i] instanceof MethodInsnNode mi && op != Opcodes.INVOKESTATIC
                    && nullField(operand(fr[i], Type.getArgumentTypes(mi.desc).length + 1, fr, m, 0))) {
                only = -1;   // a call on a null field: throws
            } else if ((op == Opcodes.GETFIELD || op == Opcodes.ARRAYLENGTH) && nullField(operand(fr[i], 1, fr, m, 0))) {
                only = -1;
            } else if (op == Opcodes.PUTFIELD && nullField(operand(fr[i], 2, fr, m, 0))) {
                only = -1;
            }
            decided[i] = only;
            for (int k = cnt[i]; k < cnt[i + 1]; k++) {
                int t = adj[k];
                if (exc[k] == 0 && only != -2 && t != only) continue;
                if (!live[t] && fr[t] != null) {
                    live[t] = true;
                    stack[sp++] = t;
                }
            }
        }
        return new Flow(fr, live, decided);
    }

    private SleepAnalysis(ClassLoader loader) {
        this.loader = loader;
    }

    /**
     * Entity classes: the root is the class's tick(); Entity.tickCount is a clock advanced by the level's tick wrapper.
     * The plan's period is the lcm of all clock moduli (1 if none).
     */
    static Plan analyzeEntity(Class<?> entityClass) {
        return analyzeEntity(entityClass, null);
    }

    /** with a sample instance: its AI objects (controls, navigation, goal selectors, goals) are followed (notes/47) */
    static Plan analyzeEntity(Class<?> entityClass, Object sample) {
        return analyzeEntity(entityClass, sample, false);
    }

    /** client: for the client's levels - every isClientSide branch takes the client's side */
    static Plan analyzeEntity(Class<?> entityClass, Object sample, boolean client) {
        SleepAnalysis a = new SleepAnalysis(entityClass.getClassLoader());
        a.entityMode = true;
        a.clientMode = client;
        a.sample = sample;
        a.ctxObj = sample;
        a.collect = sample != null;
        try {
            return a.runEntity(entityClass);
        } catch (Exception | LinkageError e) {
            return new Plan(false, "analysis failed: " + e, List.of(), List.of());
        }
    }

    /** the analysis rounds (null fields to a fixpoint) under the current zero assumptions; the number of reasons */
    private int rounds(String decl, MethodNode root, Class<?> ec, Field tickCount, Set<String> chainSet, Set<String> seen) throws AnalyzerException {
        nullFields.clear();
        nullFields.addAll(forcedNulls);
        long t0 = System.nanoTime();
        runCount++;
        for (int round = 0; ; round++) {
            roundCount++;
            clocks.clear();
            clocks.put(fkey("net/minecraft/world/entity/Entity", "tickCount"), tickCount);
            conds.clear();
            condPaths.clear();
            watch.clear();
            moduli.clear();
            modKinds.clear();
            reasons.clear();
            reasonGuards.clear();
            draws.clear();
            capturedContexts.clear();
            contexts.clear();
            chainGuards = curGuards = Set.of();
            reject = null;
            nullCandidates.clear();
            nullGuards.clear();
            writtenNow.clear();
            seen.clear();
            follow(decl, root, 0, seen, chainSet, ec);
            if (ok()) {
                seen.clear();
                branches(decl, root, 0, seen, chainSet, ec);
            }
            boolean writesSettled = !collect || writtenNow.equals(writtenAny);
            if (collect) {
                writtenAny = writtenNow;
                writtenNow = new HashSet<>();
            }
            if (nullFields.containsAll(nullCandidates) && writesSettled || round == 8) break;
            nullFields.addAll(nullCandidates);
        }
        if (collect && sample != null)
            for (String nf : nullFields) {
                if (forcedNulls.contains(nf) || holds("null:" + nf)) continue;
                // only a field set for life (final, or never written by the tick) is wrong now for good; a transient
                // one (navigation.path while the sample walks) is null at rest
                int dot = nf.lastIndexOf('.');
                Object holder = dot < 0 ? sample : resolve(nf.substring(0, dot));
                if (holder == null || !effectivelyFinal(holder.getClass(), dot < 0 ? nf : nf.substring(dot + 1))) continue;
                // the sample holds an object there: a wrong assumption, a reason to remove like any other
                String r = "assumes " + nf + " null, the sample holds one";
                reasons.merge(r, 1, Integer::sum);
                Set<String> g = nullGuards.get(nf);
                reasonGuards.put(r, g == null ? new HashSet<>() : new HashSet<>(g));
            }
        roundNs += System.nanoTime() - t0;
        return reasons.size() + (reject != null && reasons.isEmpty() ? 1 : 0);
    }

    private Plan runEntity(Class<?> ec) throws AnalyzerException {
        Set<String> chainSet = new HashSet<>();
        for (Class<?> c = ec; c != null && c != Object.class; c = c.getSuperclass()) chainSet.add(internal(c));
        this.chain = chainSet;
        this.beCls = ec;
        Field tickCount = field(ec, "tickCount");
        if (tickCount == null) return new Plan(false, "no tickCount", List.of(), List.of());
        clocks.put(fkey("net/minecraft/world/entity/Entity", "tickCount"), tickCount);
        String decl = ownerOf(internal(ec), "tick", "()V");
        MethodNode root = decl == null ? null : method(decl, "tick", "()V");
        if (root == null) return new Plan(false, reject != null ? reject : "tick() not found", List.of(), List.of());
        Set<String> seen = new HashSet<>();
        rounds(decl, root, ec, tickCount, chainSet, seen);
        ok();
        boolean clockReasons = false;
        for (String r : reasons.keySet()) clockReasons |= r.startsWith("clock ");
        if (sample != null && !reasons.isEmpty()) {
            assumptionSearch(decl, root, ec, tickCount, chainSet, seen);
        } else if (!reasons.isEmpty() && zeroFields.isEmpty() && clockReasons) {
            // try each clock compared with 0 as "0 while asleep": keep it when it removes reasons
            List<String> cands = new ArrayList<>();
            for (Cond c : conds)
                if (!c.peer() && c.thresholdField() == null && c.thresholdConst() == 0 && c.mod() == 0 && !c.clock().getName().equals("tickCount")
                        && !cands.contains(c.clock().getName())) cands.add(c.clock().getName());
            int best = reasons.size();
            for (String cand : cands) {
                if (cands.indexOf(cand) >= 8) break;
                zeroFields.add(cand);
                int r = rounds(decl, root, ec, tickCount, chainSet, seen);
                if (r < best) best = r;
                else zeroFields.remove(cand);
            }
            rounds(decl, root, ec, tickCount, chainSet, seen);
            ok();
        }
        if (sample != null && reasons.isEmpty() && reject == null && MOB_RUNTIME) return mobPlan(ec);
        if (sample != null && reasons.isEmpty() && (!falseCalls.isEmpty() || !forcedNulls.isEmpty() || !draws.isEmpty()))
            reasons.put("assumptions and " + draws.size() + " draw conditions not in the runtime yet"
                    + (capturedContexts.isEmpty() ? "" : ", whole state of " + capturedContexts) + ": " + draws.stream().map(DrawCond::text).toList(), 1);
        if (!contexts.isEmpty() && reasons.isEmpty() && reject == null)
            reasons.put("AI objects followed (" + contexts.size() + " contexts), not in the runtime snapshot yet", 1);
        if (!reasons.isEmpty()) reject = reasons.size() + " reasons (null: " + nullFields + (zeroFields.isEmpty() ? "" : ", zero: " + zeroFields)
                + (falseCalls.isEmpty() ? "" : ", false: " + falseCalls) + (emptyFields.isEmpty() ? "" : ", empty: " + emptyFields)
                + (le0Fields.isEmpty() ? "" : ", <=0: " + le0Fields) + (ge0Fields.isEmpty() ? "" : ", >=0: " + ge0Fields) + "): " + String.join(" | ", reasons.keySet());
        if (reject != null) return new Plan(false, reject, List.copyOf(clocks.values()), List.copyOf(conds));
        long period = 1;
        for (long n : moduli) {
            period = lcm(period, n);
            if (period > 1_000_000) return new Plan(false, "period too long", List.copyOf(clocks.values()), List.copyOf(conds));
        }
        List<Field> nulls = new ArrayList<>();
        for (String f : nullFields) {
            Field fld = field(ec, f);
            if (fld == null) return new Plan(false, "field not accessible: " + f, List.of(), List.of());
            nulls.add(fld);
        }
        List<Field> zeros = new ArrayList<>();
        for (String f : zeroFields) {
            Field fld = field(ec, f);
            if (fld == null) return new Plan(false, "field not accessible: " + f, List.of(), List.of());
            zeros.add(fld);
        }
        return new Plan(true, "ok: period " + period + (nulls.isEmpty() ? "" : ", null: " + nullFields) + (zeros.isEmpty() ? "" : ", zero: " + zeroFields),
                List.copyOf(clocks.values()), List.copyOf(conds), List.copyOf(watch.values()), period, List.copyOf(nulls), List.copyOf(zeros));
    }

    static final boolean MOB_RUNTIME = Boolean.getBoolean("lab.sleep.mobRuntime");

    private Plan mobPlan(Class<?> ec) {
        long period = 1;
        List<long[]> inline = new ArrayList<>();
        List<String> still = new ArrayList<>();
        List<Long> pm = new ArrayList<>();
        for (String k : modKinds) {
            String[] t = k.split(" ");
            if (t[0].equals("tick")) inline.add(new long[] {Long.parseLong(t[1]), Long.parseLong(t[2])});
            else if (t[0].equals("clock")) still.add(t[1]);
            else pm.add(Long.parseLong(t[1]));
        }
        for (long n : pm) {
            period = lcm(period, n);
            if (period > 1_000_000) return new Plan(false, "period too long", List.copyOf(clocks.values()), List.copyOf(conds));
        }
        List<String> clockPaths = new ArrayList<>();
        for (String k : clocks.keySet()) clockPaths.add(k.contains("|") ? k.substring(0, k.indexOf('|')) : "");
        for (Field f : clocks.values()) if (f == null) return new Plan(false, "clock not accessible", List.of(), List.of());
        MobPlan mob = new MobPlan(List.copyOf(contexts), List.copyOf(clockPaths), List.copyOf(condPaths), Set.copyOf(nullFields), Set.copyOf(zeroFields),
                Set.copyOf(le0Fields), Set.copyOf(ge0Fields), Set.copyOf(falseCalls), Set.copyOf(emptyFields), List.copyOf(draws), Set.copyOf(capturedContexts),
                List.copyOf(inline), List.copyOf(still));
        String text = "ok (mob): period " + period + ", contexts " + contexts.size() + ", null " + nullFields + ", zero " + zeroFields + ", <=0 " + le0Fields
                + ", >=0 " + ge0Fields + ", false " + falseCalls + ", empty " + emptyFields + ", draws " + draws.size() + ", whole " + capturedContexts
                + ", inline ticks " + inline.stream().map(x -> x[0] + ":" + x[1]).toList() + ", still clocks " + still;
        return new Plan(true, text, List.copyOf(clocks.values()), List.copyOf(conds), List.copyOf(watch.values()), period, List.of(), List.of(), List.copyOf(draws), mob);
    }

    private static long lcm(long a, long b) {
        long x = a, y = b;
        while (y != 0) {
            long t = x % y;
            x = y;
            y = t;
        }
        return a / x * b;
    }

    /** Owned objects whose whole state the entity snapshot or the engine's runtime checks see. */
    private static final Set<String> ENTITY_SAFE = Set.of("java/lang/Class", "net/minecraft/core/Vec3i", "net/minecraft/network/syncher/EntityDataAccessor", "net/minecraft/world/entity/ai/targeting/TargetingConditions","net/minecraft/network/syncher/SynchedEntityData", "net/minecraft/util/RandomSource",
            "net/minecraft/world/level/levelgen/LegacyRandomSource", "net/minecraft/world/level/levelgen/SingleThreadedRandomSource",
            "net/minecraft/world/phys/Vec3", "net/minecraft/world/phys/AABB", "java/util/Optional", "java/util/stream/Stream",
            "net/minecraft/world/entity/EntityDimensions", "net/minecraft/core/Rotations", "net/minecraft/world/damagesource/DamageSources",
            "net/minecraft/network/chat/Component", "net/minecraft/world/item/alchemy/PotionContents",
            // section bookkeeping keyed by position: a no-op while the position does not change (the engine requires that)
            "net/minecraft/world/level/entity/EntityInLevelCallback");

    static Plan analyze(Class<? extends BlockEntity> beClass, Class<?> blockClass) {
        SleepAnalysis a = new SleepAnalysis(beClass.getClassLoader());
        try {
            return a.run(beClass, blockClass);
        } catch (Exception | LinkageError e) {
            return new Plan(false, "analysis failed: " + e, List.of(), List.of());
        }
    }

    private Plan run(Class<?> beClass, Class<?> blockClass) throws AnalyzerException {
        Set<String> beChain = new HashSet<>();
        for (Class<?> c = beClass; c != null && c != Object.class; c = c.getSuperclass()) beChain.add(internal(c));
        this.chain = beChain;
        this.beCls = beClass;
        // ticker candidates in the block's class chain and its interfaces (a default getTicker, as mods write it): lambdas
        // producing a BlockEntityTicker whose 4th parameter fits this BE, and ticker objects made with NEW (a class
        // implementing BlockEntityTicker: its tick(Level, BlockPos, BlockState, BE) is the ticker)
        List<String[]> tickers = new ArrayList<>();
        for (Class<?> c : blockTypes(blockClass)) {
            ClassNode cn = node(internal(c));
            if (cn == null) continue;
            if (changed(internal(c), "getTicker"))
                return new Plan(false, "mixin-changed " + c.getSimpleName() + ".getTicker", List.of(), List.of());
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in : m.instructions) {
                    String[] t = null;
                    if (in instanceof InvokeDynamicInsnNode indy && indy.desc.endsWith(TICKER_RET) && indy.bsmArgs.length >= 2
                            && indy.bsmArgs[1] instanceof Handle h) {
                        Type[] args = Type.getArgumentTypes(h.getDesc());
                        if (args.length == 4 && beChain.contains(args[3].getInternalName())) t = new String[] {h.getOwner(), h.getName(), h.getDesc()};
                    } else if (in.getOpcode() == Opcodes.NEW && in instanceof TypeInsnNode ti && m.desc.endsWith(TICKER_RET)) {
                        t = tickerObject(ti.desc, beChain);
                    }
                    if (t == null) continue;
                    if (changed(internal(c), m.name))
                        return new Plan(false, "mixin-changed " + c.getSimpleName() + "." + m.name, List.of(), List.of());
                    final String[] found = t;
                    if (tickers.stream().noneMatch(x -> Arrays.equals(x, found))) tickers.add(found);
                }
            }
        }
        if (tickers.isEmpty()) {   // a ticker made by a helper: followed from getTicker through the calls returning a ticker
            Plan refused = helperTickers(blockClass, beClass, beChain, tickers);
            if (refused != null) return refused;
        }
        if (tickers.isEmpty()) return new Plan(false, "no ticker found in " + blockClass.getSimpleName(), List.of(), List.of());
        Set<String> seen = new HashSet<>();
        for (String[] t : tickers) {
            MethodNode m = method(t[0], t[1], t[2]);
            if (m == null) return new Plan(false, "ticker method not found " + t[1], List.of(), List.of());
            follow(t[0], m, 0, seen, beChain, beClass, tickerSelf(t, m, beChain));
            if (reject != null) break;
        }
        // conditions are gathered in a second pass, once all clocks are known
        if (reject == null) {
            seen.clear();
            for (String[] t : tickers) {
                MethodNode tm = method(t[0], t[1], t[2]);
                branches(t[0], tm, 0, seen, beChain, beClass, tickerSelf(t, tm, beChain));
            }
        }
        if (reject != null) return new Plan(false, reject, List.copyOf(clocks.values()), List.copyOf(conds));
        for (Field c : clocks.values()) if (c != null) watch.putIfAbsent(c.getName(), c);
        return new Plan(true, "ok: " + tickers.size() + " ticker(s), watching " + watch.size() + " field(s)", List.copyOf(clocks.values()),
                List.copyOf(conds), List.copyOf(watch.values()));
    }

    /** the methods the sleep probes hook in vanilla's classes (Mojang names, then 1.20.1's SRG ones), by descriptor */
    private static final Map<String, String> PROBED = Map.ofEntries(
            Map.entry("setItem", "(ILnet/minecraft/world/item/ItemStack;)V"), Map.entry("m_6836_", "(ILnet/minecraft/world/item/ItemStack;)V"),
            Map.entry("removeItem", "(II)Lnet/minecraft/world/item/ItemStack;"), Map.entry("m_7407_", "(II)Lnet/minecraft/world/item/ItemStack;"),
            Map.entry("removeItemNoUpdate", "(I)Lnet/minecraft/world/item/ItemStack;"), Map.entry("m_8016_", "(I)Lnet/minecraft/world/item/ItemStack;"),
            Map.entry("stillValid", "(Lnet/minecraft/world/entity/player/Player;)Z"), Map.entry("m_6542_", "(Lnet/minecraft/world/entity/player/Player;)Z"),
            Map.entry("setChanged", "()V"), Map.entry("m_6596_", "()V"));

    /**
     * A sleeping block entity learns of changes to its contents from the probes on vanilla's container and block-entity
     * methods; a mod's class overriding one of them without calling super on every exit (PneumaticCraft's setChanged,
     * Farmer's Delight's basket's setItem) changes them unseen. The reason, or null when every probe sees its writes.
     * SleepEngine then verifies such a class by value (its item lists) instead of trusting the probes.
     */
    static String probeBypassOf(Class<?> beClass) {
        try {
            return new SleepAnalysis(beClass.getClassLoader()).probeBypass(beClass);
        } catch (RuntimeException | LinkageError e) {
            return "class not readable: " + e;
        }
    }

    private String probeBypass(Class<?> beClass) {
        for (Class<?> c = beClass; c != null && c != Object.class; c = c.getSuperclass()) {
            String name = internal(c);
            if (name.startsWith("net/minecraft/")) break;   // vanilla's own classes carry the probes
            ClassNode cn = node(name);
            if (cn == null) return "class not readable: " + c.getSimpleName();
            for (MethodNode m : cn.methods) {
                String desc = PROBED.get(m.name);
                if (desc == null || !desc.equals(m.desc) || (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_BRIDGE)) != 0) continue;
                if (!superOnEveryExit(m)) return "overrides " + m.name + " in " + c.getSimpleName() + " without super on every path (the probes would not see it)";
            }
        }
        return null;
    }

    /** every normal exit of m passes an INVOKESPECIAL of the same name and descriptor (super) */
    private static boolean superOnEveryExit(MethodNode m) {
        AbstractInsnNode[] ins = m.instructions.toArray();
        if (ins.length == 0) return false;
        boolean[] blocked = new boolean[ins.length], seen = new boolean[ins.length];
        boolean any = false;
        for (int i = 0; i < ins.length; i++)
            if (ins[i] instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals(m.name) && mi.desc.equals(m.desc)) {
                blocked[i] = true;
                any = true;
            }
        if (!any) return false;
        java.util.ArrayDeque<Integer> st = new java.util.ArrayDeque<>();
        st.push(0);
        while (!st.isEmpty()) {
            int i = st.pop();
            if (i < 0 || i >= ins.length || seen[i] || blocked[i]) continue;
            seen[i] = true;
            AbstractInsnNode in = ins[i];
            int op = in.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) return false;   // an exit without super
            if (op == Opcodes.ATHROW) continue;
            if (in instanceof JumpInsnNode j) {
                st.push(m.instructions.indexOf(j.label));
                if (op == Opcodes.GOTO) continue;
            } else if (in instanceof TableSwitchInsnNode t) {
                st.push(m.instructions.indexOf(t.dflt));
                for (LabelNode l : t.labels) st.push(m.instructions.indexOf(l));
                continue;
            } else if (in instanceof LookupSwitchInsnNode t) {
                st.push(m.instructions.indexOf(t.dflt));
                for (LabelNode l : t.labels) st.push(m.instructions.indexOf(l));
                continue;
            }
            st.push(i + 1);
        }
        return true;
    }

    /** The block class, its superclasses and every interface they implement (a mod's getTicker may be a default method). */
    private static List<Class<?>> blockTypes(Class<?> blockClass) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c = blockClass; c != null && c != Object.class; c = c.getSuperclass()) out.add(c);
        for (int i = 0; i < out.size(); i++)
            for (Class<?> f : out.get(i).getInterfaces()) if (!out.contains(f)) out.add(f);
        return out;
    }

    /** A ticker made as an object: the class (or a superclass) implements BlockEntityTicker; its tick taking this BE. */
    /**
     * Tickers a block makes through helpers (Immersive Engineering: getTicker -> its class data's makeBaseTicker ->
     * IEServerTickableBE.makeTicker, a lambda casting the block entity to the interface and calling tickServer): from
     * the block's getTicker, every call whose result is a BlockEntityTicker is followed into its method, three calls
     * deep, and each method reached is searched as the block's own are - a lambda over (Level, BlockPos, BlockState,
     * the block entity or a supertype) or a ticker object. A lambda that first casts the block entity to a type it does
     * not have (another block entity's ticker the same helper makes, a client ticker for a server-only one) can never
     * run for this block entity and is left out. A helper method changed by another mod's mixin refuses the class.
     */
    private Plan helperTickers(Class<?> blockClass, Class<?> beClass, Set<String> beChain, List<String[]> tickers) {
        java.util.ArrayDeque<String[]> todo = new java.util.ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (Class<?> c : blockTypes(blockClass)) {
            ClassNode cn = node(internal(c));
            if (cn == null) continue;
            for (MethodNode m : cn.methods)
                if (m.name.equals("getTicker") || m.desc.endsWith(TICKER_RET)) todo.add(new String[] {internal(c), m.name, m.desc, "0"});
        }
        while (!todo.isEmpty()) {
            String[] cur = todo.poll();
            if (!visited.add(cur[0] + "." + cur[1] + cur[2])) continue;
            int depth = Integer.parseInt(cur[3]);
            MethodNode m = method(cur[0], cur[1], cur[2]);
            if (m == null || m.instructions == null) continue;
            if (depth > 0 && changed(cur[0], cur[1])) return new Plan(false, "mixin-changed " + cur[0] + "." + cur[1], List.of(), List.of());
            for (AbstractInsnNode in : m.instructions) {
                String[] t = null;
                if (in instanceof InvokeDynamicInsnNode indy && indy.desc.endsWith(TICKER_RET) && indy.bsmArgs.length >= 2 && indy.bsmArgs[1] instanceof Handle h) {
                    Type[] args = Type.getArgumentTypes(h.getDesc());
                    if (args.length == 4 && beChain.contains(args[3].getInternalName()) && castFits(h, beClass)) t = new String[] {h.getOwner(), h.getName(), h.getDesc()};
                } else if (in.getOpcode() == Opcodes.NEW && in instanceof TypeInsnNode ti && m.desc.endsWith(TICKER_RET)) {
                    t = tickerObject(ti.desc, beChain);
                } else if (in instanceof MethodInsnNode mi && mi.desc.endsWith(TICKER_RET) && depth < 3) {
                    todo.add(new String[] {mi.owner, mi.name, mi.desc, String.valueOf(depth + 1)});
                }
                if (t == null) continue;
                final String[] found = t;
                if (tickers.stream().noneMatch(x -> Arrays.equals(x, found))) tickers.add(found);
            }
        }
        return null;
    }

    /** a ticker lambda whose body starts by casting its block entity (4th parameter) to a type this block entity has, or does not cast it */
    private boolean castFits(Handle h, Class<?> beClass) {
        MethodNode m = method(h.getOwner(), h.getName(), h.getDesc());
        if (m == null || m.instructions == null) return true;
        boolean inst = h.getTag() != Opcodes.H_INVOKESTATIC;
        Type[] a = Type.getArgumentTypes(h.getDesc());
        int slot = inst ? 1 : 0;
        for (int k = 0; k < 3; k++) slot += a[k].getSize();
        boolean loaded = false;
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() < 0) continue;   // labels, line numbers, frames
            if (!loaded) {
                if (in instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == slot) {
                    loaded = true;
                    continue;
                }
                return true;   // something else first: no cast to judge by
            }
            if (in.getOpcode() == Opcodes.CHECKCAST && in instanceof TypeInsnNode ti) {
                try {
                    Class<?> cast = Class.forName(ti.desc.replace('/', '.'), false, beClass.getClassLoader());
                    return cast.isAssignableFrom(beClass);
                } catch (ClassNotFoundException | LinkageError e) {
                    return true;
                }
            }
            return true;
        }
        return true;
    }

    private String[] tickerObject(String type, Set<String> beChain) {
        for (String c = type; c != null && !c.equals("java/lang/Object"); ) {
            ClassNode cn = node(c);
            if (cn == null) return null;
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("tick") || (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_BRIDGE)) != 0) continue;
                Type[] args = Type.getArgumentTypes(m.desc);
                if (args.length == 4 && args[0].getInternalName().equals("net/minecraft/world/level/Level") && beChain.contains(args[3].getInternalName()))
                    return new String[] {c, m.name, m.desc};
            }
            c = cn.superName;
        }
        return null;
    }

    /**
     * The ticker's locals holding the block entity: its 4th parameter (the ticker lambda's "be"), and "this" when the
     * ticker is a method of the block entity's own class chain. A call on them through any type is the block entity's own.
     */
    private static int tickerSelf(String[] t, MethodNode m, Set<String> beChain) {
        if (m == null) return 0;
        boolean inst = (m.access & Opcodes.ACC_STATIC) == 0;
        Type[] a = Type.getArgumentTypes(m.desc);
        int slot = inst ? 1 : 0;
        for (int k = 0; k < 3 && k < a.length; k++) slot += a[k].getSize();
        int mask = slot < 31 ? 1 << slot : 0;
        if (inst && beChain.contains(t[0])) mask |= 1;
        return mask;
    }

    /**
     * A call on the block entity itself through a type outside its class chain - a mod's ticker calling
     * ((ITickableBlockEntity) be).serverTick(...): what runs is decided by the block entity's class, so the call is
     * resolved there and followed as the block entity's own code (before, the analysis skipped it and never saw the tick).
     * Null when the call is not of this kind; a method that cannot be resolved refuses the class.
     */
    private String[] selfDispatch(MethodInsnNode mi, Frame<SourceValue>[] fr, int i, MethodNode m, Set<String> beChain, Class<?> beClass) {
        if (SleepEngine.observedModSleep() || entityMode || mi.getOpcode() == Opcodes.INVOKESTATIC || beChain.contains(mi.owner)
                || !onThis(fr[i], Type.getArgumentTypes(mi.desc).length + 1, fr, m)) return null;
        String decl = ownerOf(internal(beClass), mi.name, mi.desc);
        MethodNode target = decl == null ? null : method(decl, mi.name, mi.desc);
        if (target == null || (target.access & Opcodes.ACC_ABSTRACT) != 0) {
            if (reject == null) reject = "calls " + simple(mi.owner) + "." + mi.name + " on the block entity, not resolved in its class";
            return new String[0];
        }
        return new String[] {decl};
    }

    // ---------------------------------------------------------------- pass 1: clocks and hidden state

    private void follow(String owner, MethodNode m, int depth, Set<String> seen, Set<String> beChain, Class<?> beClass) throws AnalyzerException {
        follow(owner, m, depth, seen, beChain, beClass, (m.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0);
    }

    private void follow(String owner, MethodNode m, int depth, Set<String> seen, Set<String> beChain, Class<?> beClass, int self) throws AnalyzerException {
        if (!ok() || !seen.add(owner + "." + m.name + m.desc + (entityMode ? "#" + self + "@" + ctxPath : ""))) return;
        int savedSelf = selfMask;
        selfMask = self;
        Set<String> savedChain = chainGuards, savedCur = curGuards;
        chainGuards = curGuards;
        if (TRACE) STACK.addLast(simple(owner) + "." + m.name);
        try {
            follow0(owner, m, depth, seen, beChain, beClass);
        } finally {
            if (TRACE) STACK.removeLast();
            selfMask = savedSelf;
            chainGuards = savedChain;
            curGuards = savedCur;
        }
    }

    private void follow0(String owner, MethodNode m, int depth, Set<String> seen, Set<String> beChain, Class<?> beClass) throws AnalyzerException {
        if (changed(owner, m.name)) {   // the resource bytes are not the code that runs
            reject = "mixin-changed " + simple(owner) + "." + m.name;
            return;
        }
        Flow flow = flow(owner, m);
        Frame<SourceValue>[] fr = flow.fr();
        String here = simple(owner) + "." + m.name;
        AbstractInsnNode[] in = m.instructions.toArray();
        Set<String>[] lg = guards(owner, m, flow, chainGuards);
        Set<String> base = chainGuards;
        for (int i = 0; i < in.length && ok(here); i++) {
            if (fr[i] == null || !flow.live()[i]) continue;
            curGuards = lg == null || lg[i] == null ? base : lg[i];
            int op = in[i].getOpcode();
            if (in[i] instanceof InvokeDynamicInsnNode indy) {
                lambda(indy, fr, i, m, depth, seen, beChain, beClass, true);
                continue;
            }
            if ((op == Opcodes.PUTFIELD || op == Opcodes.GETFIELD) && in[i] instanceof FieldInsnNode wf && beChain.contains(wf.owner)
                    && !BASE_FIELDS.contains(wf.name) && (op == Opcodes.PUTFIELD || containerType(wf.desc))) {
                Field fld = field(beClass, wf.name);
                if (fld == null) { reject = "field not accessible: " + wf.name; break; }
                watch.putIfAbsent(wf.name, fld);
            }
            if (entityMode && op == Opcodes.PUTFIELD && in[i] instanceof FieldInsnNode pf && !onThis(fr[i], 2, fr, m) && !entityClass(pf.owner)
                    && !fresh(fr[i], 2, fr, m, 0) && !localScratch(pf.owner, owner, m)) {
                reject = "writes " + simple(pf.owner) + "." + pf.name + " of a foreign object";
                break;
            }
            if (collect && in[i] instanceof MethodInsnNode cm && COLLECTION_MUTATORS.contains(cm.name) && cm.getOpcode() != Opcodes.INVOKESTATIC) {
                // a collection of this object changed in place: its elements are state, not contexts
                Operand r = operand(fr[i], Type.getArgumentTypes(cm.desc).length + 1, fr, m, 0);
                if (r.kind == Kind.FIELD && r.self) writtenNow.add(declOf(r.owner, r.name) + "." + r.name);
            }
            if (collect && op == Opcodes.PUTFIELD && in[i] instanceof FieldInsnNode wf0 && onThis(fr[i], 2, fr, m)) {   // another instance's field is not ours
                String o = declOf(wf0.owner, wf0.name);
                if (writtenNow.add(o + "." + wf0.name) && wf0.name.equals(WHO_WRITES)) System.out.println("[sleep/sleep] " + o + "." + wf0.name + " written @" + simple(owner) + "." + m.name + " ctx=" + ctxPath);
            }
            if (op == Opcodes.PUTFIELD && in[i] instanceof FieldInsnNode f && beChain.contains(f.owner) && (!entityMode || onThis(fr[i], 2, fr, m))) {
                String kind = clockKind(f, fr, m, i);
                if (kind != null) clocks.putIfAbsent(fkey(f.owner, f.name), field(beClass, f.name));
                if ("timestamp".equals(kind)) stamps.add(f.name);
            } else if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) {
                Operand arr = operand(fr[i], 3, fr, m, 0);
                if (arr.kind == Kind.FIELD && beChain.contains(arr.owner) && !BASE_FIELDS.contains(arr.name)) {
                    Field af = field(beClass, arr.name);   // snapshotted by content (FieldDiff), so the write is visible
                    if (af == null) reject = "field not accessible: " + arr.name;
                    else watch.putIfAbsent(arr.name, af);
                } else if (arr.kind == Kind.OWNCALL) {
                    reject = "writes an array owned by the block entity that the snapshot cannot see (" + arr.name + ")";
                }
            } else if (in[i] instanceof MethodInsnNode mi) {
                // game time handed to a call: the comparison would happen where the analysis cannot see it
                int argc = Type.getArgumentTypes(mi.desc).length;
                for (int k = 1; k <= argc && reject == null; k++)
                    if (containsGameTime(operand(fr[i], k, fr, m, 0))) reject = "game time passed to " + simple(mi.owner) + "." + mi.name;
                if (reject != null) break;
                if (crossContext(fr, i, mi, m, depth, seen, true)) continue;
                String[] own = selfDispatch(mi, fr, i, m, beChain, beClass);
                if (own != null) {
                    if (own.length == 0) break;
                    if (depth >= MAX_DEPTH) {
                        reject = "own call graph deeper than " + MAX_DEPTH + " (" + mi.name + ")";
                        break;
                    }
                    follow(own[0], method(own[0], mi.name, mi.desc), depth + 1, seen, beChain, beClass, calleeMask(fr[i], mi, fr, m));
                    continue;
                }
                if (beChain.contains(mi.owner) && depth >= MAX_DEPTH) {
                    reject = "own call graph deeper than " + MAX_DEPTH + " (" + mi.name + ")";
                    break;
                }
                if (depth < MAX_DEPTH && beChain.contains(mi.owner)) {
                    String decl = mi.getOpcode() == Opcodes.INVOKESTATIC || mi.getOpcode() == Opcodes.INVOKESPECIAL ? ownerOf(mi.owner, mi.name, mi.desc) : ownerOf(internal(beClass), mi.name, mi.desc);
                    MethodNode target = decl == null ? null : method(decl, mi.name, mi.desc);
                    if (target != null && (target.access & Opcodes.ACC_ABSTRACT) == 0) follow(decl, target, depth + 1, seen, beChain, beClass, calleeMask(fr[i], mi, fr, m));
                    else if (reject == null) reject = "unresolved own method " + mi.name;
                    continue;
                }
                // a call on (or with) an object owned by the block entity may change hidden state
                // instance calls: only the receiver matters (arguments like a RecipeType are registry objects);
                // static calls: any owned argument could be mutated by the helper
                // receiver and every argument; registry objects (the RecipeType of a furnace) are shared constants, not state
                int nargs = Type.getArgumentTypes(mi.desc).length + (op == Opcodes.INVOKESTATIC ? 0 : 1);
                if (entityMode && ENTITY_SINKS.contains(mi.owner + "." + mi.name)) continue;
                if (entityMode && op == Opcodes.INVOKESTATIC && emptyMethod(mi)) continue;
                for (int k = 0; k < nargs; k++) {
                    Operand o = operand(fr[i], nargs - k, fr, m, 0);
                    if (nullField(o)) {   // null here: nothing to call or to change
                        if (collect) nullGuard(ctxPath.isEmpty() ? o.name : ctxPath + "." + o.name);
                        continue;
                    }
                    if (entityMode && o.kind == Kind.FIELD && "profiler".equals(o.name) && "java/util/function/Supplier".equals(o.type)) continue;   // GoalSelector's profiler supplier
                    if (isOwnField(o, beChain) && !safeOwned(o.type) && !registryType(o.type) && !pureReader(mi)
                            && !(entityMode && o.type != null && ENTITY_SAFE.contains(o.type))) {
                        if (entityMode && o.kind == Kind.FIELD && o.type != null && o.type.startsWith("[") && mi.owner.equals("java/util/Arrays")) {
                            Field af = field(beClass, o.name);   // an own array changed in place: snapshotted by content
                            if (af != null) { watch.putIfAbsent(o.name, af); continue; }
                        }
                        if (entityMode && !ctxPath.isEmpty() && o.kind == Kind.FIELD && o.self && o.type != null && o.type.length() > 1 && !finalField(ctxObj, o.name)) {
                            String key = ctxPath + "." + o.name;   // a context's own object, assumed null like the entity's
                            nullGuard(key);
                            if (nullCandidates.add(key) && NULL_WHY) System.out.println("[sleep/sleep] null candidate " + key + " @" + simple(owner) + "." + m.name + " call " + simple(mi.owner) + "." + mi.name);
                            continue;
                        }
                        if (entityMode && !ctxPath.isEmpty()) {
                            reject = "calls " + simple(mi.owner) + "." + mi.name + " on " + ctxPath + "'s own " + o.name;
                            break;
                        }
                        if (entityMode && o.kind == Kind.FIELD && o.type != null && o.type.length() > 1) {
                            nullGuard(o.name);
                            if (nullCandidates.add(o.name) && NULL_WHY) System.out.println("[sleep/sleep] null candidate " + o.name + " @" + simple(owner) + "." + m.name + " ctx=" + ctxPath + " call " + simple(mi.owner) + "." + mi.name + " guards " + curGuards + " stack " + STACK);
                            continue;
                        }
                        reject = "calls " + simple(mi.owner) + "." + mi.name + (k == 0 && op != Opcodes.INVOKESTATIC ? " on" : " with") + " owned state " + o.name;
                        break;
                    }
                }
                if (true) continue;
                for (int k = 0; k < nargs; k++) {
                    Operand o = operand(fr[i], nargs - k, fr, m, 0);
                    if (isOwnField(o, beChain) && !safeOwned(o.type)) {
                        reject = "calls " + simple(mi.owner) + "." + mi.name + " on owned state " + o.name;
                        break;
                    }
                }
            }
        }
    }

    /** Counter: field = field +- const; timestamp: field = level.getGameTime(). */
    private String clockKind(FieldInsnNode f, Frame<SourceValue>[] fr, MethodNode m, int i) {
        if (entityMode && !onThis(fr[i], 2, fr, m)) return null;
        Operand v = operand(fr[i], 1, fr, m, 0);
        if (v.kind == Kind.GAMETIME) return "timestamp";
        if (v.kind == Kind.ARITH && v.a != null && v.b != null && (v.op == Opcodes.IADD || v.op == Opcodes.ISUB || v.op == Opcodes.LADD || v.op == Opcodes.LSUB)) {
            boolean self = (v.a.kind == Kind.FIELD && v.a.name.equals(f.name) && (!entityMode || v.a.self) && v.b.kind == Kind.CONST)
                    || (v.b.kind == Kind.FIELD && v.b.name.equals(f.name) && (!entityMode || v.b.self) && v.a.kind == Kind.CONST);
            if (self) return "counter";
        }
        return null;
    }

    // ---------------------------------------------------------------- pass 2: conditions on clocks

    private void branches(String owner, MethodNode m, int depth, Set<String> seen, Set<String> beChain, Class<?> beClass) throws AnalyzerException {
        if (m == null) return;
        branches(owner, m, depth, seen, beChain, beClass, (m.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0);
    }

    private void branches(String owner, MethodNode m, int depth, Set<String> seen, Set<String> beChain, Class<?> beClass, int self) throws AnalyzerException {
        if (!ok() || m == null || !seen.add(owner + "." + m.name + m.desc + (entityMode ? "#" + self + "@" + ctxPath : ""))) return;
        int savedSelf = selfMask;
        selfMask = self;
        Set<String> savedChain = chainGuards, savedCur = curGuards;
        chainGuards = curGuards;
        try {
            branches0(owner, m, depth, seen, beChain, beClass);
        } finally {
            selfMask = savedSelf;
            chainGuards = savedChain;
            curGuards = savedCur;
        }
    }

    private void branches0(String owner, MethodNode m, int depth, Set<String> seen, Set<String> beChain, Class<?> beClass) throws AnalyzerException {
        Flow flow = flow(owner, m);
        Frame<SourceValue>[] fr = flow.fr();
        String here = simple(owner) + "." + m.name;
        AbstractInsnNode[] in = m.instructions.toArray();
        Set<String>[] lg = guards(owner, m, flow, chainGuards);
        Set<String> base = chainGuards;
        for (int i = 0; i < in.length && ok(here); i++) {
            if (fr[i] == null || !flow.live()[i]) continue;
            curGuards = lg == null || lg[i] == null ? base : lg[i];
            curHere = (ctxPath.isEmpty() ? "" : ctxPath + ": ") + here;
            if (in[i] instanceof InvokeDynamicInsnNode indy) {
                lambda(indy, fr, i, m, depth, seen, beChain, beClass, false);
            } else if (in[i] instanceof JumpInsnNode j && j.getOpcode() != Opcodes.GOTO && j.getOpcode() != Opcodes.JSR) {
                int op = j.getOpcode();
                boolean two = op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ACMPNE;
                Operand x, y;
                if (two) {
                    x = operand(fr[i], 2, fr, m, 0);
                    y = operand(fr[i], 1, fr, m, 0);
                } else {
                    Operand v = operand(fr[i], 1, fr, m, 0);
                    if (v.kind == Kind.CMP && (op == Opcodes.IFEQ || op == Opcodes.IFNE) && staleStamp(v.a, v.b)) {
                        continue;   // a game-time stamp tested for "this tick": false once a quiet tick has passed
                    } else if (v.kind == Kind.CMP) {
                        x = v.a;
                        y = v.b;
                    } else {
                        x = v;
                        y = Operand.constant(0);
                    }
                    if (x != null && y != null && (x.kind == Kind.DRAW || y.kind == Kind.DRAW)) {
                        drawCond(x, y, op, v.kind == Kind.CMP ? v.op : 0, owner, m);
                        continue;
                    }
                }
                if (x != null && y != null && (x.kind == Kind.DRAW || y.kind == Kind.DRAW)) {
                    drawCond(x, y, op, 0, owner, m);
                    continue;
                }
                if (deadCondition(in, i, m, flow)) continue;   // both paths meet before anything happens (the client: a server-only branch)
                condition(x, y, beChain, beClass);
            } else if (in[i] instanceof MethodInsnNode mb && mb.getOpcode() == Opcodes.INVOKESTATIC && PURE_BOUNDS.contains(mb.owner + "." + mb.name)
                    && clockArgument(fr, i, mb, m)) {
                boundConditions(fr, i, mb, m, beClass);
            } else if (in[i] instanceof MethodInsnNode mi0 && !(entityMode && CLOCK_SINKS.contains(mi0.owner + "." + mi0.name)) && clockArgument(fr, i, mi0, m)
                    && !(collect && monotoneOfClock(fr, i, mi0, m))) {
                reject = "clock passed to " + simple(mi0.owner) + "." + mi0.name;
            } else if (in[i] instanceof MethodInsnNode mc && depth < MAX_DEPTH && crossContext(fr, i, mc, m, depth, seen, false)) {
                continue;
            } else if (in[i] instanceof MethodInsnNode mi && depth < MAX_DEPTH && beChain.contains(mi.owner)) {
                String decl = mi.getOpcode() == Opcodes.INVOKESTATIC || mi.getOpcode() == Opcodes.INVOKESPECIAL ? ownerOf(mi.owner, mi.name, mi.desc) : ownerOf(internal(beClass), mi.name, mi.desc);
                MethodNode target = decl == null ? null : method(decl, mi.name, mi.desc);
                if (target != null && (target.access & Opcodes.ACC_ABSTRACT) == 0) branches(decl, target, depth + 1, seen, beChain, beClass, calleeMask(fr[i], mi, fr, m));
            } else if (in[i] instanceof MethodInsnNode md && depth < MAX_DEPTH) {
                String[] own = selfDispatch(md, fr, i, m, beChain, beClass);
                if (own != null && own.length == 1) branches(own[0], method(own[0], md.name, md.desc), depth + 1, seen, beChain, beClass, calleeMask(fr[i], md, fr, m));
            }
        }
    }

    /**
     * invokedynamic lambdas and method references: an implementation in the own class chain is followed like an own
     * call (whoever receives the function object may call it); owned state captured into it is checked like an argument.
     */
    private void lambda(InvokeDynamicInsnNode indy, Frame<SourceValue>[] fr, int i, MethodNode m, int depth, Set<String> seen, Set<String> beChain,
            Class<?> beClass, boolean pass1) throws AnalyzerException {
        if (!indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
            String b = indy.bsm.getOwner();
            if (pass1 && !b.equals("java/lang/invoke/StringConcatFactory") && !b.equals("java/lang/runtime/SwitchBootstraps") && !b.equals("java/lang/runtime/ObjectMethods"))
                reject = "invokedynamic " + b;
            return;
        }
        if (indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle h)) { reject = "lambda without implementation handle"; return; }
        if (pass1) {
            int argc = Type.getArgumentTypes(indy.desc).length;
            for (int k = 1; k <= argc; k++) {
                Operand o = operand(fr[i], k, fr, m, 0);
                if (nullField(o)) continue;
                if (isOwnField(o, beChain) && !safeOwned(o.type) && !registryType(o.type) && !(entityMode && o.type != null && ENTITY_SAFE.contains(o.type))) {
                    if (collect && o.kind == Kind.FIELD && o.self && ctxObj != null) {
                        // an object the entity owns for life (the AttributeMap): its whole state goes into the runtime
                        // snapshot (step B: an epoch of its changes), so what the lambda does to it is seen
                        Object v = fieldValue(ctxObj, o.name);
                        if ((aiObject(v) || ctorOwned(ctxObj.getClass(), o.name, v)) && effectivelyFinal(ctxObj.getClass(), o.name)) {
                            capturedContexts.add(pathKey(o.name));
                            continue;
                        }
                    }
                    reject = "captures owned state " + o.name + " in a lambda";
                    return;
                }
            }
        }
        if (!beChain.contains(h.getOwner())) return;   // a foreign implementation: like calling it with the captured values
        String decl = h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKEINTERFACE ? ownerOf(internal(beClass), h.getName(), h.getDesc())
                : ownerOf(h.getOwner(), h.getName(), h.getDesc());
        MethodNode target = decl == null ? null : method(decl, h.getName(), h.getDesc());
        if (target == null || (target.access & Opcodes.ACC_ABSTRACT) != 0) {
            if (reject == null) reject = "unresolved lambda " + h.getName();
            return;
        }
        if (depth >= MAX_DEPTH) { reject = "own call graph deeper than " + MAX_DEPTH + " (" + h.getName() + ")"; return; }
        int mask = 0, slot = 0;
        Type[] cap = Type.getArgumentTypes(indy.desc);
        for (int k = 0; k < cap.length; k++) {
            if (onThis(fr[i], cap.length - k, fr, m) && slot < 31) mask |= 1 << slot;
            slot += cap[k].getSize();
        }
        if (pass1) follow(decl, target, depth + 1, seen, beChain, beClass, mask);
        else branches(decl, target, depth + 1, seen, beChain, beClass, mask);
    }

    /** a == b with one side a timestamp clock (stores getGameTime) and the other the game time */
    private boolean staleStamp(Operand a, Operand b) {
        if (!entityMode || a == null || b == null) return false;
        return isClock(a) && b.kind == Kind.GAMETIME && stamps.contains(a.name) || isClock(b) && a.kind == Kind.GAMETIME && stamps.contains(b.name);
    }

    private final Set<String> stamps = new HashSet<>();

    /** (gameTime - stamp) ? C with stamp an own long field a tick only sets to the game time (or never writes) */
    private boolean ageCond(Operand x, Operand y, Class<?> beClass) {
        Operand e = x != null && x.kind == Kind.ARITH ? x : y, c = e == x ? y : x;
        if (e == null || c == null || c.kind != Kind.CONST || e.kind != Kind.ARITH || e.op != Opcodes.LSUB || e.a == null || e.b == null) return false;
        if (e.a.kind != Kind.GAMETIME || e.b.kind != Kind.FIELD || !e.b.self || !"J".equals(e.b.type) || !ctxPath.isEmpty() || !chain.contains(e.b.owner)) return false;
        String k = fkey(e.b.owner, e.b.name);
        if (clocks.containsKey(k) && !stamps.contains(e.b.name)) return false;   // a counter, not a stamp
        Field f = field(beClass, e.b.name);
        if (f == null) return false;
        addCond(Cond.age(f, c.value, "gameTime - " + e.b.name + " ? " + c.value));
        return true;
    }

    /** calls that only store or print a value (no comparison, no owned state changed) */
    private static final Set<String> CLOCK_SINKS = Set.of("net/minecraft/nbt/CompoundTag.putInt", "net/minecraft/nbt/CompoundTag.putShort",
            "net/minecraft/nbt/CompoundTag.putLong", "net/minecraft/nbt/CompoundTag.putByte");
    private static final Set<String> ENTITY_SINKS = Set.of("java/util/Objects.requireNonNullElse", "com/google/common/base/MoreObjects.firstNonNull",
            "net/minecraft/network/chat/Component.getString", "net/minecraft/nbt/CompoundTag.copy",
            // the profiler (GoalSelector's Supplier<ProfilerFiller>): debug sections, no game state
            "net/minecraft/util/profiling/ProfilerFiller.push", "net/minecraft/util/profiling/ProfilerFiller.pop",
            "net/minecraft/util/profiling/ProfilerFiller.popPush", "net/minecraft/util/profiling/ProfilerFiller.incrementCounter");

    /** Pure bound functions: they only compare their arguments. */
    private static final Set<String> PURE_BOUNDS = Set.of("net/minecraft/util/Mth.clamp", "java/lang/Math.max", "java/lang/Math.min");

    /**
     * clamp/max/min(clock + c, bounds...): the function compares clock + c with each bound b, i.e. clock with b - c.
     * Bounds must be constants or the block entity's own fields; anything else is refused.
     */
    private void boundConditions(Frame<SourceValue>[] fr, int i, MethodInsnNode mi, MethodNode m, Class<?> beClass) {
        int argc = Type.getArgumentTypes(mi.desc).length;
        Operand[] a = new Operand[argc];
        for (int k = 0; k < argc; k++) a[k] = operand(fr[i], argc - k, fr, m, 0);
        for (int k = 0; k < argc && reject == null; k++) {
            if (!containsClock(a[k])) continue;
            Operand e = a[k], clockOp;
            long c;
            if (isClock(e)) {
                clockOp = e;
                c = 0;
            } else if (e.kind == Kind.ARITH && (e.op == Opcodes.IADD || e.op == Opcodes.LADD) && isClock(e.a) && e.b != null && e.b.kind == Kind.CONST) {
                clockOp = e.a;
                c = e.b.value;
            } else if (e.kind == Kind.ARITH && (e.op == Opcodes.ISUB || e.op == Opcodes.LSUB) && isClock(e.a) && e.b != null && e.b.kind == Kind.CONST) {
                clockOp = e.a;
                c = -e.b.value;
            } else {
                if (affineBand(a, k, mi)) return;
                reject = "clock expression passed to " + simple(mi.owner) + "." + mi.name;
                return;
            }
            Field clock = clocks.get(keyOf(clockOp));
            if (clock == null) { reject = "clock not accessible: " + clockOp.name; return; }
            for (int b = 0; b < argc; b++) {
                if (b == k) continue;
                Operand o = a[b];
                if (containsClock(o)) { reject = "two clocks in " + simple(mi.owner) + "." + mi.name; return; }
                if (peerClock(o) && o.name.equals(clockOp.name) && c == 0) {
                    addCond(Cond.peer(clock, clockOp.name + " ? other." + o.name + " (" + mi.name + ")"));
                    continue;
                }
                if (entityMode && o.kind == Kind.FIELD && !o.self) { reject = "clock bounded by another object's " + o.name + " in " + mi.name; return; }
                if (o.kind == Kind.CONST) {
                    addCond(new Cond(clock, null, o.value - c, 0, clockOp.name + " ? " + (o.value - c) + " (" + mi.name + ")"));
                } else if (o.kind == Kind.FIELD && chain.contains(o.owner) && !BASE_FIELDS.contains(o.name)) {
                    Field tf = field(beClass, o.name);
                    if (tf == null) { reject = "field not accessible: " + o.name; return; }
                    addCond(new Cond(clock, tf, -c, 0, clockOp.name + " ? " + o.name + (c == 0 ? "" : " - " + c) + " (" + mi.name + ")"));
                } else {
                    reject = "clock bounded by an unknown value in " + simple(mi.owner) + "." + mi.name;
                    return;
                }
            }
        }
    }

    /**
     * clamp(f(clock), lo, hi) / max(f(clock), b) / min(f(clock), b) with f built from the clock, constants and arithmetic
     * (BodyRotationControl: clamp((headStableTime - 10) / 10f, 0, 1)): f is evaluated exactly (Java's int / long /
     * float / double arithmetic) and is monotone; the result varies only for clock values where f is strictly inside the
     * bounds - that range becomes a band condition. False if the expression or the bounds are not of this form.
     */
    private boolean affineBand(Operand[] a, int k, MethodInsnNode mi) {
        Operand e = a[k];
        Operand clockOp = singleClock(e);
        if (clockOp == null) return false;
        double lo = Double.NEGATIVE_INFINITY, hi = Double.POSITIVE_INFINITY;
        String n = mi.name;
        for (int b = 0; b < a.length; b++) {
            if (b == k) continue;
            Double v = a[b].kind == Kind.CONST ? (double) a[b].value : a[b].kind == Kind.FCONST ? a[b].dvalue : null;
            if (v == null) return false;
            if (n.equals("clamp")) {
                if (b == (k == 0 ? 1 : 0) || a.length == 3 && b == 1) lo = Math.max(lo, v);
                else hi = Math.min(hi, v);
            } else if (n.equals("max")) {
                lo = v;
            } else if (n.equals("min")) {
                hi = v;
            } else {
                return false;
            }
        }
        if (n.equals("clamp") && !(a.length == 3 && k == 0)) return false;
        Double f0 = eval(e, clockOp, 0), f1 = eval(e, clockOp, 1);
        if (f0 == null || f1 == null) return false;
        Field clock = clocks.get(keyOf(clockOp));
        if (clock == null) return false;
        if (f0.doubleValue() == f1.doubleValue()) return true;   // constant in the clock
        boolean up = f1 > f0;
        // the clock values where f is strictly inside (lo, hi): [first, last]
        long first = up ? firstAbove(e, clockOp, lo, true) : firstAbove(e, clockOp, hi, false);
        long last = up ? firstAbove(e, clockOp, hi, false) - 1 : firstAbove(e, clockOp, lo, true) - 1;
        if (last < first) return true;   // never inside: constant
        long bl = first == Long.MIN_VALUE ? Long.MIN_VALUE : first - 2, bh = last == Long.MAX_VALUE - 1 ? Long.MAX_VALUE : last + 2;   // a margin
        addCond(Cond.band(clock, bl, bh, clockOp.name + " outside (" + bl + ", " + bh + ") (" + n + ")"));
        return true;
    }

    /**
     * A comparison of an expression of one clock (conversions, constants, arithmetic: (float) invulnerableTime > 10f)
     * with a constant: its outcome changes where the monotone expression crosses the constant - a threshold condition.
     */
    private boolean affineThreshold(Operand e, Operand other) {
        if (other == null || other.kind != Kind.CONST && other.kind != Kind.FCONST) return false;
        Operand clockOp = singleClock(e);
        if (clockOp == null || isClock(e) && other.kind == Kind.CONST) return false;   // the plain form is the existing path's
        Double f0 = eval(e, clockOp, 0), f1 = eval(e, clockOp, 1);
        if (f0 == null || f1 == null) return false;
        Field clock = clocks.get(keyOf(clockOp));
        if (clock == null) return false;
        if (f0.doubleValue() == f1.doubleValue()) return true;
        double b = other.kind == Kind.CONST ? other.value : other.dvalue;
        // outcomes can differ between f < b, f == b and f > b: no sleep from the first value at b to the first above it
        long atOrAbove = firstAbove(e, clockOp, b, false), above = firstAbove(e, clockOp, b, true);
        // never crossed within the clock range: the outcome never changes
        if (atOrAbove == Long.MIN_VALUE && above == Long.MIN_VALUE || atOrAbove == Long.MAX_VALUE && above == Long.MAX_VALUE) return true;
        long x = Math.min(atOrAbove, above), y = Math.max(atOrAbove, above);
        long bl = x == Long.MIN_VALUE ? Long.MIN_VALUE : x - 2, bh = y == Long.MAX_VALUE ? Long.MAX_VALUE : y + 1;
        addCond(Cond.band(clock, bl, bh, clockOp.name + " expression ? " + b + " outside (" + bl + ", " + bh + ")"));
        return true;
    }

    /** strictly monotone functions of one argument (a changing value is seen by the periodicity check, nothing saturates) */
    private static final Set<String> MONOTONE = Set.of("java/lang/Math.pow", "java/lang/Math.exp", "java/lang/Math.sqrt", "java/lang/Math.log",
            "net/minecraft/util/Mth.sqrt");

    /** a monotone function of an expression of one clock (Math.pow(0.997, tickCount - stamp)): its value is state the window sees */
    private boolean monotoneOfClock(Frame<SourceValue>[] fr, int i, MethodInsnNode mi, MethodNode m) {
        if (!MONOTONE.contains(mi.owner + "." + mi.name)) return false;
        int argc = Type.getArgumentTypes(mi.desc).length;
        for (int k = 1; k <= argc; k++) {
            Operand o = operand(fr[i], k, fr, m, 0);
            if (!containsClock(o)) {
                if (o.kind != Kind.CONST && o.kind != Kind.FCONST && containsGameTime(o)) return false;
                continue;
            }
            Operand c = singleClock(o, true);
            if (c == null) return false;
            evalFields = true;
            Double f0, f1;
            try {
                f0 = eval(o, c, 0);
                f1 = eval(o, c, 1);
            } finally {
                evalFields = false;
            }
            if (f0 == null || f1 == null || f0.doubleValue() == f1.doubleValue()) return false;
        }
        return true;
    }

    /** the one clock an expression reads (null: none, two, or another kind of leaf) */
    private Operand singleClock(Operand e) {
        return singleClock(e, false);
    }

    /** fields: other own fields may be leaves too (state, constant while asleep; evaluated as 0 - for a slope only) */
    private Operand singleClock(Operand e, boolean fields) {
        if (e == null) return null;
        if (isClock(e)) return e;
        if (e.kind == Kind.CONST || e.kind == Kind.FCONST) return null;
        if (e.kind != Kind.ARITH || e.op == Opcodes.IREM || e.op == Opcodes.LREM) return null;   // a remainder is not monotone (moduli are their own path)
        Operand x = e.a == null ? null : singleClock(e.a, fields), y = e.b == null ? null : singleClock(e.b, fields);
        if (x != null && y != null) return null;
        Operand c = x != null ? x : y;
        if (c == null) return null;
        if (e.a != null && x == null && !leafOk(e.a, fields) || e.b != null && y == null && !leafOk(e.b, fields)) return null;
        return c;
    }

    private boolean evalFields;

    private boolean leafOk(Operand o, boolean fields) {
        if (fields && o.kind == Kind.FIELD && o.self && !isClock(o)) return true;
        boolean saved = evalFields;
        evalFields = fields;
        try {
            return o.kind == Kind.CONST || o.kind == Kind.FCONST || o.kind == Kind.ARITH && !containsClock(o) && eval(o, null, 0) != null;
        } finally {
            evalFields = saved;
        }
    }

    /** the expression's value for a clock value, with Java's arithmetic per opcode (null: not evaluable) */
    private Double eval(Operand e, Operand clockOp, long c) {
        if (e == null) return null;
        if (clockOp != null && e == clockOp || isClock(e) && clockOp != null && e.name.equals(clockOp.name)) return (double) c;
        if (e.kind == Kind.CONST) return (double) e.value;
        if (e.kind == Kind.FCONST) return e.dvalue;
        if (evalFields && e.kind == Kind.FIELD && e.self && !isClock(e)) return 0.0;
        if (e.kind != Kind.ARITH) return null;
        Double x = eval(e.a, clockOp, c);
        if (x == null) return null;
        int op = e.op;
        if (e.b == null) {
            return switch (op) {
                case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> (double) (float) (double) x;
                case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> x;
                case Opcodes.F2I, Opcodes.D2I -> (double) (int) (double) x;
                case Opcodes.F2L, Opcodes.D2L -> (double) (long) (double) x;
                case Opcodes.INEG -> (double) (-(int) (double) x);
                case Opcodes.LNEG -> (double) (-(long) (double) x);
                case Opcodes.FNEG -> (double) (-(float) (double) x);
                case Opcodes.DNEG -> -x;
                default -> null;
            };
        }
        Double y = eval(e.b, clockOp, c);
        if (y == null) return null;
        int base = (op - Opcodes.IADD) % 4;   // 0 int, 1 long, 2 float, 3 double
        int kind = (op - Opcodes.IADD) / 4;   // add, sub, mul, div, rem
        if (op < Opcodes.IADD || op > Opcodes.LREM) return null;
        switch (base) {
            case 0: {
                int p = (int) (double) x, q = (int) (double) y;
                if ((kind == 3 || kind == 4) && q == 0) return null;
                return (double) (kind == 0 ? p + q : kind == 1 ? p - q : kind == 2 ? p * q : kind == 3 ? p / q : p % q);
            }
            case 1: {
                long p = (long) (double) x, q = (long) (double) y;
                if ((kind == 3 || kind == 4) && q == 0) return null;
                return (double) (kind == 0 ? p + q : kind == 1 ? p - q : kind == 2 ? p * q : kind == 3 ? p / q : p % q);
            }
            case 2: {
                float p = (float) (double) x, q = (float) (double) y;
                if (kind == 4) return null;
                return (double) (kind == 0 ? p + q : kind == 1 ? p - q : kind == 2 ? p * q : p / q);
            }
            default: {
                if (kind == 4) return null;
                return kind == 0 ? x + y : kind == 1 ? x - y : kind == 2 ? x * y : x / y;
            }
        }
    }

    /**
     * The smallest clock value whose f is above the bound (strictly if strict, else at or above it) for an increasing
     * f; for a decreasing f the smallest value whose f is below it - by binary search over int clock values.
     * Long.MIN_VALUE if every value qualifies, Long.MAX_VALUE if none does.
     */
    private long firstAbove(Operand e, Operand clockOp, double bound, boolean strict) {
        if (Double.isInfinite(bound)) return (bound < 0) == strict ? Long.MIN_VALUE : Long.MAX_VALUE;
        boolean up = eval(e, clockOp, 1) > eval(e, clockOp, 0);
        long lo = -1_000_000_000L, hi = 1_000_000_000L;   // clock values; wider ranges overflow (c - 10 at Integer.MIN_VALUE)
        java.util.function.LongPredicate q = c -> {
            double v = eval(e, clockOp, c);
            return up ? (strict ? v > bound : v >= bound) : (strict ? v < bound : v <= bound);
        };
        if (q.test(lo)) return Long.MIN_VALUE;
        if (!q.test(hi)) return Long.MAX_VALUE;
        while (hi - lo > 1) {
            long mid = lo + (hi - lo) / 2;
            if (q.test(mid)) hi = mid;
            else lo = mid;
        }
        return hi;
    }

    private boolean clockArgument(Frame<SourceValue>[] fr, int i, MethodInsnNode mi, MethodNode m) {
        int argc = Type.getArgumentTypes(mi.desc).length;
        for (int k = 1; k <= argc; k++) if (containsClock(operand(fr[i], k, fr, m, 0))) return true;
        return false;
    }

    private static String desc(Operand o) {
        return o == null ? "null" : o.kind + (o.name != null ? " " + o.name : "") + (o.self ? " self" : "");
    }

    private void condition(Operand x, Operand y, Set<String> beChain, Class<?> beClass) {
        String cx = ctxOf(x), cy = ctxOf(y);
        condCtx = cx != null ? cx : cy;
        try {
            condition0(x, y, beChain, beClass);
        } finally {
            condCtx = null;
        }
    }

    private void condition0(Operand x, Operand y, Set<String> beChain, Class<?> beClass) {
        boolean xc = isClock(x), yc = isClock(y);
        boolean xg = x != null && x.kind == Kind.GAMEMOD, yg = y != null && y.kind == Kind.GAMEMOD;
        if (!xc && !yc && !xg && !yg) {
            if ((x != null && x.kind == Kind.GAMETIME) || (y != null && y.kind == Kind.GAMETIME)) reject = "game time compared without modulo (" + desc(x) + " vs " + desc(y) + ")";
            else if (collect && ageCond(x, y, beClass)) return;
            else if (containsGameTime(x) || containsGameTime(y)) reject = "game time in an expression the analysis does not model";
            else if (collect && (containsClock(x) || containsClock(y)) && affineThreshold(containsClock(x) ? x : y, containsClock(x) ? y : x)) return;
            else if (containsClock(x) || containsClock(y)) clockModulo(containsClock(x) ? x : y, containsClock(x) ? y : x);
            return;   // not a time condition: its inputs are covered by the read set / own state
        }
        if (xg || yg) {
            Operand g = xg ? x : y, other = xg ? y : x;
            if (other == null || other.kind != Kind.CONST) { reject = "game time modulo compared with non-constant"; return; }
            addCond(new Cond(null, null, other.value, g.value, "gameTime % " + g.value + " ? " + other.value));
            return;
        }
        Operand c = xc ? x : y, t = xc ? y : x;
        Field clock = clocks.get(keyOf(c));
        if (t == null) { reject = "clock compared with unknown"; return; }
        switch (t.kind) {
            case CONST -> {
                if (clock == null) { reject = "clock not accessible: " + c.name; return; }
                addCond(new Cond(clock, null, t.value, 0, c.name + " ? " + t.value));
            }
            case ARITH -> {   // clock ? field +- const
                Operand fa = t.a, cb = t.b;
                boolean add = t.op == Opcodes.IADD || t.op == Opcodes.LADD, sub = t.op == Opcodes.ISUB || t.op == Opcodes.LSUB;
                if ((add || sub) && fa != null && cb != null && fa.kind == Kind.FIELD && (!entityMode || fa.self) && beChain.contains(fa.owner)
                        && !BASE_FIELDS.contains(fa.name) && !isClock(fa) && cb.kind == Kind.CONST) {
                    Field tf = field(beClass, fa.name);
                    if (tf == null || clock == null) { reject = "field not accessible: " + fa.name; return; }
                    addCond(new Cond(clock, tf, add ? cb.value : -cb.value, 0, c.name + " ? " + fa.name + (add ? " + " : " - ") + cb.value));
                } else {
                    reject = "clock " + c.name + " compared with ARITH";
                }
            }
            case FIELD -> {
                if (peerClock(t) && t.name.equals(c.name)) {
                    if (clock == null) { reject = "clock not accessible: " + c.name; return; }
                    addCond(Cond.peer(clock, c.name + " ? other." + t.name));
                    return;
                }
                if (entityMode && !t.self) { reject = "clock compared with another object's " + t.name; return; }
                if (!beChain.contains(t.owner)) { reject = "clock compared with foreign field " + t.name; return; }
                Field tf = field(beClass, t.name);
                if (tf == null || clock == null) { reject = "field not accessible: " + t.name; return; }
                addCond(new Cond(clock, tf, 0, 0, c.name + " ? " + t.name));
            }
            default -> reject = "clock " + c.name + " compared with " + t.kind;
        }
    }

    /** Read-only JDK helpers: they cannot change what they are given. */
    private static final Set<String> PURE_READERS = Set.of("java/util/Arrays.equals", "java/util/Arrays.hashCode", "java/util/Arrays.deepEquals",
            "java/util/Arrays.deepHashCode", "java/util/Arrays.copyOf", "java/util/Arrays.copyOfRange", "java/util/Arrays.toString",
            "java/util/Arrays.mismatch", "java/util/Arrays.compare", "java/util/Arrays.binarySearch", "java/util/Objects.equals",
            "java/util/Objects.hash", "java/util/Objects.hashCode", "java/util/Objects.isNull", "java/util/Objects.nonNull",
            "java/util/Objects.requireNonNull", "java/lang/System.identityHashCode");

    private static boolean pureReader(MethodInsnNode mi) {
        return mi.getOpcode() == Opcodes.INVOKESTATIC && PURE_READERS.contains(mi.owner + "." + mi.name);
    }

    /** Fields whose referenced object changes in place (inventories, maps, collections, stacks, arrays). */
    private boolean containerType(String desc) {
        if (desc.startsWith("[")) return true;
        if (!desc.startsWith("L")) return false;
        String t = desc.substring(1, desc.length() - 1);
        if (t.equals("net/minecraft/core/NonNullList") || t.equals("net/minecraft/world/item/ItemStack")) return true;
        try {
            Class<?> c = Class.forName(t.replace('/', '.'), false, loader);
            return java.util.Map.class.isAssignableFrom(c) || java.util.Collection.class.isAssignableFrom(c);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Owned state whose changes the field snapshot sees: immutable/value types, caches, and collections (content hash). */
    private boolean safeOwned(String type) {
        if (type == null || SAFE_OWNED.contains(type)) return true;
        if (type.length() == 1) return true;   // primitive field: passed by value, cannot be mutated by the callee
        try {
            Class<?> c = Class.forName(type.replace('/', '.'), false, loader);
            if (deeplyImmutable(c, new HashSet<>())) return true;   // nothing to change: the reference is the whole state
            return java.util.Map.class.isAssignableFrom(c) || java.util.Collection.class.isAssignableFrom(c);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Every instance field of the class and its superclasses is final and itself deeply immutable (primitives, String,
     * boxed values, such classes). Enums without state, records of values. Final classes only (a subclass could add
     * mutable state), except enums, whose constant bodies are checked the same way.
     */
    private static boolean deeplyImmutable(Class<?> c, Set<Class<?>> seen) {
        if (c.isPrimitive() || c == String.class || c == Integer.class || c == Long.class || c == Boolean.class || c == Short.class
                || c == Byte.class || c == Character.class || c == Float.class || c == Double.class) return true;
        if (c.isArray() || c.isInterface()) return false;
        if (!seen.add(c)) return true;
        if (!Modifier.isFinal(c.getModifiers()) && !c.isEnum()) return false;
        if (c.isEnum()) for (Object k : c.getEnumConstants()) if (k.getClass() != c && !fieldsImmutable(k.getClass(), seen)) return false;
        return fieldsImmutable(c, seen);
    }

    private static boolean fieldsImmutable(Class<?> c, Set<Class<?>> seen) {
        for (Class<?> k = c; k != null && k != Object.class && k != Enum.class && k != Record.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!Modifier.isFinal(f.getModifiers()) || !deeplyImmutable(f.getType(), seen)) return false;
            }
        }
        return true;
    }

    /** Moduli of periodic clock conditions ((clock + x) % N ? y), for the entity engine's period. */
    final java.util.TreeSet<Long> moduli = new java.util.TreeSet<>();
    /**
     * Mob plans: each modulus with its kind - "tick N r" (the entity's own tickCount % N compared with the constant r:
     * a known tick, run for real while asleep and checked), "clock path.field N" (another clock: it must stand still),
     * "period N" (anything else: in the period).
     */
    final Set<String> modKinds = new java.util.TreeSet<>();

    /** (clock +- x) % N ? y with N constant(s) and x, y free of clocks: a periodic condition; anything else is refused. */
    private void clockModulo(Operand e, Operand other) {
        if (containsClock(other) || e == null || e.kind != Kind.ARITH || (e.op != Opcodes.IREM && e.op != Opcodes.LREM)) {
            reject = "clock in an expression the analysis does not model";
            return;
        }
        Operand base = e.a, mod = e.b;
        if (containsClock(mod)) { reject = "clock as a modulus"; return; }
        long[] ns = mod.kind == Kind.CONST ? new long[] {mod.value} : mod.kind == Kind.CONSTSET ? mod.values : null;
        if (ns == null) { reject = "clock modulo a non-constant"; return; }
        Operand clockOp = base;
        if (!isClock(base)) {   // clock + x / x + clock / clock - x
            if (base.kind == Kind.ARITH && (base.op == Opcodes.IADD || base.op == Opcodes.LADD || base.op == Opcodes.ISUB || base.op == Opcodes.LSUB)
                    && (isClock(base.a) && !containsClock(base.b) || isClock(base.b) && !containsClock(base.a) && base.op != Opcodes.ISUB && base.op != Opcodes.LSUB))
                clockOp = isClock(base.a) ? base.a : base.b;
            else { reject = "clock in an expression the analysis does not model"; return; }
        }
        Field clock = clocks.get(keyOf(clockOp));
        if (clock == null) { reject = "clock not accessible: " + clockOp.name; return; }
        for (long n : ns) {
            if (n <= 0) { reject = "clock modulo " + n; return; }
            moduli.add(n);
            if (collect) {
                boolean tick = clockOp.name.equals("tickCount") && ctxPath.isEmpty() && base == clockOp;
                if (tick && other != null && other.kind == Kind.CONST && other.value >= 0 && other.value < n) modKinds.add("tick " + n + " " + other.value);
                else if (!clockOp.name.equals("tickCount")) modKinds.add("clock " + pathKey(clockOp.name) + " " + n);
                else modKinds.add("period " + n);
            }
            addCond(new Cond(clock, null, 0, n, "(" + clockOp.name + " + ..) % " + n + (collect ? " @" + curHere : ""), true));
        }
    }

    /** A local class declared in this very method (its instances are the method's scratch objects), not held by any own field. */
    private boolean localScratch(String cls, String owner, MethodNode m) {
        ClassNode cn = node(cls);
        if (cn == null || !owner.equals(cn.outerClass) || cn.outerMethod == null) return false;
        boolean here = m.name.equals(cn.outerMethod) && m.desc.equals(cn.outerMethodDesc) || m.name.startsWith("lambda$" + cn.outerMethod + "$");
        if (!here) return false;
        for (String c : chain) {
            ClassNode k = node(c);
            if (k != null) for (FieldNode f : k.fields) if (f.desc.contains("L" + cls + ";")) return false;
        }
        return true;
    }

    /** Field key by declaring class: javac names the receiver's static type as owner (ItemEntity.tickCount = Entity.tickCount). */
    // ---------------------------------------------------------------- contexts (mob sleep, notes/47)

    /** a static method whose body only returns (the debug packet stubs) */
    private boolean emptyMethod(MethodInsnNode mi) {
        MethodNode t = method(mi.owner, mi.name, mi.desc);
        if (t == null || (t.access & Opcodes.ACC_STATIC) == 0) return false;
        for (AbstractInsnNode x : t.instructions) if (x.getOpcode() >= 0 && x.getOpcode() != Opcodes.RETURN) return false;
        return true;
    }

    /**
     * Greedy: the guard removing the most reasons is assumed, kept when the whole analysis then gives fewer reasons and
     * the sample (a live entity of the class) satisfies it now - an assumption an idle entity does not hold is useless.
     */
    private void assumptionSearch(String decl, MethodNode root, Class<?> ec, Field tickCount, Set<String> chainSet, Set<String> seen) throws AnalyzerException {
        Set<String> tried = new HashSet<>();
        List<String> neutral = new ArrayList<>();
        int cur = reasons.size();
        boolean dirty = false;
        for (int step = 0; step < MAX_ASSUME_STEPS && cur > 0; step++) {
            Map<String, Integer> cover = new java.util.TreeMap<>();
            for (var e : reasonGuards.entrySet())
                if (reasons.containsKey(e.getKey())) for (String g : e.getValue()) if (!g.startsWith("rv:") && !tried.contains(g)) cover.merge(g, 1, Integer::sum);
            String best = null;
            for (var e : cover.entrySet()) if (holds(e.getKey()) && (best == null || e.getValue() > cover.get(best))) best = e.getKey();
            if (best == null) break;
            tried.add(best);
            assume(best, true);
            int r = rounds(decl, root, ec, tickCount, chainSet, seen);
            if (r < cur) {
                cur = r;
                dirty = false;
                tried.clear();   // what failed alone may help now
                if (SEARCH_LOG) System.out.println("[sleep/sleep] " + simple(internal(ec)) + " assume " + best + ": " + r + " reasons");
            } else if (r == cur) {
                // neutral: kept (it holds on the sample and guards a remaining reason) - some code needs two assumptions
                dirty = false;
                neutral.add(best);
                if (SEARCH_LOG) System.out.println("[sleep/sleep] " + simple(internal(ec)) + " assume (neutral) " + best);
            } else {
                if (SEARCH_LOG) System.out.println("[sleep/sleep] " + simple(internal(ec)) + " tried " + best + ": " + r + " reasons, kept " + cur + " " + reasons.keySet());
                assume(best, false);
                dirty = true;
            }
        }
        // the neutral ones nothing needed in the end are dropped
        if (!neutral.isEmpty() && dirty) rounds(decl, root, ec, tickCount, chainSet, seen);
        Set<String> have = new HashSet<>(reasons.keySet());
        if (SEARCH_LOG) System.out.println("[sleep/sleep] " + simple(internal(ec)) + " left: " + have);
        for (String g : neutral) {
            assume(g, false);
            rounds(decl, root, ec, tickCount, chainSet, seen);
            boolean needed = !have.containsAll(reasons.keySet());   // a reason came back without it
            if (needed) assume(g, true);
            else if (SEARCH_LOG) System.out.println("[sleep/sleep] " + simple(internal(ec)) + " dropped " + g);
            dirty = needed;
        }
        if (dirty) rounds(decl, root, ec, tickCount, chainSet, seen);
        ok();
        if (SEARCH_LOG) System.out.println("[sleep/sleep] " + simple(internal(ec)) + " search: " + runCount + " runs, " + roundCount + " rounds, "
                + roundNs / 1_000_000 + " ms in rounds, " + guardNs / 1_000_000 + " ms in guards");
    }

    private static final int MAX_ASSUME_STEPS = Integer.getInteger("lab.sleep.assumeSteps", 20);
    private static final boolean SEARCH_LOG = Boolean.getBoolean("lab.sleep.searchLog");

    private void assume(String g, boolean on) {
        String k = g.substring(g.indexOf(':') + 1);
        Set<String> set = g.startsWith("zero:") ? zeroFields : g.startsWith("false:") ? falseCalls : g.startsWith("empty:") ? emptyFields
                : g.startsWith("le0:") ? le0Fields : g.startsWith("ge0:") ? ge0Fields : forcedNulls;
        if (on) set.add(k);
        else set.remove(k);
    }

    /** the assumption is true of the sample now (fields read, boolean getters called reflectively) */
    private boolean holds(String g) {
        String k = g.substring(g.indexOf(':') + 1);
        int dot = k.lastIndexOf('.');
        Object at = dot < 0 ? sample : resolve(k.substring(0, dot));
        String name = dot < 0 ? k : k.substring(dot + 1);
        if (at == null) return false;
        try {
            if (g.startsWith("false:")) {
                int hash = name.indexOf('#');
                String mn = hash < 0 ? name : name.substring(0, hash);
                for (Class<?> c = at.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                    for (java.lang.reflect.Method mm : c.getDeclaredMethods())
                        if (mm.getName().equals(mn) && mm.getReturnType() == boolean.class
                                && (hash < 0 ? mm.getParameterCount() == 0 : mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == int.class)) {
                            mm.setAccessible(true);
                            return !(Boolean) (hash < 0 ? mm.invoke(at) : mm.invoke(at, Integer.parseInt(name.substring(hash + 1))));
                        }
                return false;
            }
            Field gf = fieldIn(at.getClass(), name);
            if (gf != null) {
                gf.setAccessible(true);
                Object v = gf.get(at);
                if (g.startsWith("null:")) return v == null;
                if (g.startsWith("empty:")) return v instanceof java.util.Collection<?> cc ? cc.isEmpty() : v instanceof java.util.Map<?, ?> mm && mm.isEmpty();
                if (g.startsWith("le0:")) return v instanceof Number n && n.longValue() <= 0;
                if (g.startsWith("ge0:")) return v instanceof Number n && n.longValue() >= 0;
                return v instanceof Boolean b ? !b : v instanceof Number n && n.longValue() == 0;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        return false;
    }

    /** the sample's object at a context path ("navigation.pathFinder", "goalSelector.availableGoals[3]") */
    private Object resolve(String path) {
        Object o = sample;
        for (String part : path.split("\\.")) {
            if (o == null) return null;
            int br = part.indexOf('[');
            o = fieldValue(o, br < 0 ? part : part.substring(0, br));
            if (br >= 0) {
                if (!(o instanceof java.util.Collection<?> c)) return null;
                int k = Integer.parseInt(part.substring(br + 1, part.length() - 1));
                Object[] els;
                try {
                    els = c.toArray();
                } catch (java.util.ConcurrentModificationException e) {
                    return null;
                }
                o = k < els.length ? els[k] : null;
            }
        }
        return o;
    }

    /**
     * A jump's operand is a boolean method's result: "false:<path>.<name>" when it is called on the current object (or
     * on the entity through a back-pointer field), "rv:<local>:<name>" when on a local (a goal in GoalSelector's loop).
     */
    private String falseCall(Frame<SourceValue>[] fr, int b, MethodNode m) {
        Frame<SourceValue> f0 = fr[b];
        if (f0 == null || f0.getStackSize() < 1) return null;
        SourceValue v = f0.getStack(f0.getStackSize() - 1);
        if (v.insns.size() == 1 && v.insns.iterator().next() instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
            // a boolean kept in a local (updateFallFlying's flag): the value of its one store
            SourceValue lv = f0.getLocal(load.var);
            if (lv.insns.size() != 1 || !(lv.insns.iterator().next() instanceof VarInsnNode st) || st.getOpcode() != Opcodes.ISTORE) return null;
            Frame<SourceValue> sf = fr[m.instructions.indexOf(st)];
            if (sf == null || sf.getStackSize() < 1) return null;
            v = sf.getStack(sf.getStackSize() - 1);
        }
        if (v.insns.size() != 1 || !(v.insns.iterator().next() instanceof MethodInsnNode mi) || !(mi.desc.equals("()Z") || mi.desc.equals("(I)Z"))
                || mi.getOpcode() == Opcodes.INVOKESTATIC || mi.getOpcode() == Opcodes.INVOKESPECIAL) return null;
        Frame<SourceValue> f = fr[m.instructions.indexOf(mi)];
        if (f == null) return null;
        int argc = mi.desc.equals("()Z") ? 0 : 1;
        String name = mi.name;
        if (argc == 1) {
            Operand a = operand(f, 1, fr, m, 0);
            if (a.kind != Kind.CONST) return null;
            name += "#" + a.value;   // the flag's index
        }
        if (onThis(f, argc + 1, fr, m)) return "false:" + pathKey(name);
        Operand r = operand(f, argc + 1, fr, m, 0);
        if (r.kind == Kind.FIELD && r.self && ctxObj != null && sample != null && fieldValue(ctxObj, r.name) == sample) return "false:" + name;
        String sig = rvSig(f.getStack(f.getStackSize() - 1 - argc), f, m);
        return sig == null ? null : "rv:" + sig + ":" + name;
    }

    private static final Set<String> VIEWS = Set.of("iterator", "keySet", "values", "entrySet");

    /**
     * A jump on hasNext() / isEmpty() of an own collection or map field (through keySet() / values() / entrySet() /
     * iterator() and locals): "emp:0:<path>" (hasNext, false when empty) or "emp:1:<path>" (isEmpty, true when empty).
     */
    private String emptyCall(Frame<SourceValue>[] fr, int b, MethodNode m) {
        Frame<SourceValue> f0 = fr[b];
        if (f0 == null || f0.getStackSize() < 1) return null;
        SourceValue v = f0.getStack(f0.getStackSize() - 1);
        if (v.insns.size() != 1 || !(v.insns.iterator().next() instanceof MethodInsnNode mi) || !mi.desc.equals("()Z")
                || !(mi.name.equals("hasNext") || mi.name.equals("isEmpty")) || mi.getOpcode() == Opcodes.INVOKESTATIC) return null;
        Frame<SourceValue> f = fr[m.instructions.indexOf(mi)];
        if (f == null || f.getStackSize() < 1) return null;
        SourceValue r = f.getStack(f.getStackSize() - 1);
        for (int hops = 0; hops < 8; hops++) {
            if (r.insns.size() != 1) return null;
            AbstractInsnNode x = r.insns.iterator().next();
            Frame<SourceValue> xf = fr[m.instructions.indexOf(x)];
            if (xf == null) return null;
            if (x instanceof VarInsnNode vi && vi.getOpcode() == Opcodes.ALOAD && vi.var != 0) {
                SourceValue lv = xf.getLocal(vi.var);
                if (lv.insns.size() != 1 || !(lv.insns.iterator().next() instanceof VarInsnNode st) || st.getOpcode() != Opcodes.ASTORE) return null;
                Frame<SourceValue> sf = fr[m.instructions.indexOf(st)];
                if (sf == null || sf.getStackSize() < 1) return null;
                r = sf.getStack(sf.getStackSize() - 1);
            } else if (x instanceof MethodInsnNode vm && VIEWS.contains(vm.name) && Type.getArgumentTypes(vm.desc).length == 0 && vm.getOpcode() != Opcodes.INVOKESTATIC) {
                if (xf.getStackSize() < 1) return null;
                r = xf.getStack(xf.getStackSize() - 1);
            } else if (x instanceof FieldInsnNode gf && gf.getOpcode() == Opcodes.GETFIELD) {
                if (!onThis(xf, 1, fr, m)) return null;
                if (ctxPath.isEmpty() && (chain == null || !chain.contains(gf.owner))) return null;
                return "emp:" + (mi.name.equals("isEmpty") ? "1" : "0") + ":" + pathKey(gf.name);
            } else {
                return null;
            }
        }
        return null;
    }

    /** a value loaded from a local: the local and the stores its value comes from */
    private static String rvSig(SourceValue v, Frame<SourceValue> f, MethodNode m) {
        if (v.insns.size() != 1 || !(v.insns.iterator().next() instanceof VarInsnNode vi) || vi.getOpcode() != Opcodes.ALOAD || vi.var == 0) return null;
        SourceValue lv = f.getLocal(vi.var);
        StringBuilder sb = new StringBuilder().append(vi.var);
        List<Integer> at = new ArrayList<>();
        for (AbstractInsnNode x : lv.insns) at.add(m.instructions.indexOf(x));
        java.util.Collections.sort(at);
        return sb.append('@').append(at).toString();
    }

    /**
     * Per instruction of a method, the guards making it unreachable (plus the call site's): for every undecided jump on
     * an own int / boolean field (0), an own boolean call (false) or an own object field (null), the instructions only
     * reachable through the other side.
     */
    @SuppressWarnings("unchecked")
    long guardNs, roundNs;
    int roundCount, runCount;

    private Set<String>[] guards(String owner, MethodNode m, Flow flow, Set<String> base) throws AnalyzerException {
        if (!collect || sample == null) return null;
        long t0 = System.nanoTime();
        try {
            return guards0(owner, m, flow, base);
        } finally {
            guardNs += System.nanoTime() - t0;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String>[] guards0(String owner, MethodNode m, Flow flow, Set<String> base) throws AnalyzerException {
        Frame<SourceValue>[] fr = flow.fr();
        AbstractInsnNode[] in = m.instructions.toArray();
        Set<String>[] out = null;
        for (int b = 0; b < in.length; b++) {
            if (fr[b] == null || !flow.live()[b] || !(in[b] instanceof JumpInsnNode j)) continue;
            int op = j.getOpcode();
            String key = null;
            boolean taken = false;
            if (op >= Opcodes.IFEQ && op <= Opcodes.IFLE) {
                Operand o = operand(fr[b], 1, fr, m, 0);
                if (o.kind == Kind.FIELD && o.self && o.type != null && o.type.length() == 1 && "IZBSC".contains(o.type) && !zeroField(o)
                        && !signField(o, le0Fields) && !signField(o, ge0Fields)
                        && (!ctxPath.isEmpty() || chain != null && chain.contains(o.owner))) {
                    if (op == Opcodes.IFEQ || op == Opcodes.IFNE) {
                        key = "zero:" + pathKey(o.name);
                        taken = op == Opcodes.IFEQ;
                    } else if (op == Opcodes.IFLE || op == Opcodes.IFGT) {
                        key = "le0:" + pathKey(o.name);
                        taken = op == Opcodes.IFLE;
                    } else {
                        key = "ge0:" + pathKey(o.name);
                        taken = op == Opcodes.IFGE;
                    }
                } else if ((op == Opcodes.IFEQ || op == Opcodes.IFNE) && emptyCall(fr, b, m) != null) {
                    String ec = emptyCall(fr, b, m);
                    if (!emptyFields.contains(ec.substring(6))) {
                        key = "empty:" + ec.substring(6);
                        boolean value = ec.charAt(4) == '1';
                        taken = (op == Opcodes.IFNE) == value;
                    }
                } else if (op == Opcodes.IFEQ || op == Opcodes.IFNE) {
                    String fc = falseCall(fr, b, m);
                    if (fc != null && !(fc.startsWith("false:") && falseCalls.contains(fc.substring(6)))) {
                        key = fc;
                        taken = op == Opcodes.IFEQ;
                    }
                }
            } else if (op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) {
                Operand o = operand(fr[b], 1, fr, m, 0);
                if (o.kind == Kind.FIELD && o.self && !nullField(o) && (!ctxPath.isEmpty() || chain != null && chain.contains(o.owner))) {
                    key = "null:" + pathKey(o.name);
                    taken = op == Opcodes.IFNULL;
                }
            }
            if (key == null) continue;
            int target = m.instructions.indexOf(j.label);
            forceAt = b;
            forceTo = taken ? target : b + 1;
            boolean[] l2;
            try {
                l2 = flow(owner, m).live();
            } finally {
                forceAt = -1;
            }
            for (int i = 0; i < in.length; i++) {
                if (!flow.live()[i] || l2[i]) continue;
                if (out == null) out = new Set[in.length];
                if (out[i] == null) out[i] = new HashSet<>(base);
                out[i].add(key);
            }
        }
        return out;
    }

    private static final Set<String> DRAW_METHODS = Set.of("nextInt", "nextFloat", "nextDouble", "nextBoolean", "nextLong");

    /** a draw on the entity's own random: the receiver evaluates, on the sample, to the sample's Entity.random */
    private boolean entityDraw(MethodInsnNode mi, Frame<SourceValue> f, Frame<SourceValue>[] frames, MethodNode m) {
        if (sample == null || f == null || !DRAW_METHODS.contains(mi.name) || mi.getOpcode() == Opcodes.INVOKESTATIC) return false;
        if (!mi.owner.endsWith("RandomSource") && !mi.owner.equals("java/util/Random")) return false;
        if (sampleRandom == null) sampleRandom = fieldValue(sample, "random");
        int argc = Type.getArgumentTypes(mi.desc).length;
        if (f.getStackSize() < argc + 1) return false;
        Object r = sampleValue(f.getStack(f.getStackSize() - argc - 1), frames, m, 0);
        return r != null && r == sampleRandom;
    }

    /** mob analysis, in a context other than the entity: whether the value fromTop is the entity itself (on the sample) */
    private boolean onEntity(Frame<SourceValue> f, int fromTop, Frame<SourceValue>[] frames, MethodNode m) {
        if (!entityMode || sample == null || ctxPath.isEmpty() || f == null || f.getStackSize() < fromTop) return false;
        return sampleValue(f.getStack(f.getStackSize() - fromTop), frames, m, 0) == sample;
    }

    /** a no-argument getter of a primitive field called on the entity from another context: that field, in its context */
    private boolean entityRead(MethodInsnNode mi, Frame<SourceValue> f, Frame<SourceValue>[] frames, MethodNode m, Operand o) {
        if (mi.getOpcode() == Opcodes.INVOKESTATIC || Type.getArgumentTypes(mi.desc).length != 0) return false;
        Type rt = Type.getReturnType(mi.desc);
        if (rt.getSort() < Type.BOOLEAN || rt.getSort() > Type.DOUBLE || !onEntity(f, 1, frames, m)) return false;
        String decl = ownerOf(internal(sample.getClass()), mi.name, mi.desc);
        MethodNode t = decl == null ? null : method(decl, mi.name, mi.desc);
        FieldInsnNode got = t == null ? null : getterField(t);
        if (got == null) return false;
        o.kind = Kind.FIELD;
        o.owner = got.owner;
        o.name = got.name;
        o.self = true;
        o.ctx = "";
        o.type = rt.getDescriptor();
        return true;
    }

    /** what a value is on the sample, for this, its fields and plain getters (null: not known) */
    private Object sampleValue(SourceValue v, Frame<SourceValue>[] frames, MethodNode m, int depth) {
        if (depth > 4 || v.insns.size() != 1) return null;
        AbstractInsnNode src = v.insns.iterator().next();
        Frame<SourceValue> f = frames[m.instructions.indexOf(src)];
        if (src instanceof VarInsnNode vi && vi.getOpcode() == Opcodes.ALOAD && vi.var == 0 && (selfMask & 1) != 0) {
            for (AbstractInsnNode x : m.instructions) if (x instanceof VarInsnNode st && st.getOpcode() == Opcodes.ASTORE && st.var == 0) return null;
            return ctxObj;
        }
        if (f == null || f.getStackSize() < 1) return null;
        if (src instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD) {
            Object recv = sampleValue(f.getStack(f.getStackSize() - 1), frames, m, depth + 1);
            return recv == null ? null : fieldValue(recv, fi.name);
        }
        if (src instanceof MethodInsnNode mi && Type.getArgumentTypes(mi.desc).length == 0 && mi.getOpcode() != Opcodes.INVOKESTATIC) {
            Object recv = sampleValue(f.getStack(f.getStackSize() - 1), frames, m, depth + 1);
            if (recv == null) return null;
            String decl = ownerOf(internal(recv.getClass()), mi.name, mi.desc);
            MethodNode t = decl == null ? null : method(decl, mi.name, mi.desc);
            FieldInsnNode got = t == null ? null : getterField(t);
            return got == null ? null : fieldValue(recv, got.name);
        }
        return null;
    }

    /** a draw compared in a jump: recorded with the other side (a constant, or a clock plus a constant) */
    private void drawCond(Operand x, Operand y, int jumpOp, int cmpOp, String owner, MethodNode m) {
        boolean first = x.kind == Kind.DRAW;
        Operand d = first ? x : y, o = first ? y : x;
        if (o.kind == Kind.DRAW) { reject = "two draws compared"; return; }
        double c = 0;
        String clockPath = null;
        Field clock = null;
        int other = DrawCond.CONST;
        if (o.kind == Kind.CONST) c = o.value;
        else if (o.kind == Kind.FCONST) c = o.dvalue;
        else if (o.kind == Kind.FIELD && o.self && o.ctx == null && o.type != null && o.type.length() == 1 && ctxObj != null && finalField(ctxObj, o.name)) {
            // a final primitive of the current object (a goal's probability): read at run time
            other = DrawCond.FINAL;
            clockPath = pathKey(o.name);
            clock = field(ctxObj.getClass(), o.name);
            if (clock == null) { reject = "field not accessible: " + clockPath; return; }
        } else if (isClock(o)) {
            other = DrawCond.CLOCK;
            clock = clocks.get(keyOf(o));
            clockPath = pathOf(o);
        } else if (o.kind == Kind.ARITH && (o.op == Opcodes.IADD || o.op == Opcodes.ISUB) && isClock(o.a) && o.b != null && o.b.kind == Kind.CONST) {
            other = DrawCond.CLOCK;
            clock = clocks.get(keyOf(o.a));
            clockPath = pathOf(o.a);
            c = o.op == Opcodes.IADD ? o.b.value : -o.b.value;
        } else {
            other = DrawCond.UNKNOWN;   // not predictable: a period that draws here never sleeps
        }
        if (other == DrawCond.CLOCK && clock == null) { reject = "clock not accessible: " + clockPath; return; }
        MethodInsnNode mi = d.draw;
        int line = -1, k = 0;
        for (AbstractInsnNode x2 = mi.getPrevious(); x2 != null; x2 = x2.getPrevious()) {
            if (x2 instanceof LineNumberNode ln) { line = ln.line; break; }
            if (x2 instanceof MethodInsnNode p && DRAW_METHODS.contains(p.name) && (p.owner.endsWith("RandomSource") || p.owner.equals("java/util/Random"))) k++;
        }
        String site = owner + "." + m.name + m.desc + "@" + line + "#" + k;
        String text = (ctxPath.isEmpty() ? "" : ctxPath + ": ") + d.name + " " + (first ? "?" : "?r") + " " + (other == DrawCond.UNKNOWN ? "?" : clockPath != null ? clockPath + (c == 0 ? "" : "+" + (long) c) : c)
                + " @" + simple(owner) + "." + m.name + ":" + line;
        for (DrawCond dc : draws) if (dc.site().equals(site) && dc.text().equals(text)) return;
        draws.add(new DrawCond(site, d.name, jumpOp, cmpOp, first, c, other, clockPath, clock, text));
    }

    /** an object of Minecraft's AI (controls, navigation, sensing, goal selectors, goals) or a subclass of one */
    private static boolean aiObject(Object o) {
        if (o == null || o instanceof net.minecraft.world.entity.Entity || o instanceof java.util.Collection<?> || o instanceof java.util.Map<?, ?>
                || o.getClass().isHidden()) return false;   // a lambda: lambdaHost
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            if (c.getName().startsWith("net.minecraft.world.entity.ai.") || c.getName().startsWith("net.minecraft.world.level.pathfinder.")) return true;
        return false;
    }

    private final Map<String, Boolean> ctorOwnedCache = new HashMap<>();

    /** fields (owner.name) any followed code writes, over all rounds: grown to a fixpoint with the null fields */
    private Set<String> writtenAny = new HashSet<>();
    private static final Set<String> COLLECTION_MUTATORS = Set.of("add", "addAll", "remove", "removeAll", "removeIf", "retainAll", "clear", "put", "putAll",
            "addFirst", "addLast", "removeFirst", "removeLast", "poll", "offer", "push", "pop", "set", "sort", "replaceAll", "compute", "computeIfAbsent", "merge");

    /** the class declaring a field, as reflection names it */
    private String declOf(String owner, String name) {
        for (ClassNode cn = node(owner); cn != null; cn = cn.superName == null ? null : node(cn.superName))
            for (FieldNode fn : cn.fields) if (fn.name.equals(name)) return cn.name;
        return owner;
    }
    /** this round's writes; the next round decides with them (until two rounds agree) */
    private Set<String> writtenNow = new HashSet<>();

    /**
     * Final, or never written by the code a tick runs (Mob.navigation, LivingEntity.brain, Sheep.eatBlockGoal: set
     * while the entity is built or loaded): what the sample holds there stays while it sleeps (a replacement from
     * outside a tick changes a reference the replay checks). A field the tick code reassigns (navigation.path) is
     * state - its value now is a moment of the sample, never a context.
     */
    private boolean effectivelyFinal(Class<?> holder, String name) {
        Field fd = fieldIn(holder, name);
        if (fd == null) return false;
        if (java.lang.reflect.Modifier.isFinal(fd.getModifiers())) return true;
        return !writtenAny.contains(internal(fd.getDeclaringClass()) + "." + name);
    }

    /**
     * A final field that every constructor of its class sets to a new object (walkAnimation = new WalkAnimationState(),
     * combatTracker = new CombatTracker(this)): the object belongs to its holder alone, like the AI objects - followed as
     * a context of its own. Not collections or maps (their content is the holder's state), not entities.
     */
    private boolean ctorOwned(Class<?> holder, String name, Object v) {
        if (v == null || v instanceof net.minecraft.world.entity.Entity || v instanceof java.util.Collection<?> || v instanceof java.util.Map<?, ?>
                || v.getClass().isHidden() || v.getClass().isArray() || v.getClass().isEnum()) return false;
        Field fd = fieldIn(holder, name);
        if (fd == null || !java.lang.reflect.Modifier.isFinal(fd.getModifiers()) || java.lang.reflect.Modifier.isStatic(fd.getModifiers())) return false;
        String decl = internal(fd.getDeclaringClass());
        return ctorOwnedCache.computeIfAbsent(decl + "." + name, k -> {
            ClassNode cn = node(decl);
            if (cn == null) return false;
            int sets = 0;
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("<init>")) continue;
                Frame<SourceValue>[] fr;
                try {
                    fr = dev.lull.ClassBytes.analyze(new Analyzer<>(new SourceInterpreter()), decl, m);
                } catch (AnalyzerException e) {
                    return false;
                }
                AbstractInsnNode[] in = m.instructions.toArray();
                boolean delegates = false;
                int here = 0;
                for (int i = 0; i < in.length; i++) {
                    // this(...) instead of super(...): the other constructor sets it
                    if (in[i] instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>") && mi.owner.equals(decl)
                            && fr[i] != null && thisAt(fr[i], Type.getArgumentTypes(mi.desc).length + 1, m)) delegates = true;
                    if (!(in[i] instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.PUTFIELD || !f.name.equals(name) || !f.owner.equals(decl)) continue;
                    if (fr[i] == null || !thisAt(fr[i], 2, m) || !fresh(fr[i], 1, fr, m, 0)) return false;
                    here++;
                }
                if (!delegates && here == 0) return false;
                sets += here;
            }
            return sets > 0;
        });
    }

    /** the value fromTop is local 0 of a constructor (this) */
    private static boolean thisAt(Frame<SourceValue> f, int fromTop, MethodNode m) {
        if (f.getStackSize() < fromTop) return false;
        SourceValue v = f.getStack(f.getStackSize() - fromTop);
        return v.insns.size() == 1 && v.insns.iterator().next() instanceof VarInsnNode vi && vi.getOpcode() == Opcodes.ALOAD && vi.var == 0;
    }

    private static boolean finalField(Object o, String name) {
        Field f = fieldIn(o.getClass(), name);
        return f != null && java.lang.reflect.Modifier.isFinal(f.getModifiers());
    }

    private static Object fieldValue(Object o, String name) {
        Field f = fieldIn(o.getClass(), name);
        if (f == null || java.lang.reflect.Modifier.isStatic(f.getModifiers())) return null;
        try {
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Per class: each field name to the field a lookup from that class finds (declared there or in the nearest
     * superclass, Object excluded) - getDeclaredField copies the class's fields on every call and throws (a stack
     * trace) for each superclass it is not in: the analysis allocated ~20 GB that way.
     */
    private static final ClassValue<Map<String, Field>> FIELDS_IN = new ClassValue<>() {
        @Override
        protected Map<String, Field> computeValue(Class<?> c) {
            Map<String, Field> m = c.getSuperclass() == null || c.getSuperclass() == Object.class ? new HashMap<>() : new HashMap<>(FIELDS_IN.get(c.getSuperclass()));
            for (Field f : declaredFields(c)) m.put(f.getName(), f);
            return m;
        }
    };
    private static final ClassValue<Field[]> DECLARED = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> c) {
            try {
                return c.getDeclaredFields();
            } catch (Throwable e) {   // a field type that does not load
                return new Field[0];
            }
        }
    };

    /** the field a lookup of {@code name} from class c finds, or null */
    static Field fieldIn(Class<?> c, String name) {
        return c == Object.class ? null : FIELDS_IN.get(c).get(name);
    }

    /** c.getDeclaredFields(), once per class (the array is shared: not to be changed) */
    static Field[] declaredFields(Class<?> c) {
        return DECLARED.get(c);
    }

    private static Set<String> chainOf(Class<?> c) {
        Set<String> s = new HashSet<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) s.add(internal(k));
        return s;
    }

    /**
     * A call whose receiver is another context: an AI object held in a field of the current one (or handed out by the
     * entity's getter of one: getNavigation...), the entity itself from inside one (Goal.mob and the like), or an
     * element of a collection of AI objects of the current one (GoalSelector's goals: every element followed). The
     * callee is followed in that context. False if the call is not one of these.
     */
    private boolean crossContext(Frame<SourceValue>[] fr, int i, MethodInsnNode mi, MethodNode m, int depth, Set<String> seen, boolean pass1) throws AnalyzerException {
        if (!entityMode || sample == null || ctxObj == null || mi.getOpcode() == Opcodes.INVOKESTATIC || mi.getOpcode() == Opcodes.INVOKESPECIAL) return false;
        int argc = Type.getArgumentTypes(mi.desc).length;
        if (onThis(fr[i], argc + 1, fr, m)) return false;   // the current object's own method
        Operand r = operand(fr[i], argc + 1, fr, m, 0);
        List<Object[]> targets = new ArrayList<>();   // {path, object}
        if (r.kind == Kind.FIELD && r.self) {
            Object v = fieldValue(ctxObj, r.name);
            if (v == sample) targets.add(new Object[] {"", sample});
            else if ((aiObject(v) || ctorOwned(ctxObj.getClass(), r.name, v)) && effectivelyFinal(ctxObj.getClass(), r.name)) targets.add(new Object[] {(ctxPath.isEmpty() ? "" : ctxPath + ".") + r.name, v});
        } else if (r.kind == Kind.OWNCALL && ctxPath.isEmpty() && r.name != null && r.name.startsWith("result of get") && r.name.length() > 13) {
            String n = r.name.substring(13);
            String field = Character.toLowerCase(n.charAt(0)) + n.substring(1);
            Object v = fieldValue(sample, field);
            if ((aiObject(v) || ctorOwned(sample.getClass(), field, v)) && effectivelyFinal(sample.getClass(), field)) targets.add(new Object[] {field, v});
        }
        if (targets.isEmpty() && r.kind != Kind.FIELD) {
            // an element of a collection field of the current object, of the call's class
            Class<?> want;
            try {
                want = Class.forName(mi.owner.replace('/', '.'), false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
            for (Class<?> c = ctxObj.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (Field f : declaredFields(c)) {
                    if (f.getName().equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !java.util.Collection.class.isAssignableFrom(f.getType())
                            || !effectivelyFinal(ctxObj.getClass(), f.getName())) continue;
                    Object col = fieldValue(ctxObj, f.getName());
                    if (!(col instanceof java.util.Collection<?> cc)) continue;
                    Object[] els = null;
                    for (int tries = 0; tries < 8 && els == null; tries++)
                        try {
                            els = cc.toArray();
                        } catch (java.util.ConcurrentModificationException e) {
                            // the server thread changed it: again
                        }
                    if (els == null) {
                        if (pass1 && reject == null) reject = "collection " + f.getName() + " kept changing during the analysis";
                        return true;
                    }
                    int k = 0;
                    for (Object el : els) {
                        if (want.isInstance(el) && aiObject(el)) targets.add(new Object[] {(ctxPath.isEmpty() ? "" : ctxPath + ".") + f.getName() + "[" + k + "]", el});
                        k++;
                    }
                }
        }
        if (targets.isEmpty() && r.kind == Kind.FIELD && r.self) {
            Object v = fieldValue(ctxObj, r.name);
            if (v != null && v.getClass().isHidden() && v.getClass().getName().contains("$$Lambda")) return lambdaHost(v.getClass(), mi, depth, seen, pass1);
        }
        if (targets.isEmpty()) return false;
        Set<String> callG = curGuards;
        List<String> recvGuards = new ArrayList<>();
        String sig = fr[i] == null ? null : rvSig(fr[i].getStack(fr[i].getStackSize() - argc - 1), fr[i], m);
        if (sig != null) for (String g : callG) if (g.startsWith("rv:" + sig + ":")) recvGuards.add(g.substring(("rv:" + sig + ":").length()));
        for (Object[] t : targets) {
            Set<String> g2 = callG;
            boolean dead = false;
            if (!recvGuards.isEmpty()) {
                g2 = new HashSet<>(callG);
                for (String n : recvGuards) {
                    String k = "false:" + (((String) t[0]).isEmpty() ? "" : t[0] + ".") + n;
                    dead |= falseCalls.contains(k.substring(6));
                    g2.add(k);
                }
            }
            if (dead) continue;   // this element's call is behind its assumed-false check
            Object obj = t[1];
            Class<?> cls = obj.getClass();
            String decl = ownerOf(internal(cls), mi.name, mi.desc);
            MethodNode target = decl == null ? null : method(decl, mi.name, mi.desc);
            if (target == null || (target.access & Opcodes.ACC_ABSTRACT) != 0) {
                if (pass1 && reject == null) reject = "unresolved " + simple(internal(cls)) + "." + mi.name + " in " + t[0];
                continue;
            }
            String savedPath = ctxPath;
            Object savedObj = ctxObj;
            ctxPath = (String) t[0];
            ctxObj = obj;
            if (!ctxPath.isEmpty()) contexts.add(ctxPath);
            curGuards = g2;
            try {
                if (pass1) follow(decl, target, depth + 1, seen, chainOf(cls), cls, 1);
                else branches(decl, target, depth + 1, seen, chainOf(cls), cls, 1);
            } finally {
                ctxPath = savedPath;
                ctxObj = savedObj;
                curGuards = callG;
            }
        }
        return true;
    }

    /**
     * A call on a lambda held in a field (PanicGoal's damage-type function): its implementation is one of the host
     * class's synthetic lambda methods - all of them are followed (static ones as such, instance ones on an unknown
     * receiver).
     */
    private boolean lambdaHost(Class<?> lambda, MethodInsnNode mi, int depth, Set<String> seen, boolean pass1) throws AnalyzerException {
        String name = lambda.getName();
        String host = name.substring(0, name.indexOf("$$Lambda")).replace('.', '/');
        ClassNode cn = node(host);
        if (cn == null) {
            if (pass1 && reject == null) reject = "lambda host " + simple(host) + " not found for " + mi.name;
            return true;
        }
        for (MethodNode t : cn.methods) {
            if ((t.access & Opcodes.ACC_SYNTHETIC) == 0 || !t.name.startsWith("lambda$")) continue;
            int self = (t.access & Opcodes.ACC_STATIC) != 0 ? 0 : 0;   // an instance lambda's receiver is not a context object
            if (pass1) follow(host, t, depth + 1, seen, chainOf(Object.class), Object.class, self);
            else branches(host, t, depth + 1, seen, chainOf(Object.class), Object.class, self);
        }
        return true;
    }

    private String fkey(String owner, String name) {
        if (entityMode && !ctxPath.isEmpty() && (chain == null || !chain.contains(owner))) return ctxPath + "|" + owner + "." + name;
        if (owner != null && chain != null && chain.contains(owner) && beCls != null) {
            Field f = field(beCls, name);
            if (f != null) return internal(f.getDeclaringClass()) + "." + name;
        }
        return owner + "." + name;
    }

    private boolean entityClass(String internal) {
        try {
            return net.minecraft.world.entity.Entity.class.isAssignableFrom(Class.forName(internal.replace('/', '.'), false, loader));
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private boolean containsClock(Operand o) {
        if (o == null) return false;
        if (isClock(o)) return true;
        return containsClock(o.a) || containsClock(o.b);
    }

    private boolean isClock(Operand o) {
        return o != null && o.kind == Kind.FIELD && (!entityMode || o.self) && clocks.containsKey(keyOf(o));
    }

    /** entity mode: the same field as a clock, read on another instance */
    private boolean peerClock(Operand o) {
        return entityMode && o != null && o.kind == Kind.FIELD && !o.self && clocks.containsKey(fkey(o.owner, o.name));
    }

    private boolean isOwnField(Operand o, Set<String> beChain) {
        if (entityMode && o != null && o.type != null && o.type.length() > 1 && !o.type.startsWith("[") && entityClass(o.type)) return false;
        return o != null && (o.kind == Kind.OWNCALL || o.kind == Kind.FIELD && (!entityMode || o.self) && beChain.contains(o.owner) && !BASE_FIELDS.contains(o.name));
    }

    private static boolean containsGameTime(Operand o) {
        if (o == null) return false;
        if (o.kind == Kind.GAMETIME || o.kind == Kind.GAMEMOD) return true;
        return containsGameTime(o.a) || containsGameTime(o.b);
    }

    /** Types whose instances are registry entries (shared, immutable in practice): RecipeType, Block, Item, SoundEvent, ... */
    private static final Map<String, Boolean> REGISTRY_TYPE = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean registryType(String type) {
        if (type == null || type.length() == 1) return false;
        return REGISTRY_TYPE.computeIfAbsent(type, t -> {
            try {
                Class<?> c = Class.forName(t.replace('/', '.'), false, loader);
                for (var reg : net.minecraft.core.registries.BuiltInRegistries.REGISTRY) {
                    var it = reg.iterator();
                    if (it.hasNext() && c.isInstance(it.next())) return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- operand model

    /** OWNCALL: an object returned by a method of the block entity itself (other than a plain field getter). */
    enum Kind { FIELD, CONST, CONSTSET, GAMETIME, GAMEMOD, ARITH, CMP, CALL, OWNCALL, LOCAL, OTHER, DRAW, FCONST }

    static final class Operand {
        Kind kind = Kind.OTHER;
        String owner, name, type;
        long value;
        long[] values;   // CONSTSET: every constant the value can come from
        double dvalue;   // FCONST
        MethodInsnNode draw;   // DRAW: the call
        boolean self;    // FIELD / OWNCALL: read on this object (not on another instance of the class)
        String ctx;      // FIELD: read on another context's object (its path: "" the entity), null for this one
        int op;
        Operand a, b;

        static Operand constant(long v) {
            Operand o = new Operand();
            o.kind = Kind.CONST;
            o.value = v;
            return o;
        }
    }

    /** Is the stack value {@code fromTop} at frame f an object allocated in this method (NEW, possibly through locals)? */
    private static boolean fresh(Frame<SourceValue> f, int fromTop, Frame<SourceValue>[] frames, MethodNode m, int depth) {
        if (f == null || f.getStackSize() < fromTop || depth > 6) return false;
        return freshValue(f.getStack(f.getStackSize() - fromTop), frames, m, depth);
    }

    private static boolean freshValue(SourceValue v, Frame<SourceValue>[] frames, MethodNode m, int depth) {
        if (v.insns.isEmpty() || depth > 6) return false;
        for (AbstractInsnNode src : v.insns) {
            int op = src.getOpcode();
            Frame<SourceValue> sf = frames[m.instructions.indexOf(src)];
            if (op == Opcodes.NEW) continue;
            if (op == Opcodes.DUP && fresh(sf, 1, frames, m, depth + 1)) continue;
            if (src instanceof VarInsnNode vi && op == Opcodes.ALOAD && sf != null) {
                SourceValue lv = sf.getLocal(vi.var);
                boolean ok = !lv.insns.isEmpty();
                for (AbstractInsnNode st : lv.insns) {
                    Frame<SourceValue> stf = frames[m.instructions.indexOf(st)];
                    if (!(st instanceof VarInsnNode) || st.getOpcode() != Opcodes.ASTORE || !fresh(stf, 1, frames, m, depth + 1)) { ok = false; break; }
                }
                if (ok) continue;
            }
            return false;
        }
        return true;
    }

    /** Is the stack value {@code fromTop} at frame f the object itself (ALOAD 0 in an instance method that never reassigns it)? */
    /** local slots holding the object itself in the method being analysed (slot 0 of instance methods, or params given this) */
    private int selfMask = 1;

    private int calleeMask(Frame<SourceValue> f, MethodInsnNode mi, Frame<SourceValue>[] frames, MethodNode m) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        boolean inst = mi.getOpcode() != Opcodes.INVOKESTATIC;
        int n = args.length + (inst ? 1 : 0), mask = 0, slot = 0;
        for (int k = 0; k < n; k++) {
            if (onThis(f, n - k, frames, m) && slot < 31) mask |= 1 << slot;
            slot += inst && k == 0 ? 1 : args[k - (inst ? 1 : 0)].getSize();
        }
        return mask;
    }

    private boolean onThis(Frame<SourceValue> f, int fromTop, Frame<SourceValue>[] frames, MethodNode m) {
        if (f == null || f.getStackSize() < fromTop) return false;
        SourceValue v = f.getStack(f.getStackSize() - fromTop);
        for (int hops = 0; hops < 4 && v.insns.size() == 1; hops++) {
            AbstractInsnNode d = v.insns.iterator().next();
            int op = d.getOpcode(), at = m.instructions.indexOf(d);
            Frame<SourceValue> df = frames[at];
            if (op == Opcodes.DUP) {
                // DUP's copy: the value it duplicated (top of the stack at the DUP)
                if (df == null || df.getStackSize() < 1) return false;
                v = df.getStack(df.getStackSize() - 1);
            } else if (op == Opcodes.DUP_X1) {
                // [.., v2, v1] -> [.., v1, v2, v1] (javac's x.f++ in an expression): which of the three this value is,
                // by its place in the frame after the DUP_X1, and so which value it was before
                Frame<SourceValue> af = at + 1 < frames.length ? frames[at + 1] : null;
                if (df == null || af == null || df.getStackSize() < 2) return false;
                int s = af.getStackSize(), pos = -1;
                for (int k = s - 3; k < s; k++) if (k >= 0 && af.getStack(k) == v) pos = s - 1 - k;   // 0 = top
                if (pos < 0) return false;
                v = df.getStack(df.getStackSize() - (pos == 1 ? 2 : 1));
            } else if (op == Opcodes.CHECKCAST) {
                // ((ITickableBlockEntity) be): a cast is the same object
                if (df == null || df.getStackSize() < 1) return false;
                v = df.getStack(df.getStackSize() - 1);
            } else break;
        }
        if (v.insns.size() != 1 || !(v.insns.iterator().next() instanceof VarInsnNode vi) || vi.getOpcode() != Opcodes.ALOAD || vi.var >= 31) return false;
        boolean stored = false;
        for (AbstractInsnNode x : m.instructions) if (x instanceof VarInsnNode st && st.getOpcode() == Opcodes.ASTORE && st.var == vi.var) stored = true;
        if ((selfMask & (1 << vi.var)) != 0) return !stored;
        return ALIAS && stored && aliasOfThis(vi.var, frames, m, 0);
    }

    /**
     * A local that only ever holds the object itself ({@code AbstractMinecart mc = this;} in NeoForge's
     * moveMinecartOnRail): every store to it stores this, the slot holds nothing else (a parameter slot is never one).
     */
    /**
     * Such locals are followed (-Dlab.sleep.noAlias=true: not). With them the six minecart kinds become eligible;
     * lag_restcarts (all 128 carts and 64 items asleep) and lag_carts / lag_items keep entity sleep's deep hash equal to
     * entity sleep off (10-30; compared at the same first entity id: item phases follow (tickCount + id) % 4).
     */
    private static final boolean ALIAS = !Boolean.getBoolean("lab.sleep.noAlias");

    private boolean aliasOfThis(int var, Frame<SourceValue>[] frames, MethodNode m, int depth) {
        if (depth > 2) return false;
        int params = (Type.getArgumentsAndReturnSizes(m.desc) >> 2) - ((m.access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
        if (var < params) return false;
        boolean any = false;
        for (AbstractInsnNode x : m.instructions) {
            if (!(x instanceof VarInsnNode st) || st.var != var || st.getOpcode() < Opcodes.ISTORE || st.getOpcode() > Opcodes.ASTORE) continue;
            if (st.getOpcode() != Opcodes.ASTORE) return false;
            Frame<SourceValue> sf = frames[m.instructions.indexOf(st)];
            if (sf == null) continue;   // dead code
            SourceValue sv = sf.getStack(sf.getStackSize() - 1);
            if (sv.insns.size() != 1 || !(sv.insns.iterator().next() instanceof VarInsnNode src) || src.getOpcode() != Opcodes.ALOAD) return false;
            if (src.var == var) continue;
            boolean selfSlot = src.var < 31 && (selfMask & (1 << src.var)) != 0;
            if (!(selfSlot || aliasOfThis(src.var, frames, m, depth + 1))) return false;
            if (selfSlot) for (AbstractInsnNode y : m.instructions)
                if (y instanceof VarInsnNode z && z.getOpcode() == Opcodes.ASTORE && z.var == src.var) return false;
            any = true;
        }
        return any;
    }

    /** The value {@code fromTop} positions from the top of the stack at a frame (1 = top). */
    private Operand operand(Frame<SourceValue> f, int fromTop, Frame<SourceValue>[] frames, MethodNode m, int depth) {
        if (f == null || f.getStackSize() < fromTop) return new Operand();
        return describe(f.getStack(f.getStackSize() - fromTop), frames, m, depth);
    }

    private Operand describe(SourceValue v, Frame<SourceValue>[] frames, MethodNode m, int depth) {
        Operand o = new Operand();
        if (v.insns.size() > 1 && depth <= 4) {
            long[] vals = new long[v.insns.size()];
            int n = 0;
            for (AbstractInsnNode src : v.insns) {
                Operand c = describe(new SourceValue(v.size, src), frames, m, depth + 1);
                if (c.kind != Kind.CONST) return o;
                vals[n++] = c.value;
            }
            o.kind = Kind.CONSTSET;
            o.values = vals;
            return o;
        }
        if (v.insns.size() != 1 || depth > 4) return o;
        AbstractInsnNode src = v.insns.iterator().next();
        int op = src.getOpcode();
        Frame<SourceValue> f = frames[m.instructions.indexOf(src)];
        if (src instanceof FieldInsnNode fi && op == Opcodes.GETFIELD) {
            o.kind = Kind.FIELD;
            o.owner = fi.owner;
            o.name = fi.name;
            o.self = onThis(f, 1, frames, m);
            o.type = Type.getType(fi.desc).getSort() == Type.OBJECT ? Type.getType(fi.desc).getInternalName() : fi.desc;
            if (!o.self && onEntity(f, 1, frames, m) && o.type.length() == 1) {   // the entity's primitive field, from another context
                o.self = true;
                o.ctx = "";
            }
        } else if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            o = Operand.constant(op - Opcodes.ICONST_0);
        } else if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) {
            o = Operand.constant(op - Opcodes.LCONST_0);
        } else if (src instanceof IntInsnNode ii && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
            o = Operand.constant(ii.operand);
        } else if (src instanceof LdcInsnNode l && (l.cst instanceof Integer || l.cst instanceof Long)) {
            o = Operand.constant(((Number) l.cst).longValue());
        } else if (collect && src instanceof LdcInsnNode l && (l.cst instanceof Float || l.cst instanceof Double)) {
            o.kind = Kind.FCONST;
            o.dvalue = ((Number) l.cst).doubleValue();
        } else if (collect && op >= Opcodes.FCONST_0 && op <= Opcodes.DCONST_1) {
            o.kind = Kind.FCONST;
            o.dvalue = op <= Opcodes.FCONST_2 ? op - Opcodes.FCONST_0 : op - Opcodes.DCONST_0;
        } else if (collect && src instanceof MethodInsnNode md && entityDraw(md, f, frames, m)) {
            o.kind = Kind.DRAW;
            o.name = md.name;
            o.draw = md;
        } else if (src instanceof MethodInsnNode mi) {
            if (mi.name.equals("getGameTime") && mi.desc.equals("()J")) o.kind = Kind.GAMETIME;
            else if (chain != null && chain.contains(mi.owner) && op != Opcodes.INVOKESTATIC && Type.getReturnType(mi.desc).getSort() >= Type.ARRAY
                    && (!entityMode || onThis(f, Type.getArgumentTypes(mi.desc).length + 1, frames, m))) {
                // an object handed out by the block entity itself: a plain getter is that field, anything else is owned
                String decl = ownerOf(internal(beCls), mi.name, mi.desc);
                MethodNode t = decl == null ? null : method(decl, mi.name, mi.desc);
                FieldInsnNode got = t == null ? null : getterField(t);
                Type rt = Type.getReturnType(mi.desc);
                if (got != null) {
                    o.kind = Kind.FIELD;
                    o.owner = got.owner;
                    o.name = got.name;
                    o.self = true;
                } else if (entityMode && t != null && handsOutNothingOwned(decl, t)) {
                    o.kind = Kind.CALL;
                    o.name = mi.owner + "." + mi.name;
                } else {
                    o.kind = Kind.OWNCALL;
                    o.name = "result of " + mi.name;
                }
                o.type = rt.getSort() == Type.OBJECT ? rt.getInternalName() : rt.getDescriptor();
            } else if (entityRead(mi, f, frames, m, o)) {
                // the entity's own field through a getter, read from another context (RandomStrollGoal:
                // mob.getNoActionTime() >= 100): a clock there is a condition on the entity's clock
            } else {
                o.kind = Kind.CALL;
                o.name = mi.owner + "." + mi.name;
            }
        } else if (src instanceof VarInsnNode vi && (op == Opcodes.ALOAD || op == Opcodes.LLOAD || op == Opcodes.ILOAD)) {
            // follow the local back to what was stored in it (any owned store makes it owned)
            SourceValue lv = f == null ? null : f.getLocal(vi.var);
            o.kind = Kind.LOCAL;
            if (lv != null) {
                for (AbstractInsnNode st : lv.insns) {
                    if (!(st instanceof VarInsnNode) || st.getOpcode() < Opcodes.ISTORE || st.getOpcode() > Opcodes.ASTORE) continue;
                    Frame<SourceValue> sf = frames[m.instructions.indexOf(st)];
                    Operand stored = sf == null ? null : operand(sf, 1, frames, m, depth + 1);
                    if (stored != null && (stored.kind == Kind.OWNCALL || stored.kind == Kind.FIELD || containsGameTime(stored)
                            || stored.kind == Kind.CONST || stored.kind == Kind.CONSTSET || containsClock(stored))) return stored;
                }
            }
        } else if (op == Opcodes.DUP || op == Opcodes.DUP2 || op == Opcodes.DUP_X1 || op == Opcodes.DUP_X2) {
            if (op == Opcodes.DUP2 && f != null && f.getStackSize() >= 2) {
                Operand lower = operand(f, 2, frames, m, depth + 1);
                if (lower.kind == Kind.FIELD) return lower;
            }
            return operand(f, 1, frames, m, depth + 1);
        } else if (op == Opcodes.LCMP || (op >= Opcodes.IADD && op <= Opcodes.LREM) || collect && op >= Opcodes.FCMPL && op <= Opcodes.DCMPG) {
            o.kind = op == Opcodes.LCMP || op >= Opcodes.FCMPL ? Kind.CMP : Kind.ARITH;
            o.op = op;
            o.a = operand(f, 2, frames, m, depth + 1);
            o.b = operand(f, 1, frames, m, depth + 1);
            if ((op == Opcodes.LREM || op == Opcodes.IREM) && o.a.kind == Kind.GAMETIME && o.b.kind == Kind.CONST) {
                Operand g = new Operand();
                g.kind = Kind.GAMEMOD;
                g.value = o.b.value;
                return g;
            }
        } else if (collect && (op >= Opcodes.I2F && op <= Opcodes.D2F && op != Opcodes.I2L && op != Opcodes.L2I || op == Opcodes.FNEG || op == Opcodes.DNEG
                || op == Opcodes.INEG || op == Opcodes.LNEG)) {
            // a conversion between number types (or a negation): the value it carries, as a one-operand expression
            o.kind = Kind.ARITH;
            o.op = op;
            o.a = operand(f, 1, frames, m, depth + 1);
        } else if (op == Opcodes.I2L || op == Opcodes.L2I || op == Opcodes.I2S || op == Opcodes.I2B || op == Opcodes.I2C) {
            return operand(f, 1, frames, m, depth + 1);
        }
        return o;
    }

    // ---------------------------------------------------------------- class access

    private ClassNode node(String internal) {
        ClassNode n = nodes.get(internal);
        if (n != null || nodes.containsKey(internal)) return n;
        try {
            n = dev.lull.ClassBytes.read(loader, internal, ClassReader.SKIP_FRAMES);   // other mods' mixins as they run (lab.analysis.running)
        } catch (Exception e) {
            n = null;
        }
        nodes.put(internal, n);
        return n;
    }

    /** Is this method changed by another mod's mixin in a way the analysis cannot see (ClassBytes.unseen)? */
    private boolean changed(String owner, String name) {
        if (!MixinTouch.touched(owner, name)) return false;
        node(owner);
        return dev.lull.ClassBytes.unseen(owner, name);
    }

    private final Map<String, Boolean> nothingOwned = new HashMap<>();

    /**
     * Every value the method returns is a new object, null, a static field, a constant or the result of a call outside
     * the class chain (whose result the analysis already treats as foreign): the caller gets nothing the object owns.
     */
    private boolean handsOutNothingOwned(String owner, MethodNode t) {
        String key = owner + "." + t.name + t.desc;
        Boolean b = nothingOwned.get(key);
        if (b != null) return b;
        nothingOwned.put(key, false);   // recursion: conservative
        boolean ok;
        try {
            Frame<SourceValue>[] fr = dev.lull.ClassBytes.analyze(new Analyzer<>(new SourceInterpreter()), owner, t);
            ok = true;
            AbstractInsnNode[] in = t.instructions.toArray();
            for (int i = 0; i < in.length && ok; i++) {
                if (in[i].getOpcode() != Opcodes.ARETURN || fr[i] == null) continue;
                SourceValue v = fr[i].getStack(fr[i].getStackSize() - 1);
                ok = foreignValue(v, fr, t, 0);
            }
        } catch (AnalyzerException | RuntimeException e) {
            ok = false;
        }
        nothingOwned.put(key, ok);
        return ok;
    }

    private boolean foreignValue(SourceValue v, Frame<SourceValue>[] fr, MethodNode t, int depth) {
        if (v.insns.isEmpty() || depth > 6) return false;
        for (AbstractInsnNode src : v.insns) {
            int op = src.getOpcode();
            if (op == Opcodes.NEW || op == Opcodes.ACONST_NULL || op == Opcodes.GETSTATIC || op == Opcodes.LDC) continue;
            if (src instanceof MethodInsnNode mi && !chain.contains(mi.owner)) continue;
            if (op == Opcodes.CHECKCAST || op == Opcodes.DUP) {
                Frame<SourceValue> f = fr[t.instructions.indexOf(src)];
                if (f != null && foreignValue(f.getStack(f.getStackSize() - 1), fr, t, depth + 1)) continue;
            }
            if (src instanceof VarInsnNode vi && op == Opcodes.ALOAD) {
                Frame<SourceValue> f = fr[t.instructions.indexOf(src)];
                if (f != null && !(vi.var == 0 && (t.access & Opcodes.ACC_STATIC) == 0)) {
                    SourceValue lv = f.getLocal(vi.var);
                    boolean ok = !lv.insns.isEmpty();
                    for (AbstractInsnNode st : lv.insns) {
                        Frame<SourceValue> sf = fr[t.instructions.indexOf(st)];
                        if (!(st instanceof VarInsnNode) || sf == null || !foreignValue(sf.getStack(sf.getStackSize() - 1), fr, t, depth + 1)) { ok = false; break; }
                    }
                    if (ok) continue;
                }
            }
            return false;
        }
        return true;
    }

    /** ALOAD 0; GETFIELD f; xRETURN (ignoring labels and line numbers) -> f */
    private static FieldInsnNode getterField(MethodNode t) {
        AbstractInsnNode[] real = java.util.Arrays.stream(t.instructions.toArray()).filter(x -> x.getOpcode() >= 0).toArray(AbstractInsnNode[]::new);
        if (real.length == 3 && real[0] instanceof VarInsnNode a && a.getOpcode() == Opcodes.ALOAD && a.var == 0
                && real[1] instanceof FieldInsnNode g && g.getOpcode() == Opcodes.GETFIELD && real[2].getOpcode() >= Opcodes.IRETURN && real[2].getOpcode() <= Opcodes.ARETURN)
            return g;
        return null;
    }

    private static final MethodNode NO_METHOD = new MethodNode();
    private final Map<String, MethodNode> methodCache = new HashMap<>();
    private final Map<String, String> ownerCache = new HashMap<>();

    private MethodNode method(String owner, String name, String desc) {
        String key = owner + '.' + name + desc;
        MethodNode r = methodCache.get(key);
        if (r == null) {
            ClassNode cn = node(owner);
            r = NO_METHOD;
            if (cn != null) for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) { r = m; break; }
            methodCache.put(key, r);
        }
        return r == NO_METHOD ? null : r;
    }

    private MethodNode resolve(String owner, String name, String desc) {
        String o = ownerOf(owner, name, desc);
        return o == null ? null : method(o, name, desc);
    }

    /** Declaring class of a method: superclass chain first, then (default methods) all interfaces. */
    private String ownerOf(String owner, String name, String desc) {
        String key = owner + '.' + name + desc;
        String r = ownerCache.get(key);
        if (r == null) {
            String savedReject = reject;
            reject = null;
            r = ownerOf0(owner, name, desc);
            String rej = reject;
            reject = savedReject;
            r = rej != null ? "\u0000" + rej : r == null ? "\u0000" : r;
            ownerCache.put(key, r);
        }
        if (r.startsWith("\u0000")) {
            if (r.length() > 1 && reject == null) reject = r.substring(1);   // the mixin-changed refusal, every time
            return null;
        }
        return r;
    }

    private String ownerOf0(String owner, String name, String desc) {
        List<String> ifaces = new ArrayList<>();
        for (String c = owner; c != null; ) {
            if (changed(c, name)) {   // a mixin may add or change this method here: resolution unknown
                if (reject == null) reject = "mixin-changed " + simple(c) + "." + name;
                return null;
            }
            MethodNode found = method(c, name, desc);
            if (found != null && (found.access & Opcodes.ACC_ABSTRACT) == 0) return c;
            ClassNode cn = node(c);
            if (cn == null) break;
            ifaces.addAll(cn.interfaces);
            c = cn.superName;
        }
        Set<String> seen = new HashSet<>();
        while (!ifaces.isEmpty()) {
            String i = ifaces.remove(ifaces.size() - 1);
            if (!seen.add(i)) continue;
            if (changed(i, name)) {
                if (reject == null) reject = "mixin-changed " + simple(i) + "." + name;
                return null;
            }
            MethodNode m = method(i, name, desc);
            if (m != null && (m.access & Opcodes.ACC_ABSTRACT) == 0) return i;
            ClassNode cn = node(i);
            if (cn != null) ifaces.addAll(cn.interfaces);
        }
        return null;
    }

    private static Field field(Class<?> c, String name) {
        Field f = fieldIn(c, name);
        if (f == null || Modifier.isStatic(f.getModifiers())) return null;
        f.setAccessible(true);
        return f;
    }

    static String internal(Class<?> c) {
        return c.getName().replace('.', '/');
    }

    static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }
}
