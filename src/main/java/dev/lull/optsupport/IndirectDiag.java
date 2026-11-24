package dev.lull.optsupport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;

/**
 * Measurement only (-Dsleep.glTrace=true): an indirect multi-draw that raised a GL error is tried again with one thing
 * changed at a time (each vertex array off, the storage buffers unbound, one command, the single-draw form), and the
 * commands, the element buffer and each array's buffer are reported, so a driver that only says "Generic error"
 * (Intel) still tells which input it refuses. The first 2 failing draws per call site; state is restored after.
 * Passive by default (issue #11): only GL state is read and reported, nothing drawn or bound for good; the draws tried
 * again, the storage buffers unbound and the self test (it writes into the game's command buffer) only with
 * -Dsleep.glTrace.retry=true.
 */
public final class IndirectDiag {
    public static final boolean ON = Boolean.getBoolean("sleep.glTrace");
    /** the active probes: draws tried again, storage buffers rebound, the self test */
    static final boolean RETRY = Boolean.getBoolean("sleep.glTrace.retry");
    private static final Map<String, int[]> SEEN = new HashMap<>();

    private IndirectDiag() {}

    /** Right after glMultiDrawElementsIndirect(GL_TRIANGLES, type, offset, n, stride) on the render thread. */
    public static void after(String site, int type, long offset, int n, int stride) {
        int e = GL11C.glGetError();
        if (e == 0) return;
        int[] seen = SEEN.computeIfAbsent(site, k -> new int[1]);
        if (++seen[0] > 2) return;
        StringBuilder sb = new StringBuilder("[sleep/indirect] ").append(site).append(": error 0x").append(Integer.toHexString(e))
                .append(" type 0x").append(Integer.toHexString(type)).append(" offset ").append(offset).append(" n ").append(n).append(" stride ").append(stride);
        int ind = GL11C.glGetInteger(GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING);
        int indSize = ind == 0 ? 0 : GL15C.glGetBufferParameteri(GL40C.GL_DRAW_INDIRECT_BUFFER, GL15C.GL_BUFFER_SIZE);
        int ebo = GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int eboSize = ebo == 0 ? 0 : GL15C.glGetBufferParameteri(GL15C.GL_ELEMENT_ARRAY_BUFFER, GL15C.GL_BUFFER_SIZE);
        sb.append("\n   indirect buffer ").append(ind).append(" size ").append(indSize).append(", element buffer ").append(ebo).append(" size ").append(eboSize);
        int show = Math.min(n, 3);
        if (ind != 0 && offset + 20L * show <= indSize) {
            ByteBuffer cmd = ByteBuffer.allocateDirect(20 * show).order(ByteOrder.nativeOrder());
            GL15C.glGetBufferSubData(GL40C.GL_DRAW_INDIRECT_BUFFER, offset, cmd);
            for (int i = 0; i < show; i++)
                sb.append("\n   command ").append(i).append(": count ").append(cmd.getInt(20 * i)).append(" instances ").append(cmd.getInt(20 * i + 4))
                        .append(" first ").append(cmd.getInt(20 * i + 8)).append(" baseVertex ").append(cmd.getInt(20 * i + 12)).append(" baseInstance ").append(cmd.getInt(20 * i + 16));
        }
        int maxAttribs = GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_ATTRIBS);
        int arrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        for (int a = 0; a < maxAttribs; a++) {
            if (GL20C.glGetVertexAttribi(a, GL20C.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0) continue;
            int buf = GL20C.glGetVertexAttribi(a, GL15C.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
            int size = 0;
            if (buf != 0) {
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buf);
                size = GL15C.glGetBufferParameteri(GL15C.GL_ARRAY_BUFFER, GL15C.GL_BUFFER_SIZE);
            }
            sb.append("\n   attrib ").append(a).append(": buffer ").append(buf).append(" size ").append(size)
                    .append(" comps ").append(GL20C.glGetVertexAttribi(a, GL20C.GL_VERTEX_ATTRIB_ARRAY_SIZE))
                    .append(" type 0x").append(Integer.toHexString(GL20C.glGetVertexAttribi(a, GL20C.GL_VERTEX_ATTRIB_ARRAY_TYPE)))
                    .append(" int ").append(GL20C.glGetVertexAttribi(a, GL30C.GL_VERTEX_ATTRIB_ARRAY_INTEGER))
                    .append(" stride ").append(GL20C.glGetVertexAttribi(a, GL20C.GL_VERTEX_ATTRIB_ARRAY_STRIDE))
                    .append(" divisor ").append(GL20C.glGetVertexAttribi(a, org.lwjgl.opengl.GL33C.GL_VERTEX_ATTRIB_ARRAY_DIVISOR))
                    .append(RETRY ? " -> off: " + without(a, type, offset, n, stride) : "");
        }
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer);
        if (!RETRY) {
            System.out.println(sb.append("\n   (passive report; -Dsleep.glTrace.retry=true tries the draw again in other forms)"));
            return;
        }
        sb.append("\n   again: ").append(name(draw(type, offset, n, stride)));
        sb.append(", one command: ").append(name(draw(type, offset, 1, stride)));
        GL11C.glGetError();
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
        sb.append(", DrawElementsIndirect: ").append(name(GL11C.glGetError()));
        int s6 = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, 6), s7 = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, 7);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, 0);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 7, 0);
        sb.append(", storage 6/7 unbound: ").append(name(draw(type, offset, n, stride)));
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, s6);   // ranges come back as whole buffers: diagnostics only
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 7, s7);
        GL11C.glGetError();
        if (!selfTested) {
            selfTested = true;
            sb.append("\n   self test: ").append(selfTest(type));
        }
        System.out.println(sb);
    }

    private static boolean selfTested;

    /**
     * In the game's context, a draw from objects of our own (a plain GLSL 150 program, a VAO with one array, an element
     * buffer, an indirect buffer), then with the game's element buffer, VAO or program in their place: whether the
     * context refuses every indirect draw or only ours. Draws 2 degenerate triangles (nothing visible).
     */
    private static String selfTest(int gameType) {
        int prog = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), vao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int ebo = GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING), ind = GL11C.glGetInteger(GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING);
        int arr = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        StringBuilder sb = new StringBuilder();
        int vs = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER), fs = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
        GL20C.glShaderSource(vs, "#version 150\nin vec3 Position;\nvoid main() { gl_Position = vec4(Position, 1.0); }\n");
        GL20C.glShaderSource(fs, "#version 150\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n");
        GL20C.glCompileShader(vs);
        GL20C.glCompileShader(fs);
        int p = GL20C.glCreateProgram();
        GL20C.glAttachShader(p, vs);
        GL20C.glAttachShader(p, fs);
        GL20C.glBindAttribLocation(p, 0, "Position");
        GL20C.glLinkProgram(p);
        int myVao = GL30C.glGenVertexArrays(), vbo = GL15C.glGenBuffers(), myEbo = GL15C.glGenBuffers(), myInd = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(myVao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, new float[12], GL15C.GL_STATIC_DRAW);
        GL20C.glEnableVertexAttribArray(0);
        GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 12, 0);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, myEbo);
        GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, new short[] {0, 1, 2, 2, 3, 0}, GL15C.GL_STATIC_DRAW);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, myInd);
        GL15C.glBufferData(GL40C.GL_DRAW_INDIRECT_BUFFER, new int[] {6, 1, 0, 0, 0}, GL15C.GL_STREAM_DRAW);
        GL20C.glUseProgram(p);
        sb.append("ours ").append(name(draw(GL11C.GL_UNSIGNED_SHORT, 0, 1, 20)));
        GL11C.glGetError();
        GL11C.glDrawElements(GL11C.GL_TRIANGLES, 6, GL11C.GL_UNSIGNED_SHORT, 0L);
        sb.append(" (DrawElements ").append(name(GL11C.glGetError())).append(')');
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        sb.append(", game's element buffer ").append(name(draw(gameType, 0, 1, 20)));
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, myEbo);
        GL20C.glUseProgram(prog);
        sb.append(", game's program ").append(name(draw(GL11C.GL_UNSIGNED_SHORT, 0, 1, 20)));
        GL20C.glUseProgram(p);
        GL30C.glBindVertexArray(vao);
        sb.append(", game's VAO ").append(name(draw(gameType, 0, 1, 20)));
        GL20C.glUseProgram(prog);
        sb.append(", game's VAO+program with our commands ").append(name(draw(GL11C.GL_UNSIGNED_SHORT, 0, 1, 20)));
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, ind);
        sb.append(", game's all ").append(name(draw(gameType, 0, 1, 20)));
        GL20C.glUseProgram(p);
        GL30C.glBindVertexArray(myVao);   // its own element buffer
        sb.append(", ours with the game's command buffer ").append(name(draw(GL11C.GL_UNSIGNED_SHORT, 0, 1, 20)));
        sb.append(" (its mapped ").append(GL15C.glGetBufferParameteri(GL40C.GL_DRAW_INDIRECT_BUFFER, GL15C.GL_BUFFER_MAPPED))
                .append(" usage 0x").append(Integer.toHexString(GL15C.glGetBufferParameteri(GL40C.GL_DRAW_INDIRECT_BUFFER, GL15C.GL_BUFFER_USAGE)))
                .append(" immutable ").append(GL15C.glGetBufferParameteri(GL40C.GL_DRAW_INDIRECT_BUFFER, org.lwjgl.opengl.GL44C.GL_BUFFER_IMMUTABLE_STORAGE)).append(')');
        int fresh = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, fresh);
        GL15C.glBufferData(GL40C.GL_DRAW_INDIRECT_BUFFER, new int[] {6, 1, 0, 0, 0}, GL15C.GL_STREAM_DRAW);
        GL30C.glBindVertexArray(vao);
        GL20C.glUseProgram(prog);
        sb.append(", game's VAO+program, a fresh command buffer, game's elements ").append(name(draw(gameType, 0, 1, 20)));
        int[] game = new int[5];
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, ind);
        GL15C.glGetBufferSubData(GL40C.GL_DRAW_INDIRECT_BUFFER, 0, game);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, fresh);
        GL15C.glBufferData(GL40C.GL_DRAW_INDIRECT_BUFFER, game, GL15C.GL_STREAM_DRAW);
        sb.append(", the same with the game's first command ").append(java.util.Arrays.toString(game)).append(' ').append(name(draw(gameType, 0, 1, 20)));
        for (int k = 0; k < 4; k++) GL15C.glBufferData(GL40C.GL_DRAW_INDIRECT_BUFFER, new int[] {6, 1, 0, 0, 0, 0, 0, 0, 0, 0}, GL15C.GL_STREAM_DRAW);
        sb.append(", fresh re-specified 4x ").append(name(draw(gameType, 0, 1, 20)));
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, ind);
        int indSize = GL15C.glGetBufferParameteri(GL40C.GL_DRAW_INDIRECT_BUFFER, GL15C.GL_BUFFER_SIZE);
        GL15C.glBufferSubData(GL40C.GL_DRAW_INDIRECT_BUFFER, 0, new int[] {6, 1, 0, 0, 0});
        sb.append(", game's buffer (size ").append(indSize).append(") with {6,1,0,0,0} ").append(name(draw(gameType, 0, 1, 20)));
        GL15C.glBufferSubData(GL40C.GL_DRAW_INDIRECT_BUFFER, 0, game);
        GL15C.glDeleteBuffers(fresh);
        // restore
        GL20C.glUseProgram(prog);
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, arr);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, ind);
        GL30C.glDeleteVertexArrays(myVao);
        GL15C.glDeleteBuffers(new int[] {vbo, myEbo, myInd});
        GL20C.glDeleteProgram(p);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        GL11C.glGetError();
        return sb.toString();
    }

    /** the draw with attribute a disabled, then enabled again */
    private static String without(int a, int type, long offset, int n, int stride) {
        GL20C.glDisableVertexAttribArray(a);
        int e = draw(type, offset, n, stride);
        GL20C.glEnableVertexAttribArray(a);
        return name(e);
    }

    private static int draw(int type, long offset, int n, int stride) {
        GL11C.glGetError();
        GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset, n, stride);
        return GL11C.glGetError();
    }

    private static String name(int e) {
        return e == 0 ? "ok" : "0x" + Integer.toHexString(e);
    }
}
