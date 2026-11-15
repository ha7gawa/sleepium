package dev.lull.shaders;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-vertex values a pack program reads that vanilla's vertex formats do not have, made in a vertex prologue from
 * what the vertex bytes carry (ShaderIds) and from the quad's other vertices (the vertex buffer is a storage buffer at
 * binding 9 during the level, ShaderVertexFetchMixin):
 * <ul>
 * <li>mc_Entity (block id, fluid flag), at_midBlock (offset to the block's centre x64, light emission) for terrain;</li>
 * <li>mc_midTexCoord (the quad's mean UV), at_tangent (from the quad's first triangle, Iris's NormalHelper way);</li>
 * <li>entityId / blockEntityId / currentRenderedItemId: uniforms in the pack, per-vertex here (flat to the fragment
 * stage, as Iris does);</li>
 * <li>vaUV1 / vaUV2 without the id bytes (the raw shorts come in as sl_rawUV1 / sl_rawUV2).</li>
 * </ul>
 */
final class ShaderPrologue {
    private ShaderPrologue() {}

    private static final Pattern ATTR = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?\\b(?:in|attribute)\\s+(\\w+)\\s+(mc_Entity|mc_midTexCoord|at_tangent|at_midBlock)\\s*;");
    private static final Pattern RAW = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+ivec2\\s+(vaUV1|vaUV2)\\s*;");
    private static final Pattern ID_UNIFORM = Pattern.compile("\\buniform\\s+int\\s+(entityId|blockEntityId|currentRenderedItemId)\\s*;");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)");
    private static final Pattern POSITION = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+vec3\\s+vaPosition\\s*;");

    /** {vsh, fsh} with the prologue; blockEntityIds: the overlay slot holds block entity ids (gbuffers_block*) */
    static String[] apply(String vsh, String fsh, VertexFormat format, boolean blockEntityIds) {
        boolean idsFs = ID_UNIFORM.matcher(fsh).find();
        boolean terrain = format == DefaultVertexFormat.BLOCK, entity = format == DefaultVertexFormat.NEW_ENTITY;
        StringBuilder globals = new StringBuilder();
        java.util.Set<String> attrs = new java.util.HashSet<>();
        Matcher m = ATTR.matcher(vsh);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            attrs.add(m.group(2));
            m.appendReplacement(out, m.group(1) + " " + m.group(2) + ";");
        }
        m.appendTail(out);
        vsh = out.toString();
        boolean[] raw = new boolean[3];
        m = RAW.matcher(vsh);
        out = new StringBuilder();
        while (m.find()) {
            int k = m.group(1).charAt(4) - '0';
            raw[k] = true;
            m.appendReplacement(out, "ivec2 " + m.group(1) + ";");
        }
        m.appendTail(out);
        vsh = out.toString();
        boolean idsVs = ID_UNIFORM.matcher(vsh).find();
        vsh = ID_UNIFORM.matcher(vsh).replaceAll("int $1;");
        boolean needFetch = !Boolean.getBoolean("sleep.shaders.noFetch") && (attrs.contains("mc_midTexCoord") || attrs.contains("at_tangent") || attrs.contains("at_midBlock"));
        // terrain through chunk multi-draw: the section offset per draw (ChunkMultiDraw's offsets block, gl_DrawIDARB)
        boolean multi = terrain && Pattern.compile("\\bChunkOffset\\b").matcher(vsh).find();
        if (multi) {
            vsh = vsh.replaceAll("\\bChunkOffset\\b", "sl_chunkOffset").replace("uniform vec3 sl_chunkOffset;", "uniform vec3 ChunkOffset;");
            globals.append("uniform int ProbeMultiDraw;\nlayout(std430) buffer ProbeChunkOffsets { vec4 ProbeOffsets[]; };\nvec3 sl_chunkOffset;\n");
        }
        // entities: PartBatch's resident recordings (mode 2: the recorded vertices in VRAM, posed by the draw's record)
        boolean parts = entity && POSITION.matcher(vsh).find();
        if (parts) {
            vsh = POSITION.matcher(vsh).replaceFirst("vec3 vaPosition;\nin vec3 sl_rawPosition;");
            globals.append("struct ProbePart { mat4 pose; vec4 n0; vec4 n1; vec4 n2; vec4 color; ivec4 lo; };\n")
                    .append("layout(std430) buffer ProbeParts { ProbePart probeParts[]; };\nuniform int ProbePartMode;\n");
        }
        if (attrs.isEmpty() && !raw[1] && !raw[2] && !idsVs && !idsFs && !multi && !parts) return new String[] {vsh, fsh};
        // the vertex prologue
        int stride = format.getVertexSize() / 4;
        int pos = format.contains(VertexFormatElement.POSITION) ? format.getOffset(VertexFormatElement.POSITION) / 4 : -1;
        int uv = format.contains(VertexFormatElement.UV0) ? format.getOffset(VertexFormatElement.UV0) / 4 : -1;
        int nrm = format.contains(VertexFormatElement.NORMAL) ? format.getOffset(VertexFormatElement.NORMAL) : -1;
        boolean quads = pos >= 0 && uv >= 0 && needFetch;
        globals.append("in ivec2 sl_rawUV1;\nin ivec2 sl_rawUV2;\nflat out ivec3 sl_ids;\n");
        if (quads) {
            globals.append("layout(std430, binding = 9) buffer SlVertexData { uint sl_vdata[]; };\n")
                    .append("vec3 sl_vpos(int v) { int b = v * ").append(stride).append(" + ").append(pos)
                    .append("; vec3 p = vec3(uintBitsToFloat(sl_vdata[b]), uintBitsToFloat(sl_vdata[b + 1]), uintBitsToFloat(sl_vdata[b + 2]));")
                    .append(parts ? " if (ProbePartMode == 2) p = (probeParts[(gl_BaseInstanceARB + gl_InstanceID)].pose * vec4(p, 1.0)).xyz;" : "").append(" return p; }\n")
                    .append("vec2 sl_vuv(int v) { int b = v * ").append(stride).append(" + ").append(uv)
                    .append("; return vec2(uintBitsToFloat(sl_vdata[b]), uintBitsToFloat(sl_vdata[b + 1])); }\n");
            if (nrm >= 0)
                globals.append("vec3 sl_vnormal(int v) { uint w = sl_vdata[v * ").append(stride).append(" + ").append(nrm / 4)
                        .append("]; return vec3(ivec3(int(w << 24) >> 24, int(w << 16) >> 24, int(w << 8) >> 24)) / 127.0; }\n")
                        .append("uint sl_vpad(int v) { return sl_vdata[v * ").append(stride).append(" + ").append(nrm / 4).append("] >> 24; }\n");
        }
        StringBuilder pro = new StringBuilder("void main() {\n");
        if (multi) pro.append("    sl_chunkOffset = ProbeMultiDraw != 0 ? ProbeOffsets[" + dev.lull.optsupport.ChunkMultiDraw.drawIndex() + "].xyz : ChunkOffset;\n");
        if (parts) pro.append("    vaPosition = ProbePartMode == 2 ? (probeParts[(gl_BaseInstanceARB + gl_InstanceID)].pose * vec4(sl_rawPosition, 1.0)).xyz : sl_rawPosition;\n");
        if (raw[1]) pro.append("    vaUV1 = sl_rawUV1 & 255;\n");
        if (raw[2]) pro.append("    vaUV2 = sl_rawUV2 & 255;\n");
        pro.append("    int sl_hi1 = (sl_rawUV1.x >> 8 & 255) | (sl_rawUV1.y >> 8 & 255) << 8;\n");
        pro.append("    int sl_hi2 = (sl_rawUV2.x >> 8 & 255) | (sl_rawUV2.y >> 8 & 255) << 8;\n");
        if (entity) {
            pro.append(blockEntityIds ? "    sl_ids = ivec3(0, sl_hi1, sl_hi2 - 1);\n" : "    sl_ids = ivec3(sl_hi1, 0, sl_hi2 - 1);\n");
        } else pro.append("    sl_ids = ivec3(0, 0, -1);\n");
        if (idsVs) pro.append(idAssign(vsh));
        if (quads) {
            pro.append("    int sl_q = gl_VertexID / 4 * 4;\n");
            if (attrs.contains("mc_midTexCoord"))
                pro.append("    mc_midTexCoord = vec4((sl_vuv(sl_q) + sl_vuv(sl_q + 1) + sl_vuv(sl_q + 2) + sl_vuv(sl_q + 3)) * 0.25, 0.0, 1.0);\n");
            if (attrs.contains("at_tangent")) {
                pro.append("    {\n        vec3 p0 = sl_vpos(sl_q), e1 = sl_vpos(sl_q + 1) - p0, e2 = sl_vpos(sl_q + 2) - p0;\n")
                        .append("        vec2 t0 = sl_vuv(sl_q), d1 = sl_vuv(sl_q + 1) - t0, d2 = sl_vuv(sl_q + 2) - t0;\n")
                        .append("        float den = d1.x * d2.y - d2.x * d1.y, f = den == 0.0 ? 1.0 : 1.0 / den;\n")
                        .append("        vec3 T = f * (d2.y * e1 - d1.y * e2), B = f * (-d2.x * e1 + d1.x * e2);\n")
                        .append(nrm >= 0 ? "        vec3 N = sl_vnormal(sl_q);\n" : "        vec3 N = normalize(cross(e1, e2));\n")
                        .append("        at_tangent = vec4(length(T) > 0.0 ? normalize(T) : vec3(1.0, 0.0, 0.0), dot(cross(N, T), B) < 0.0 ? -1.0 : 1.0);\n    }\n");
            }
            if (attrs.contains("at_midBlock") && terrain) {
                pro.append("    {\n        vec3 c = (sl_vpos(sl_q) + sl_vpos(sl_q + 1) + sl_vpos(sl_q + 2) + sl_vpos(sl_q + 3)) * 0.25;\n")
                        .append("        vec3 blk = floor(c - sl_vnormal(sl_q) * 0.01) + 0.5;\n")
                        .append("        at_midBlock = vec4((blk - sl_vpos(gl_VertexID)) * 64.0, float(sl_vpad(gl_VertexID) & 15u));\n    }\n");
            }
        }
        if (attrs.contains("mc_Entity"))
            pro.append(terrain ? "    mc_Entity = vec4(float(sl_hi2) - 1.0, (sl_vpadFluid()) ? 1.0 : -1.0, 0.0, 1.0);\n" : "    mc_Entity = vec4(-1.0, -1.0, 0.0, 1.0);\n");
        if (attrs.contains("at_midBlock") && !(terrain && quads)) pro.append("    at_midBlock = vec4(0.0);\n");
        if (attrs.contains("mc_midTexCoord") && !quads) pro.append("    mc_midTexCoord = vec4(0.0, 0.0, 0.0, 1.0);\n");
        if (attrs.contains("at_tangent") && !quads) pro.append("    at_tangent = vec4(1.0, 0.0, 0.0, 1.0);\n");
        pro.append("    sl_main();\n}\n");
        String fluid = terrain && quads && nrm >= 0 ? "bool sl_vpadFluid() { return (sl_vpad(gl_VertexID) & 16u) != 0u; }\n" : "bool sl_vpadFluid() { return false; }\n";
        Matcher mm = MAIN.matcher(vsh);
        if (!mm.find()) return new String[] {vsh, fsh};
        vsh = insertGlobals(vsh.substring(0, mm.start()) + "void sl_main()" + vsh.substring(mm.end()), globals + fluid) + "\n" + pro;
        if (idsFs) {
            // plain globals set before the pack's main (a pack local of the same name - Complementary's terrain has
            // "int blockEntityId = mat;" - shadows them as it shadowed the uniform)
            boolean[] first = {true};
            Matcher fm = ID_UNIFORM.matcher(fsh);
            StringBuilder fo = new StringBuilder();
            while (fm.find()) {
                fm.appendReplacement(fo, (first[0] ? "flat in ivec3 sl_ids;\n" : "") + "int " + fm.group(1) + ";");
                first[0] = false;
            }
            fm.appendTail(fo);
            fsh = fo.toString();
            Matcher fmain = MAIN.matcher(fsh);
            if (fmain.find()) {
                fsh = fsh.substring(0, fmain.start()) + "void sl_fmain()" + fsh.substring(fmain.end()) + "\nvoid main() {\n" + idAssign(fsh) + "    sl_fmain();\n}\n";
            }
        }
        return new String[] {vsh, fsh};
    }

    /** assignments for the id uniforms the vertex stage declared (now plain globals) */
    private static String idAssign(String vsh) {
        StringBuilder b = new StringBuilder();
        if (Pattern.compile("\\bint\\s+entityId\\s*;").matcher(vsh).find()) b.append("    entityId = sl_ids.x;\n");
        if (Pattern.compile("\\bint\\s+blockEntityId\\s*;").matcher(vsh).find()) b.append("    blockEntityId = sl_ids.y;\n");
        if (Pattern.compile("\\bint\\s+currentRenderedItemId\\s*;").matcher(vsh).find()) b.append("    currentRenderedItemId = sl_ids.z;\n");
        return b.toString();
    }

    /** the storage-buffer extension right after #version; the globals before the first code line */
    private static String insertGlobals(String src, String globals) {
        int v = src.indexOf('\n');
        String head = src.substring(0, v + 1) + "#extension GL_ARB_shader_storage_buffer_object : enable\n#extension GL_ARB_shader_draw_parameters : enable\n";
        String[] lines = src.substring(v + 1).split("\n", -1);
        StringBuilder lead = new StringBuilder(), rest = new StringBuilder();
        boolean inComment = false;
        int i = 0;
        for (; i < lines.length; i++) {
            String l = lines[i].strip();
            if (!inComment && !l.isEmpty() && !l.startsWith("#") && !l.startsWith("//") && !l.startsWith("/*")) break;
            lead.append(lines[i]).append('\n');
            inComment = GlslPreprocessor.commentStateAfter(lines[i], inComment);
        }
        for (; i < lines.length; i++) rest.append(lines[i]).append(i + 1 < lines.length ? "\n" : "");
        return head + lead + globals + rest;
    }
}
