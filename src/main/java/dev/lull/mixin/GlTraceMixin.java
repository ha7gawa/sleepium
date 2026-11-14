package dev.lull.mixin;

import com.mojang.blaze3d.platform.GlDebug;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.KHRDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only (-Dsleep.glTrace=true): which Java call made a GL error. After vanilla's debug callback is set up,
 * the debug output is made synchronous and our own callback takes its place: an error message is reported with the
 * Java stack of the GL call that raised it (the callback runs inside that call), the first 3 times per distinct
 * stack, every other error counted. Drivers that name no function in the message (Intel: "in (null)") are why.
 */
@Mixin(GlDebug.class)
public abstract class GlTraceMixin {
    @Unique private static GLDebugMessageCallback sleep$callback;
    @Unique private static final Map<String, int[]> PROBE$SEEN = new HashMap<>();

    @Inject(method = "enableDebugCallback", at = @At("TAIL"))
    private static void sleep$trace(int verbosity, boolean synchronous, CallbackInfo ci) {
        GL11.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
        if (!Boolean.getBoolean("sleep.glTrace.async")) GL11.glEnable(KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        // every message kind on (vanilla's verbosity may have filtered some out)
        KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, (java.nio.IntBuffer) null, true);
        sleep$callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
            if (type != KHRDebug.GL_DEBUG_TYPE_ERROR) {
                if (dev.lull.optsupport.StallProbe.ON && com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread())
                    dev.lull.optsupport.GlFrame.msg(type, severity, id, GLDebugMessageCallback.getMessage(length, message));
                return;
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String c = e.getClassName();
                if (c.startsWith("java.") || c.startsWith("org.lwjgl.") || c.contains("GlTraceMixin")) continue;
                sb.append(System.lineSeparator()).append("   ").append(c).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
                if (++n >= 12) break;
            }
            String key = sb.toString();
            int[] seen;
            synchronized (PROBE$SEEN) {
                seen = PROBE$SEEN.computeIfAbsent(key, k -> new int[1]);
                seen[0]++;
            }
            if (seen[0] <= 3 || Integer.bitCount(seen[0]) == 1)
                System.out.println("[sleep/gltrace] #" + seen[0] + " on " + Thread.currentThread().getName() + ": id " + id + " "
                        + GLDebugMessageCallback.getMessage(length, message) + key + (seen[0] == 1 ? sleep$state() : ""));
        });
        KHRDebug.glDebugMessageCallback(sleep$callback, 0L);
        System.out.println("[sleep/gltrace] synchronous GL debug output with Java stacks");
    }

    @Unique private static boolean sleep$inState;

    /**
     * The draw state at the first error of a stack: the program's validation log (the driver's reason a draw is
     * refused) and the bindings a draw reads. GL calls inside the callback are outside the spec, so only for this
     * measurement, and not re-entered when they raise errors themselves.
     */
    @Unique
    private static String sleep$state() {
        if (sleep$inState || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return "";   // a worker's context: no LWJGL capabilities there
        sleep$inState = true;
        try {
            int prog = GL11.glGetInteger(org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM);
            String log = "";
            int valid = -1;
            if (prog != 0) {
                org.lwjgl.opengl.GL20C.glValidateProgram(prog);
                valid = org.lwjgl.opengl.GL20C.glGetProgrami(prog, org.lwjgl.opengl.GL20C.GL_VALIDATE_STATUS);
                log = org.lwjgl.opengl.GL20C.glGetProgramInfoLog(prog).trim();
            }
            int vao = GL11.glGetInteger(org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING);
            int ebo = GL11.glGetInteger(org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            int ind = GL11.glGetInteger(org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING);
            String sizes = "";
            if (ebo != 0) sizes += " ebo size " + org.lwjgl.opengl.GL15C.glGetBufferParameteri(org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER, org.lwjgl.opengl.GL15C.GL_BUFFER_SIZE)
                    + " mapped " + org.lwjgl.opengl.GL15C.glGetBufferParameteri(org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER, org.lwjgl.opengl.GL15C.GL_BUFFER_MAPPED);
            if (ind != 0) sizes += " indirect size " + org.lwjgl.opengl.GL15C.glGetBufferParameteri(org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER, org.lwjgl.opengl.GL15C.GL_BUFFER_SIZE);
            StringBuilder ssbo = new StringBuilder();
            for (int b = 0; b < 8; b++) {
                int name = org.lwjgl.opengl.GL30C.glGetIntegeri(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, b);
                if (name != 0) ssbo.append(' ').append(b).append('=').append(name).append('/')
                        .append(org.lwjgl.opengl.GL32C.glGetInteger64i(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, b));
            }
            return System.lineSeparator() + "   state: program " + prog + " valid " + valid + " vao " + vao + " ebo " + ebo + " indirect " + ind
                    + sizes + " ssbo" + ssbo + System.lineSeparator() + "   validate log: " + (log.isEmpty() ? "-" : log.replace('\n', ' '));
        } catch (Throwable t) {
            return System.lineSeparator() + "   state: " + t;
        } finally {
            sleep$inState = false;
        }
    }
}
