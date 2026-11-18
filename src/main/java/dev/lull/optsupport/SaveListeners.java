package dev.lull.optsupport;

import java.util.Set;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * -Dlab.asyncsave.listeners (11-18): AsyncChunkSave defers a chunk's palette encodes only while nobody listens to
 * ChunkDataEvent.Save (a listener sees the chunk tag, whose palettes are placeholders until the pool fills them). With
 * Immersive Engineering or Mekanism installed - both mark retrogen in the tag - every save went back to the main
 * thread (the dense modded scene: chunk saving ~60 % of the server thread). Their listeners only put their own keys.
 *
 * <p>Here every listener of the event must be a @SubscribeEvent method of a registered object or class, taking the Save
 * event itself, whose bytecode (as it runs) only reads and writes the tag by constant keys that are none of the chunk
 * format's own, through CompoundTag's single-key methods (no key set, size, copy, merge, remove, no list of the tag), and
 * hands neither the tag nor the event to anything else (no other call, field, array or lambda taking them). Then a
 * listener never sees a placeholder. Any listener not found that way (a lambda, a supertype event, a call it makes with
 * the tag) and the saves stay vanilla, as before. Decided again whenever the event's listener array changes.
 */
public final class SaveListeners {
    private SaveListeners() {}

    public static final boolean ON = Boolean.getBoolean("lab.asyncsave.listeners");

    /** keys of the chunk format and of its sections, and the old ones ("Level") a listener could still look into */
    private static final Set<String> CHUNK_KEYS = Set.of("DataVersion", "xPos", "yPos", "zPos", "LastUpdate", "InhabitedTime", "Status",
            "blending_data", "below_zero_retrogen", "UpgradeData", "sections", "isLightOn", "block_entities", "CarvingMasks", "PostProcessing",
            "Heightmaps", "block_ticks", "fluid_ticks", "structures", "Lights", "entities", "Entities", "block_states", "biomes", "Y",
            "BlockLight", "SkyLight", "palette", "data", "Palette", "BlockStates", "Biomes", "Sections", "TileEntities", "TileTicks",
            "LiquidTicks", "ToBeTicked", "LiquidsToBeTicked", "Structures");
    private static final Set<String> TAG_OK = Set.of("<init>", "put", "putInt", "putBoolean", "putString", "putLong", "putByte", "putShort",
            "putFloat", "putDouble", "putIntArray", "putLongArray", "putByteArray", "putUUID", "getCompound", "getInt", "getBoolean",
            "getString", "getLong", "getByte", "getShort", "getFloat", "getDouble", "getIntArray", "getLongArray", "getByteArray",
            "getUUID", "hasUUID", "contains");
    private static final String TAG = "net/minecraft/nbt/CompoundTag", SAVE = "net/neoforged/neoforge/event/level/ChunkDataEvent$Save";

    private static EventListener[] decidedFor;
    private static boolean decided;
    public static String why = "";

    /** every listener of ChunkDataEvent.Save keeps to its own keys (cached per listener array) */
    public static boolean onlyOwnKeys(CompositeEventBus cb) {
        EventListener[] ls = cb.listenersOf(ChunkDataEvent.Save.class);
        if (ls == null) return false;
        if (ls == decidedFor) return decided;
        boolean d;
        try {
            d = decide(cb, ls);
        } catch (Throwable t) {
            why = "analysis failed: " + t;
            d = false;
        }
        decided = d;
        decidedFor = ls;
        System.out.println("[sleep/opt] chunk save listeners: " + (d ? "own keys only - saves stay deferred" : "saves vanilla (" + why + ")"));
        return d;
    }

    private static boolean decide(CompositeEventBus cb, EventListener[] ls) throws java.io.IOException {
        int listeners = 0;
        listeners = ls.length;   // (NeoForge's list holds listeners only, no priority markers)
        int found = 0;
        String saveDesc = "(L" + SAVE + ";)V";
        Set<String> supers = Set.of("Lnet/neoforged/bus/api/Event;", "Lnet/neoforged/neoforge/event/level/LevelEvent;", "Lnet/neoforged/neoforge/event/level/ChunkEvent;", "Lnet/neoforged/neoforge/event/level/ChunkDataEvent;");
        for (Object target : cb.registered()) {
            Class<?> c = target instanceof Class<?> k ? k : target.getClass();
            boolean statics = target instanceof Class<?>;
            ClassLoader loader = c.getClassLoader();
            // the class chain read as bytecode (no reflection: a method's signature may name a class of a mod not
            // installed - getDeclaredMethods would throw)
            for (String internal = c.getName().replace('.', '/'); internal != null && !internal.equals("java/lang/Object"); ) {
                ClassNode cn = dev.lull.ClassBytes.read(loader, internal, ClassReader.SKIP_FRAMES);
                if (cn == null) {
                    why = "no bytes of " + internal;
                    return false;
                }
                for (MethodNode mn : cn.methods) {
                    if (mn.visibleAnnotations == null || mn.visibleAnnotations.stream().noneMatch(a -> a.desc.equals("Lnet/neoforged/bus/api/SubscribeEvent;"))) continue;
                    if (((mn.access & Opcodes.ACC_STATIC) != 0) != statics) continue;
                    org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mn.desc);
                    if (args.length != 1) continue;
                    String a = args[0].getDescriptor();
                    if (mn.desc.equals(saveDesc)) {
                        found++;
                        String r = check(cn, mn);
                        if (r != null) {
                            why = internal + "." + mn.name + ": " + r;
                            return false;
                        }
                    } else if (supers.contains(a)) {
                        why = internal + "." + mn.name + " listens to " + a;
                        return false;
                    }
                }
                internal = cn.superName;
            }
        }
        if (found != listeners) {
            why = found + " listener methods found for " + listeners + " listeners (a lambda or a generated one)";
            return false;
        }
        why = found + " listeners";
        return true;
    }

    /** null when the method only uses the tag by its own constant keys; else why not */
    private static String check(ClassNode cn, MethodNode mn) {
        {
            for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof LdcInsnNode ldc && ldc.cst instanceof String s && CHUNK_KEYS.contains(s)) return "uses the key " + s;
                if (in instanceof MethodInsnNode mi) {
                    if (mi.owner.equals(TAG)) {
                        if (!TAG_OK.contains(mi.name)) return "calls CompoundTag." + mi.name;
                        continue;
                    }
                    if (mi.owner.equals(SAVE) || mi.owner.startsWith("net/neoforged/neoforge/event/level/") || mi.owner.equals("net/neoforged/bus/api/Event")) {
                        if (mi.name.equals("getData") || mi.name.equals("getChunk") || mi.name.equals("getLevel") || mi.name.equals("getType")) continue;
                        return "calls " + mi.owner + "." + mi.name;
                    }
                    if (mi.desc.contains("Lnet/minecraft/nbt/") || mi.desc.contains("Lnet/neoforged/neoforge/event/"))
                        return "hands the tag or the event to " + mi.owner + "." + mi.name;
                }
                if (in instanceof InvokeDynamicInsnNode id && (id.desc.contains("Lnet/minecraft/nbt/") || id.desc.contains("Lnet/neoforged/neoforge/event/")))
                    return "a lambda takes the tag or the event";
                if (in instanceof FieldInsnNode fi && (fi.getOpcode() == Opcodes.PUTFIELD || fi.getOpcode() == Opcodes.PUTSTATIC)
                        && (fi.desc.startsWith("Lnet/minecraft/nbt/") || fi.desc.startsWith("Lnet/neoforged/neoforge/event/")))
                    return "keeps the tag or the event in a field";
                if (in.getOpcode() == Opcodes.AASTORE) return "stores into an array";
            }
            return null;
        }
    }
}
