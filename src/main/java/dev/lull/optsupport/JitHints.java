package dev.lull.optsupport;

/**
 * Compiler hints at run time (HotSpot's compiler directives, the DiagnosticCommand MBean - what jcmd
 * Compiler.directives_add does): methods the JIT must not inline into their callers.
 *
 * SpecTicks: while speculative runs tick, the write barrier's transaction branch is taken at every write and collection
 * mutation of an entity tick, so C2 inlines the transaction code (SpecTx, SpecUndo, WB.hit) into vanilla's hot methods;
 * those stay compiled so after the runs, and the plain ticks of the same code were ~10 % slower (farm, notes/99: 65 vs
 * 59 ms on the Xeons; 60 with these methods kept out of line). Out of line they cost a call each, in runs only.
 * On a JVM without directives (not HotSpot, or the MBean missing): nothing happens.
 */
public final class JitHints {
    private JitHints() {}

    private static boolean applied;

    /** patterns as directives write them: package/Class.method, '*' at either end of each part */
    public static synchronized void dontInline(String... patterns) {
        if (applied || patterns.length == 0) return;
        applied = true;
        java.nio.file.Path f = null;
        try {
            StringBuilder sb = new StringBuilder("[{ match: \"*.*\", inline: [");
            for (int i = 0; i < patterns.length; i++) sb.append(i == 0 ? "" : ", ").append("\"-").append(patterns[i]).append('"');
            sb.append("] }]");
            f = java.nio.file.Files.createTempFile("sleepium-jit", ".json");
            java.nio.file.Files.writeString(f, sb.toString());
            var server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            var name = new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand");
            Object out = server.invoke(name, "compilerDirectivesAdd", new Object[] {new String[] {f.toString()}}, new String[] {String[].class.getName()});
            System.out.println("[sleep/jit] not inlined: " + String.join(", ", patterns) + (out == null || out.toString().isBlank() ? "" : " (" + out.toString().trim() + ")"));
        } catch (Throwable t) {
            System.out.println("[sleep/jit] compiler directives not available: " + t);
        } finally {
            if (f != null) try {
                java.nio.file.Files.deleteIfExists(f);
            } catch (java.io.IOException ignored) {
            }
        }
    }
}
