package dev.lab.runtime;

/**
 * OS-level thread placement through JNA (Minecraft ships it): Windows SetThreadPriority; Linux setpriority on the thread
 * (a nice value - raising above normal needs privileges, so ABOVE_NORMAL usually stays normal there, while BELOW_NORMAL
 * works: our pools step back); macOS a thread QoS class (user-interactive / default / utility, no privileges needed).
 * Without JNA: Java thread priorities (HotSpot maps them on Windows only).
 */
public final class OsThreads {
    public static final int BELOW_NORMAL = -1, NORMAL = 0, ABOVE_NORMAL = 1;

    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS.contains("win"), LINUX = OS.contains("linux"), MAC = OS.contains("mac");
    private static final Object K32 = loadKernel32();
    private static final Object LIBC = loadLibc();

    private OsThreads() {}

    public interface Kernel32 extends com.sun.jna.Library {
        com.sun.jna.Pointer GetCurrentThread();
        boolean SetThreadPriority(com.sun.jna.Pointer thread, int priority);
        long SetThreadAffinityMask(com.sun.jna.Pointer thread, long mask);
        int GetCurrentProcessorNumber();
    }

    /** Linux libc: setpriority(PRIO_PROCESS, tid, nice) acts on one thread; the tid from syscall(SYS_gettid) */
    public interface LinuxC extends com.sun.jna.Library {
        int setpriority(int which, int who, int prio);
        int syscall(int number);
    }

    /** macOS libSystem: the calling thread's QoS class */
    public interface MacC extends com.sun.jna.Library {
        int pthread_set_qos_class_self_np(int qosClass, int relativePriority);
    }

    private static Object loadKernel32() {
        if (!WINDOWS || Boolean.getBoolean("lab.runtime.noJna")) return null;
        try {
            return com.sun.jna.Native.load("kernel32", Kernel32.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object loadLibc() {
        if (Boolean.getBoolean("lab.runtime.noJna")) return null;
        try {
            if (LINUX) return com.sun.jna.Native.load("c", LinuxC.class);
            if (MAC) return com.sun.jna.Native.load("System", MacC.class);
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    public static boolean nativeAvailable() {
        return K32 != null || LIBC != null;
    }

    /** SYS_gettid by architecture (x86-64 186, aarch64 / riscv64 178, x86 224); -1 when unknown */
    private static int gettidNumber() {
        String arch = System.getProperty("os.arch", "");
        if (arch.equals("amd64") || arch.equals("x86_64")) return 186;
        if (arch.equals("aarch64") || arch.equals("riscv64")) return 178;
        if (arch.equals("x86") || arch.equals("i386")) return 224;
        return -1;
    }

    /** Call from the thread itself. True when the OS took it (a refused raise on Linux is false, the thread stays as it was). */
    public static boolean setCurrentPriority(int osPriority) {
        try {
            if (K32 != null) {
                Kernel32 k = (Kernel32) K32;
                return k.SetThreadPriority(k.GetCurrentThread(), osPriority);
            }
            if (LIBC instanceof LinuxC c) {
                int nr = gettidNumber();
                if (nr >= 0) {
                    int tid = c.syscall(nr);
                    // nice: -5 above normal (needs CAP_SYS_NICE or a raised RLIMIT_NICE), +5 below
                    return tid > 0 && c.setpriority(0 /* PRIO_PROCESS */, tid, osPriority > 0 ? -5 : osPriority < 0 ? 5 : 0) == 0;
                }
            }
            if (LIBC instanceof MacC c)   // QOS_CLASS_USER_INTERACTIVE 0x21, DEFAULT 0x15, UTILITY 0x11
                return c.pthread_set_qos_class_self_np(osPriority > 0 ? 0x21 : osPriority < 0 ? 0x11 : 0x15, 0) == 0;
        } catch (Throwable t) {
            // the native call is not there: Java's priority below
        }
        Thread.currentThread().setPriority(switch (osPriority) {
            case BELOW_NORMAL -> Thread.NORM_PRIORITY - 1;
            case ABOVE_NORMAL -> Thread.NORM_PRIORITY + 1;
            default -> Thread.NORM_PRIORITY;
        });
        return false;
    }

    /** Call from the thread itself. mask 0 = leave as is. Returns false if not supported. */
    public static boolean pinCurrent(long affinityMask) {
        if (affinityMask == 0 || K32 == null) return false;
        Kernel32 k = (Kernel32) K32;
        return k.SetThreadAffinityMask(k.GetCurrentThread(), affinityMask) != 0;
    }

    public static int currentCpu() {
        return K32 != null ? ((Kernel32) K32).GetCurrentProcessorNumber() : -1;
    }
}
