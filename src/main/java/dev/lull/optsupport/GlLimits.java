package dev.lull.optsupport;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;

/**
 * What the driver allows beyond its version string. Android launchers' GL translators report a desktop version and
 * extensions that their GLES device cannot always back: LTW on an Adreno 640 says GL 3.3 with
 * ARB_shader_storage_buffer_object, and a vertex shader reading a storage buffer did not compile there (1.2.0: the
 * particle shader stopped the game). The vertex stage's own limit tells.
 */
public final class GlLimits {
    private GlLimits() {}

    private static int vertexSsbo = -1;

    /** storage blocks a vertex shader may read: 0 without GL 4.3 / ARB_shader_storage_buffer_object (render thread) */
    public static int vertexStorageBlocks() {
        int v = vertexSsbo;
        if (v >= 0) return v;
        var caps = GL.getCapabilities();
        if (!(caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object)) {
            v = 0;
        } else {
            v = GL11C.glGetInteger(0x90D6);   // GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) v = Math.max(0, v);   // an unknown enum on a translator: no limit read
            if (v < 0) v = 0;
        }
        String plain = null, shader5 = null;
        if (v > 0) {   // the limit says yes: a test compile of what the features write (a translator may still refuse it)
            plain = compiles(TEST);
            shader5 = plain == null ? compiles(TEST.replace("#extension GL_ARB_shader_storage_buffer_object : require",
                    "#extension GL_ARB_shader_storage_buffer_object : require\n#extension GL_ARB_gpu_shader5 : enable\n"
                            + "#ifdef GL_ARB_gpu_shader5\n#define PROBE_PRECISE precise\n#else\n#define PROBE_PRECISE\n#endif")
                    .replace("vec3 p = ", "PROBE_PRECISE float x = b[gl_InstanceID].pos.x * 2.0;\n    vec3 p = vec3(x) + ")) : null;
            if (plain != null) v = 0;
            gpuShader5 = plain == null && shader5 == null;
        }
        vertexSsbo = v;
        System.out.println("[sleep/opt] GL " + GL11C.glGetString(GL11C.GL_VERSION) + " (" + GL11C.glGetString(GL11C.GL_RENDERER)
                + "): storage blocks in a vertex shader " + v
                + (plain != null ? " (a test shader reading one did not compile: " + plain + ")" : "")
                + (plain == null && shader5 != null ? " (with gpu_shader5's precise it did not compile: " + shader5 + ")" : ""));
        return v;
    }

    /** the test compile passed with gpu_shader5 / precise too */
    static boolean gpuShader5;

    private static final String TEST = "#version 150\n#extension GL_ARB_shader_storage_buffer_object : require\n"
            + "in vec3 Position;\n"
            + "struct ProbeTest { vec4 pos; vec4 uv; ivec4 misc; };\n"
            + "layout(std430) buffer ProbeTests { ProbeTest probeTests[]; };\n"
            + "layout(std430) buffer ProbeTestCorners { vec4 probeCorners[]; };\n"
            + "out vec2 texCoord0;\n"
            + "void main() {\n"
            + "    ProbeTest b0 = probeTests[gl_InstanceID];\n"
            + "    vec3 p = Position + b0.pos.xyz + probeCorners[b0.misc.z * 4 + (gl_VertexID & 3)].xyz;\n"
            + "    texCoord0 = b0.uv.xy;\n"
            + "    gl_Position = vec4(p, 1.0);\n"
            + "}\n";

    /** null when the vertex shader compiles; else its log (or "no log") */
    private static String compiles(String src) {
        int sh = org.lwjgl.opengl.GL20C.glCreateShader(org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER);
        try {
            org.lwjgl.opengl.GL20C.glShaderSource(sh, src.replace("b[gl_InstanceID]", "probeTests[gl_InstanceID]"));
            org.lwjgl.opengl.GL20C.glCompileShader(sh);
            if (org.lwjgl.opengl.GL20C.glGetShaderi(sh, org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS) != 0) return null;
            String log = org.lwjgl.opengl.GL20C.glGetShaderInfoLog(sh, 2048).trim();
            return log.isEmpty() ? "no log" : log.lines().limit(2).reduce((x, y) -> x + " | " + y).orElse(log);
        } catch (Throwable t) {
            return t.toString();
        } finally {
            org.lwjgl.opengl.GL20C.glDeleteShader(sh);
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { /* the test leaves no error behind */ }
        }
    }
}
