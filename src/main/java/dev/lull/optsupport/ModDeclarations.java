package dev.lull.optsupport;

/** Fabric (lab): reading other mods' mods.toml declarations is not in the Fabric build; what other classes read stays, off. */
public final class ModDeclarations {
    private ModDeclarations() {}

    public static void read(Object declared, Object declaredBy) {}
}
