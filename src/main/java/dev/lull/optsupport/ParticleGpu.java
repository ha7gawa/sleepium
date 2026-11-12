package dev.lull.optsupport;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

/**
 * exp/particle-gpu (client): a particle list drawn from records - one per particle, its quad's corners computed by the
 * particle shader - instead of four vertices written per particle per frame (36 % of the render thread with 500
 * working machines' particles).
 *
 * <p>Per particle the CPU does what SingleQuadParticle.render does before its vertices: the rotation (the camera's,
 * with the particle's roll), the position relative to the camera (the same lerp and float casts), the quad size, the sprite's UVs, the
 * light and the colour bytes (color's (int) (c * 255)). The four corner offsets - Vector3f(+-1, +-1, 0).rotate(q),
 * JOML's own arithmetic - are computed once per distinct rotation (one for all particles without roll). The shader
 * does the rest: offset * size + position (mul, then add), each product and sum rounded on its own (precise). UVs are
 * copied; colour and light reach the shader as per-instance vertex attributes in the particle format's own types, so
 * the hardware converts them as it converts vanilla's. One instance per particle in the list's order: the same draw
 * order, also for the translucent sheet.
 *
 * <p>A list is taken only when every particle in it is plain (ParticleGpu.plain: a SingleQuadParticle whose render
 * is SingleQuadParticle's, untouched by other mods) and its sheet begins the PARTICLE format;
 * else the whole list is drawn the vanilla way. The batch still gets one degenerate quad so that its draw - shader,
 * uniforms, textures, state - happens; the records are drawn right before it.
 *
 * <p>1.20.1: render builds the rotation itself (the camera's, a copy rotated by the roll) and writes the corners
 * (-1,-1) (-1,1) (1,1) (1,-1); the sheet's begin has no return and its end(tesselator) draws (afterEnd: records not
 * drawn by it are dropped, counted as lost). Method names are compared as Mojang's (production runs on SRG names).
 */
public final class ParticleGpu {
    private ParticleGpu() {}

    public static volatile boolean enabled = true;
    public static long lists, listsVanilla, particles, rotations;
    private static final int REC = 48;
    private static final boolean TOUCHED = MixinTouch.touched("net/minecraft/client/particle/SingleQuadParticle", "render");

    /** the particle's fields and protected getters (ParticleGpuAccessor, ParticleUvInvoker) */
    public interface Access {
        double sleep$xo();

        double sleep$yo();

        double sleep$zo();

        double sleep$px();

        double sleep$py();

        double sleep$pz();

        float sleep$roll();

        float sleep$oRoll();

        float sleep$r();

        float sleep$g();

        float sleep$b();

        float sleep$a();

        int sleep$light(float partialTicks);

        net.minecraft.client.multiplayer.ClientLevel sleep$level();
    }

    public interface Uv {
        float sleep$u0();

        float sleep$u1();

        float sleep$v0();

        float sleep$v1();
    }

    private static final Map<Class<?>, Boolean> PLAIN = new ConcurrentHashMap<>();

    /** a SingleQuadParticle drawn by SingleQuadParticle.render itself */
    public static boolean plain(Particle p) {
        if (TOUCHED || !(p instanceof SingleQuadParticle)) return false;
        return PLAIN.computeIfAbsent(p.getClass(), c -> declares(c, "render"));
    }

    private static final java.util.function.UnaryOperator<String> NAME = dev.lull.Names::official;   // method names as Mojang's
    private static final Map<Class<?>, Boolean> OWN_LIGHT = new ConcurrentHashMap<>();
    private static final boolean LIGHT_TOUCHED = MixinTouch.touched("net/minecraft/client/particle/Particle", "getLightColor")
            || MixinTouch.touched("net/minecraft/client/renderer/LevelRenderer", "getLightColor");

    /** the class gets its light from Particle.getLightColor (no override below Particle, no other mod's mixin on it or LevelRenderer's) */
    private static boolean ownLight(Class<?> c) {
        if (LIGHT_TOUCHED) return false;
        return OWN_LIGHT.computeIfAbsent(c, k -> {
            for (Class<?> x = k; x != null && x != Particle.class; x = x.getSuperclass())
                for (java.lang.reflect.Method m : x.getDeclaredMethods()) if (NAME.apply(m.getName()).equals("getLightColor")) return false;
            return true;
        });
    }

    private static boolean declares(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != SingleQuadParticle.class; k = k.getSuperclass())
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) if (dev.lull.Names.official(m.getName()).equals(name)) return false;   // overridden below SingleQuadParticle
        return true;
    }

    // ------------------------------------------------------------------ the list being drawn (render thread)

    /** set by begin(): the current list goes to records */
    private static boolean listOn;
    private static ByteBuffer rec = MemoryUtil.memAlloc(REC * 4096);
    private static int n;
    private static ByteBuffer corners = MemoryUtil.memAlloc(64 * 64);
    private static int nRot;
    private static final Quaternionf Q = new Quaternionf(), LAST = new Quaternionf();
    private static final Vector3f V = new Vector3f();
    private static boolean pending;

    /** From ParticleEngine.render after the sheet began its builder: whether this list is drawn from records. */
    public static void begin(ParticleRenderType type, BufferBuilder bb, java.util.Collection<Particle> queue) {
        listOn = false;
        n = 0;
        nRot = 0;
        // only with the particle shader patched and the draw hook seen working (PartDrawMixin's _drawWithShader): without
        // either the records would never be drawn - the particles gone (an N100 run with most of the engine off)
        if (!enabled || bb == null || !patched || !hookSeen || !partsReady()) return;
        // the sheet's shader (its begin may set its own: a mod's sheet) must be the patched particle program, else the
        // records would never be drawn
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null || program(shader).length == 0) {
            listsVanilla++;
            return;
        }
        if (((dev.lull.opt.BufferBuilderAccessor) bb).sleep$format() != DefaultVertexFormat.PARTICLE
                || ((dev.lull.opt.BufferBuilderAccessor) bb).sleep$mode() != VertexFormat.Mode.QUADS) return;
        for (Particle p : queue)
            if (!plain(p)) {
                listsVanilla++;
                return;
            }
        listOn = true;
        lists++;
    }

    /** In place of particle.render: true if the particle became a record. */
    public static boolean add(Particle particle, Camera camera, float pt) {
        if (!listOn) return false;
        SingleQuadParticle p = (SingleQuadParticle) particle;
        Access a = (Access) (Object) p;
        // SingleQuadParticle.render: the rotation (camera.rotation() itself without roll, else a copy rotated)
        Quaternionf q = Q.set(camera.rotation());
        float roll = a.sleep$roll();
        if (roll != 0.0F) q.rotateZ(Mth.lerp(pt, a.sleep$oRoll(), roll));
        int ci;
        if (nRot > 0 && sameBits(q, LAST)) ci = nRot - 1;
        else {
            ci = rotation(q);
            LAST.set(q);
        }
        // the position, floats as vanilla casts them
        Vec3 c = camera.getPosition();
        float x = (float) (Mth.lerp((double) pt, a.sleep$xo(), a.sleep$px()) - c.x());
        float y = (float) (Mth.lerp((double) pt, a.sleep$yo(), a.sleep$py()) - c.y());
        float z = (float) (Mth.lerp((double) pt, a.sleep$zo(), a.sleep$pz()) - c.z());
        // size, UVs, light - in vanilla's order of calls
        float size = p.getQuadSize(pt);
        Uv uv = (Uv) p;
        float u0 = uv.sleep$u0(), u1 = uv.sleep$u1(), v0 = uv.sleep$v0(), v1 = uv.sleep$v1();
        // Particle.getLightColor itself (not overridden): its value per block position, once per render window (ParticleLightMemo)
        int light = ParticleLightMemo.open() && ownLight(p.getClass()) ? ParticleLightMemo.particleLight(a.sleep$level(), a.sleep$px(), a.sleep$py(), a.sleep$pz())
                : a.sleep$light(pt);
        if ((long) (n + 1) * REC > rec.capacity()) {
            ByteBuffer b = MemoryUtil.memRealloc(rec, rec.capacity() * 2);
            rec = b;
        }
        long at = MemoryUtil.memAddress(rec) + (long) n * REC;
        MemoryUtil.memPutFloat(at, x);
        MemoryUtil.memPutFloat(at + 4, y);
        MemoryUtil.memPutFloat(at + 8, z);
        MemoryUtil.memPutFloat(at + 12, size);
        MemoryUtil.memPutFloat(at + 16, u0);
        MemoryUtil.memPutFloat(at + 20, u1);
        MemoryUtil.memPutFloat(at + 24, v0);
        MemoryUtil.memPutFloat(at + 28, v1);
        // color(float, float, float, float): (int) (c * 255.0F) per channel, as BufferBuilder writes it
        MemoryUtil.memPutByte(at + 32, (byte) (int) (a.sleep$r() * 255.0F));
        MemoryUtil.memPutByte(at + 33, (byte) (int) (a.sleep$g() * 255.0F));
        MemoryUtil.memPutByte(at + 34, (byte) (int) (a.sleep$b() * 255.0F));
        MemoryUtil.memPutByte(at + 35, (byte) (int) (a.sleep$a() * 255.0F));
        MemoryUtil.memPutShort(at + 36, (short) (light & 0xFFFF));   // uv2: block, then sky
        MemoryUtil.memPutShort(at + 38, (short) (light >> 16 & 0xFFFF));
        MemoryUtil.memPutInt(at + 40, ci);
        MemoryUtil.memPutInt(at + 44, 0);
        n++;
        particles++;
        return true;
    }

    private static boolean sameBits(Quaternionf a, Quaternionf b) {
        return Float.floatToRawIntBits(a.x) == Float.floatToRawIntBits(b.x) && Float.floatToRawIntBits(a.y) == Float.floatToRawIntBits(b.y)
                && Float.floatToRawIntBits(a.z) == Float.floatToRawIntBits(b.z) && Float.floatToRawIntBits(a.w) == Float.floatToRawIntBits(b.w);
    }

    /** render's four offsets, new Vector3f(+-1, +-1, 0).rotate(q) - JOML on the CPU - for this rotation: its index */
    private static int rotation(Quaternionf q) {
        if ((long) (nRot + 1) * 64 > corners.capacity()) corners = MemoryUtil.memRealloc(corners, corners.capacity() * 2);
        long at = MemoryUtil.memAddress(corners) + (long) nRot * 64;
        put(at, V.set(-1.0F, -1.0F, 0.0F).rotate(q));
        put(at + 16, V.set(-1.0F, 1.0F, 0.0F).rotate(q));
        put(at + 32, V.set(1.0F, 1.0F, 0.0F).rotate(q));
        put(at + 48, V.set(1.0F, -1.0F, 0.0F).rotate(q));
        rotations++;
        return nRot++;
    }

    private static void put(long at, Vector3f v) {
        MemoryUtil.memPutFloat(at, v.x);
        MemoryUtil.memPutFloat(at + 4, v.y);
        MemoryUtil.memPutFloat(at + 8, v.z);
        MemoryUtil.memPutFloat(at + 12, 0.0F);
    }

    /** From ParticleEngine.render before the sheet's end: the batch drawn even if every particle is a record. */
    public static void beforeBuild(BufferBuilder bb) {
        if (!listOn) return;
        listOn = false;
        if (n == 0) return;
        if (((dev.lull.opt.BufferBuilderAccessor) bb).sleep$vertices() == 0)
            for (int i = 0; i < 4; i++) bb.vertex(0.0D, 0.0D, 0.0D).uv(0.0F, 0.0F).color(0, 0, 0, 0).uv2(0).endVertex();
        pending = true;
    }

    /** After the sheet's end: records its draw did not take (a sheet whose end draws nothing) are dropped, not left for another draw. */
    public static void afterEnd() {
        if (!pending) return;
        pending = false;
        lost += n;
    }

    // ------------------------------------------------------------------ drawing (VertexBuffer._drawWithShader, right before the batch)

    private static int recBuf, cornerBuf, vao;
    private static final Map<Integer, int[]> PROGRAMS = new ConcurrentHashMap<>();
    public static long draws;

    /** the particle program's mode uniform and blocks, bound once; null if this program is not patched */
    private static int[] program(ShaderInstance shader) {
        return PROGRAMS.computeIfAbsent(shader.getId(), id -> {
            int mode = GL20C.glGetUniformLocation(id, "ProbeParticleMode");
            int b0 = GL43C.glGetProgramResourceIndex(id, GL43C.GL_SHADER_STORAGE_BLOCK, "ProbeParticles");
            int b1 = GL43C.glGetProgramResourceIndex(id, GL43C.GL_SHADER_STORAGE_BLOCK, "ProbeParticleCorners");
            if (mode < 0 || b0 == GL31C.GL_INVALID_INDEX || b1 == GL31C.GL_INVALID_INDEX) return new int[0];
            GL43C.glShaderStorageBlockBinding(id, b0, 2);
            GL43C.glShaderStorageBlockBinding(id, b1, 3);
            return new int[] {mode};
        });
    }

    /** the draw hook ran (PartDrawMixin applied): records can be drawn */
    private static volatile boolean hookSeen;

    public static void beforeDraw(ShaderInstance shader, com.mojang.blaze3d.vertex.VertexBuffer vb) {
        if (!hookSeen) hookSeen = true;
        if (!pending) return;
        pending = false;
        int[] st = program(shader);
        if (st.length == 0) {
            lost += n;
            return;
        }
        if (recBuf == 0) {
            recBuf = GL15C.glGenBuffers();
            cornerBuf = GL15C.glGenBuffers();
            vao = GL30C.glGenVertexArrays();
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, recBuf);
            // the particle format's attributes as the shader binds them (Position 0, UV0 1, Color 2, UV2 3): colour and light per instance
            GL20C.glEnableVertexAttribArray(2);
            GL20C.glVertexAttribPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, true, REC, 32);
            GL33C.glVertexAttribDivisor(2, 1);
            GL20C.glEnableVertexAttribArray(3);
            GL30C.glVertexAttribIPointer(3, 2, GL11C.GL_SHORT, REC, 36);
            GL33C.glVertexAttribDivisor(3, 1);
        }
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, recBuf);
        GL15C.nglBufferData(GL15C.GL_ARRAY_BUFFER, (long) n * REC, MemoryUtil.memAddress(rec), GL15C.GL_STREAM_DRAW);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, cornerBuf);
        GL15C.nglBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, (long) nRot * 64, MemoryUtil.memAddress(corners), GL15C.GL_STREAM_DRAW);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, recBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, cornerBuf);
        GL30C.glBindVertexArray(vao);
        RenderSystem.AutoStorageIndexBuffer ib = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        ib.bind(6);
        GL20C.glUniform1i(st[0], 1);
        GL31C.glDrawElementsInstanced(GL11C.GL_TRIANGLES, 6, ib.type().asGLType, 0L, n);
        GL20C.glUniform1i(st[0], 0);
        draws++;
        vb.bind();
    }

    public static long lost;

    private static Boolean ready;

    /** storage buffers and instancing (GL 4.3 or the extensions) */
    private static boolean partsReady() {
        Boolean r = ready;
        if (r == null) ready = r = capable() && !dev.lull.shaders.ShaderPipeline.active;
        return r && !dev.lull.shaders.ShaderPipeline.active;
    }

    private static Boolean capable;

    /**
     * Storage buffers and GL 3.3 (GL 4.3 or ARB_shader_storage_buffer_object): the shader patch and the draws both ask.
     * Without them the particle shader stays vanilla's - 1.2.0 patched it unconditionally and the game crashed at the
     * first resource load where the driver has none (Android launchers' GL4ES: GL 2.1; macOS: GL 4.1).
     */
    static boolean capable() {
        Boolean c = capable;
        if (c == null) {
            var caps = org.lwjgl.opengl.GL.getCapabilities();
            capable = c = GlLimits.vertexStorageBlocks() > 0 && caps.OpenGL33;
            if (!c) System.out.println("[sleep/opt] particle records: not available (needs GL 4.3 or ARB_shader_storage_buffer_object, and GL 3.3): particles drawn the vanilla way");
        }
        return c;
    }

    // ------------------------------------------------------------------ the particle vertex shader

    /** The particle vertex shader with the record path: Position and UV0 from the record (gl_InstanceID), corner by gl_VertexID. */
    public static String patch(String src) {
        String main = "void main() {";
        if (!capable() || !src.startsWith("#version 150") || !src.contains(main) || !src.contains("in vec3 Position;") || !src.contains("in vec2 UV0;")) return src;
        int at = src.indexOf(main);
        String head = src.substring(0, at), body = src.substring(at + main.length());
        body = body.replaceAll("\\bPosition\\b", "probe_Position").replaceAll("\\bUV0\\b", "probe_UV0");
        String pre = "uniform int ProbeParticleMode;\n"
                + "struct ProbeParticle { vec4 pos; vec4 uv; ivec4 misc; };\n"
                + "layout(std430) buffer ProbeParticles { ProbeParticle probeParticles[]; };\n"
                + "layout(std430) buffer ProbeParticleCorners { vec4 probeCorners[]; };\n"
                + "#ifdef GL_ARB_gpu_shader5\n#define PROBE_PRECISE precise\n#else\n#define PROBE_PRECISE\n#endif\n"
                + "void main() {\n"
                + "    vec3 probe_Position = Position;\n    vec2 probe_UV0 = UV0;\n"
                + "    if (ProbeParticleMode != 0) {\n"
                + "        ProbeParticle probe_p = probeParticles[gl_InstanceID];\n"
                + "        int probe_k = gl_VertexID & 3;\n"
                + "        vec4 probe_o = probeCorners[probe_p.misc.z * 4 + probe_k];\n"
                // render: vector.rotate(q).mul(quadSize).add(x, y, z) - a product, then a sum, each rounded
                + "        PROBE_PRECISE float probe_x0 = probe_o.x * probe_p.pos.w; PROBE_PRECISE float probe_x = probe_x0 + probe_p.pos.x;\n"
                + "        PROBE_PRECISE float probe_y0 = probe_o.y * probe_p.pos.w; PROBE_PRECISE float probe_y = probe_y0 + probe_p.pos.y;\n"
                + "        PROBE_PRECISE float probe_z0 = probe_o.z * probe_p.pos.w; PROBE_PRECISE float probe_z = probe_z0 + probe_p.pos.z;\n"
                + "        probe_Position = vec3(probe_x, probe_y, probe_z);\n"
                // corners (-1,-1) (-1,1) (1,1) (1,-1): UVs (u1,v1) (u1,v0) (u0,v0) (u0,v1); uv = (u0, u1, v0, v1)
                + "        probe_UV0 = probe_k == 0 ? probe_p.uv.yw : probe_k == 1 ? probe_p.uv.yz : probe_k == 2 ? probe_p.uv.xz : probe_p.uv.xw;\n"
                + "    }";
        String out = head.replaceFirst("#version 150", "#version 150\n#extension GL_ARB_shader_storage_buffer_object : require\n#extension GL_ARB_gpu_shader5 : enable")
                + pre + body;
        patched = true;
        return out;
    }

    public static volatile boolean patched;

    public static String report() {
        return String.format(java.util.Locale.ROOT, "particle gpu: lists %d (vanilla %d), particles %d, rotations %d, draws %d, lost %d, shader patched %s",
                lists, listsVanilla, particles, rotations, draws, lost, patched);
    }
}
