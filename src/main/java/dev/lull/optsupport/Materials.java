package dev.lull.optsupport;

import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;

/**
 * Material shading for part-batched geometry (PartBatch modes 1 and 3: vanilla parts and GeckoLib bones): a texture
 * {@code <texture>_s.png} beside the model's texture is a material mask in the LabPBR layout resource packs use -
 * R perceptual smoothness, G reflectance (230 and up: metal, tinted by the albedo), A emission (255: none). The
 * entity fragment shaders get: highlights from the two world lights, a sky reflection and a grazing rim for smooth
 * surfaces (metal tinted, dielectrics faint), all scaled by the lightmap (nothing shines in the dark), and emission
 * (full albedo whatever the light). The mask's B (LabPBR's porosity, unused by our models) is ours: a polish - B 1..127
 * its strength, B 128..255 the same less 128 on a texel that may glow besides (Coatings.GLOW: a blade under a buff).
 * A polish is a sharp highlight and the sky's bright horizon (and the ground's glint) sliding over the metal as it
 * turns (10-04: "a stronger shine, light running over the metal" - no colours the forge could not give).
 * A model without a mask draws exactly as before (the branch is off), and so does one whose B is 0 or 128.
 * {@code -Dsleep.materials=false} turns it off.
 */
public final class Materials {
    private Materials() {}

    public static final boolean ON = !"false".equals(System.getProperty("sleep.materials"));
    static final int UNIT = 7;   // a texture unit the entity shaders do not use
    /** the fragment stage reads what the vertex stage of the same name writes: patched only after it */
    /** ShaderPatches.failed: this shader stays vanilla's */
    static void unpatched(String name) {
        VERTEX_PATCHED.remove(name);
    }

    private static final java.util.Set<String> VERTEX_PATCHED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static long bound, missing;

    private static boolean shaderTarget(String name) {
        return name.startsWith("rendertype_entity_cutout") || name.startsWith("rendertype_entity_translucent") || name.startsWith("rendertype_entity_solid")
                || name.equals("rendertype_armor_cutout_no_cull");
    }

    /** after PartBatch.patch: the fragment stage gets the posed normal and the camera-relative position */
    public static String patchVertex(String name, String src) {
        if (!ON || !shaderTarget(name) || !src.contains("probe_Normal")) return src;
        int end = src.lastIndexOf('}');
        int main = src.indexOf("void main() {");
        if (end < 0 || main < 0) return src;
        VERTEX_PATCHED.add(name);
        // the coatings (Coatings): the packed amounts and the height up the model in its bind pose (the recorded geometry's
        // own position: a coat stays where it lies on the body however it moves), from the part's record
        String coat = "    probeCoat = 0;\n    probeCoat2 = 0;\n    probeDmg = 0;\n    probeCoatH = 0.0;\n"
                + "    if (ProbePartMode == 1 || ProbePartMode == 3) {\n"
                + "        probeCoat = int(probeParts[gl_DrawIDARB].n0.w + 0.5);\n"
                + "        probeCoat2 = int(probeParts[gl_DrawIDARB].n1.w + 0.5);\n"
                + "        int probeW2 = int(probeParts[gl_DrawIDARB].n2.w + 0.5);\n"   // the height (tenths, 8 bits) and the blows' marks
                + "        probeDmg = probeW2 >> 8;\n"
                + "        if (probeW2 != 0) probeCoatH = Position.y / max(float(probeW2 & 255) / 10.0, 0.1);\n"
                + "    }\n";
        return src.substring(0, main) + "out vec3 probeMatN;\nout vec3 probeMatP;\nflat out int probeCoat;\nflat out int probeCoat2;\nflat out int probeDmg;\nout float probeCoatH;\n" + src.substring(main, end)
                + "    probeMatN = probe_Normal;\n    probeMatP = probe_Position;\n" + coat + src.substring(end);
    }

    public static String patchFragment(String name, String src) {
        if (!ON || !VERTEX_PATCHED.contains(name) || !src.contains("texCoord0") || !src.contains("lightMapColor") || !src.contains("fragColor = linear_fog(")) return src;
        int main = src.indexOf("void main() {");
        if (main < 0) return src;
        String decl = "uniform sampler2D ProbeMaterial;\nuniform int ProbeMaterialOn;\nuniform vec3 Light0_Direction;\nuniform vec3 Light1_Direction;\n"
                + "in vec3 probeMatN;\nin vec3 probeMatP;\nflat in int probeCoat;\nflat in int probeCoat2;\nflat in int probeDmg;\nin float probeCoatH;\n\n";
        String shade = """
                    if (ProbeMaterialOn != 0) {
                        vec4 probe_m = texture(ProbeMaterial, texCoord0);
                        vec3 probe_alb = texture(Sampler0, texCoord0).rgb * vertexColor.a;
                        float probe_s = probe_m.r;
                        bool probe_metal = probe_m.g > 0.898;
                        float probe_f0 = probe_metal ? 1.0 : max(probe_m.g, 0.04);
                        float probe_e = probe_m.a < 0.999 ? probe_m.a : 0.0;
                        vec3 probe_N = normalize(probeMatN), probe_V = normalize(-probeMatP);
                        if (dot(probe_N, probe_V) < 0.0) probe_N = -probe_N;
                        float probe_lit = max(max(lightMapColor.r, lightMapColor.g), lightMapColor.b);
                        float probe_shin = mix(4.0, 40.0, probe_s * probe_s);   // broad: small flat faces must not flash on and off
                        vec3 probe_H0 = normalize(normalize(Light0_Direction) + probe_V), probe_H1 = normalize(normalize(Light1_Direction) + probe_V);
                        float probe_spec = (pow(max(dot(probe_N, probe_H0), 0.0), probe_shin) + 0.6 * pow(max(dot(probe_N, probe_H1), 0.0), probe_shin)) * (probe_shin + 8.0) / 60.0;
                        float probe_fres = pow(1.0 - max(dot(probe_N, probe_V), 0.0), 5.0);
                        vec3 probe_R = reflect(-probe_V, probe_N);
                        vec3 probe_sky = mix(vec3(0.13, 0.11, 0.10), mix(vec3(0.52, 0.55, 0.58), vec3(0.70, 0.78, 0.92), clamp(probe_R.y, 0.0, 1.0)), smoothstep(-0.3, 0.15, probe_R.y));
                        vec3 probe_tint = probe_metal ? probe_alb : vec3(1.0);
                        vec3 probe_add = probe_tint * probe_f0 * (probe_spec * probe_s * 1.6 + probe_sky * probe_s * mix(0.25, 0.95, float(probe_metal)))
                                + probe_sky * probe_fres * probe_s * mix(0.12, 0.5, float(probe_metal));   // (no ternary inside an expression: Veil reprints sources and drops its parentheses)
                        int probe_bi = int(probe_m.b * 255.0 + 0.5);
                        float probe_pol = probe_bi < 128 ? float(probe_bi) / 127.0 : float(probe_bi - 128) / 127.0;
                        if (probe_pol > 0.0) {   // a polish: a sharp highlight, the horizon's bright line and the ground's glint sliding over it
                            float probe_ps = mix(40.0, 160.0, probe_pol);
                            float probe_pspec = (pow(max(dot(probe_N, probe_H0), 0.0), probe_ps) + 0.6 * pow(max(dot(probe_N, probe_H1), 0.0), probe_ps)) * (probe_ps + 8.0) / 40.0;
                            float probe_band = exp(-abs(probe_R.y - 0.08) * 22.0) + 0.5 * exp(-abs(probe_R.y + 0.35) * 14.0);
                            probe_add += probe_tint * probe_pol * (probe_pspec * 1.4 + probe_band * 0.9);
                        }
                        if (probe_metal) color.rgb *= mix(1.0, 0.35, probe_s);   // metal: little diffuse, mostly what it reflects
                        color.rgb += probe_add * probe_lit;
                        color.rgb = mix(color.rgb, probe_alb * 1.15, probe_e);
                    }
                    if (probeCoat != 0 || probeCoat2 != 0 || probeDmg != 0) {   // the coatings: wet, mud from the feet up, snow on what faces up, ice in patches, blood, soot; the blows' marks
                        float c_wet = float(probeCoat & 63) / 63.0, c_mud = float((probeCoat >> 6) & 63) / 63.0;
                        float c_snow = float((probeCoat >> 12) & 63) / 63.0, c_ice = float((probeCoat >> 18) & 63) / 63.0;
                        float c_blood = float(probeCoat2 & 63) / 63.0, c_soot = float((probeCoat2 >> 6) & 63) / 63.0;
                        vec2 c_tx = floor(texCoord0 * vec2(textureSize(Sampler0, 0)));   // a value per texel: the coat in the texture's own pixels
                        float c_r = fract(sin(dot(c_tx, vec2(12.9898, 78.233))) * 43758.5453);
                        vec3 c_N = normalize(probeMatN), c_V = normalize(-probeMatP);
                        if (dot(c_N, c_V) < 0.0) c_N = -c_N;
                        vec3 c_light = vertexColor.rgb * lightMapColor.rgb;   // what lights the surface here (the diffuse shading, the light map)
                        float c_lit = max(max(lightMapColor.r, lightMapColor.g), lightMapColor.b);
                        float c_gloss = 0.0;
                        float c_r2 = fract(sin(dot(floor(c_tx / 2.0), vec2(27.619, 57.583))) * 43758.5453);   // patches of 2 x 2 texels
                        float c_glow = float((probeCoat2 >> 12) & 63) / 63.0;
                        if (c_glow > 0.0 && ProbeMaterialOn != 0) {   // a glow: the texels marked for it (B 128..255) lit by themselves in its hue
                            float c_b = texture(ProbeMaterial, texCoord0).b;
                            if (c_b >= 0.5) {
                                float c_hue = float((probeCoat2 >> 18) & 63) / 63.0;
                                vec3 c_col = mix(vec3(1.0), clamp(abs(fract(c_hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0), 0.75);
                                float c_lum = dot(texture(Sampler0, texCoord0).rgb, vec3(0.3, 0.5, 0.2));   // the steel's own grain through it
                                color.rgb = mix(color.rgb, c_col * (0.45 + 1.1 * c_lum), c_glow * 0.85);
                            }
                        }
                        if (probeDmg != 0) {   // the blows' marks (10-07): by where on the body - the head, the body, the legs
                            float d_q = probeCoatH > 0.82 ? float(probeDmg & 15) : probeCoatH >= 0.45 ? float((probeDmg >> 4) & 15) : float((probeDmg >> 8) & 15);
                            float d = d_q / 15.0;
                            if (d > 0.0) {
                                vec3 d_alb = texture(Sampler0, texCoord0).rgb;
                                float d_mx = max(d_alb.r, max(d_alb.g, d_alb.b)), d_mn = min(d_alb.r, min(d_alb.g, d_alb.b));
                                bool d_metal = ProbeMaterialOn != 0 ? texture(ProbeMaterial, texCoord0).g > 0.898 : (d_mx - d_mn < 0.1 && d_mx > 0.3);   // (the mask's metal; without one a guess)
                                vec2 d_c3 = floor(c_tx / 3.0), d_l = c_tx - d_c3 * 3.0;
                                float d_r3 = fract(sin(dot(d_c3, vec2(71.31, 19.73))) * 43758.5453);
                                float d_r4 = fract(sin(dot(floor(c_tx / 4.0), vec2(5.17, 93.11))) * 43758.5453);
                                if (d_metal) {
                                    if (d_r3 > 1.0 - d * 0.22) color.rgb *= d_l.y < 0.5 ? 1.22 : (d_l.y < 1.5 ? 0.72 : 0.62);   // a dent: its upper rim lit, its hollow dark
                                    if (mod(c_tx.x + c_tx.y, 5.0) < 0.5 && d_r4 > 1.0 - d * 0.38)
                                        color.rgb = mix(color.rgb, vec3(0.86, 0.87, 0.9) * c_light, 0.6);   // a gouge: the bare steel bright
                                    if (d > 0.55 && mod(c_tx.x * 2.0 - c_tx.y, 7.0) < 0.5 && d_r3 < (d - 0.55) * 0.6) color.rgb *= 0.3;   // cracks
                                } else {
                                    if (mod(c_tx.x - c_tx.y * 2.0, 6.0) < 0.5 && d_r4 > 1.0 - d * 0.25) color.rgb *= 0.32;   // cuts
                                    color.rgb *= 1.0 - 0.12 * d * c_r2;   // grime
                                }
                            }
                        }
                        if (c_soot > 0.0) {   // soot: blackened patches, heavier toward the feet (the fire was below), matte
                            float m = step(c_r2 * 0.75 + probeCoatH * 0.35 + (c_r - 0.5) * 0.1, c_soot * 1.05);
                            color.rgb *= mix(1.0, 0.28, 0.85 * m);
                        }
                        if (c_blood > 0.0) {   // blood: dark red splashes over the body and the arms (not the feet), wet while fresh
                            float m = step(1.0 - c_blood * 0.3, c_r2 * 0.8 + c_r * 0.2) * step(0.3, probeCoatH) * step(probeCoatH, 0.88);   // a third of it at the most
                            color.rgb = mix(color.rgb, vec3(0.21, 0.035, 0.025) * c_light, 0.8 * m);
                            c_gloss = max(c_gloss, 0.35 * m * c_blood);
                        }
                        if (c_mud > 0.0) {   // mud: from the feet up, ragged at its edge, matte
                            float m = step(probeCoatH + (c_r - 0.5) * 0.12, c_mud * 0.45);
                            color.rgb = mix(color.rgb, vec3(0.30, 0.22, 0.15) * (0.8 + 0.4 * c_r) * c_light, 0.9 * m);
                        }
                        if (c_snow > 0.0) {   // snow: on the faces that look up, more of them the more it lies
                            // only on what faces well up (tilted over ~25 degrees from the upright), in 2 x 2 patches: the per-texel noise
                            // had speckled the half-tilted cloth, coming and going as a cloak swayed (10-05: it lay crooked)
                            float m = step(1.0 - c_snow, clamp((c_N.y - 0.42) * 2.6, 0.0, 1.0) + (c_r2 - 0.5) * 0.18) * step(0.45 - c_snow * 0.4, probeCoatH);   // the upper body (the feet too only when it lies deep)
                            color.rgb = mix(color.rgb, vec3(0.90, 0.92, 0.95) * c_light, 0.92 * m);
                        }
                        if (c_ice > 0.0) {   // ice: a pale blue rime in patches of a few texels, thicker on what faces up, glossy
                            float c_r3 = fract(sin(dot(floor(c_tx / 3.0), vec2(39.346, 11.135))) * 43758.5453);
                            float m = smoothstep(1.0 - c_ice, 1.15 - c_ice, c_r3 * 0.75 + 0.25 * clamp(c_N.y, 0.0, 1.0) + (c_r - 0.5) * 0.1);
                            color.rgb = mix(color.rgb, vec3(0.74, 0.85, 0.95) * c_light, 0.55 * m);
                            c_gloss = max(c_gloss, 0.8 * m);
                        }
                        if (c_wet > 0.0) {   // wet: darker, and a sheen
                            color.rgb *= mix(1.0, 0.62, c_wet);
                            c_gloss = max(c_gloss, c_wet * 0.7);
                        }
                        if (c_gloss > 0.0) {
                            vec3 c_H0 = normalize(normalize(Light0_Direction) + c_V), c_H1 = normalize(normalize(Light1_Direction) + c_V);
                            float c_spec = pow(max(dot(c_N, c_H0), 0.0), 24.0) + 0.6 * pow(max(dot(c_N, c_H1), 0.0), 24.0);
                            float c_fres = pow(1.0 - max(dot(c_N, c_V), 0.0), 4.0);
                            color.rgb += vec3(0.85, 0.9, 1.0) * (c_spec * 0.55 + c_fres * 0.25) * c_gloss * c_lit;
                        }
                    }
                """;
        int fog = src.indexOf("fragColor = linear_fog(", main);
        return src.substring(0, main) + decl + src.substring(main, fog) + shade.stripTrailing() + "\n    " + src.substring(fog);
    }

    // ------------------------------------------------------------------ binding

    private static final Map<RenderType, AbstractTexture> MASKS = new IdentityHashMap<>();
    private static final AbstractTexture NONE = new AbstractTexture() {
        @Override
        public void load(net.minecraft.server.packs.resources.ResourceManager rm) {}
    };
    private static final Map<Integer, int[]> PROGRAMS = new java.util.HashMap<>();

    static void forgetProgram(int id) {
        PROGRAMS.remove(id);
    }
    private static Field stateField, textureField;
    private static Method cutoutTexture;
    private static boolean reflectFailed;

    /** the type's texture (a composite render type's texture shard), or null */
    private static ResourceLocation texture(RenderType t) {
        if (reflectFailed) return null;
        try {
            if (stateField == null) {
                Class<?> composite = Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType");
                stateField = composite.getDeclaredField("state");
                stateField.setAccessible(true);
                textureField = Class.forName("net.minecraft.client.renderer.RenderType$CompositeState").getDeclaredField("textureState");
                textureField.setAccessible(true);
                cutoutTexture = Class.forName("net.minecraft.client.renderer.RenderStateShard$EmptyTextureStateShard").getDeclaredMethod("cutoutTexture");
                cutoutTexture.setAccessible(true);
            }
            if (!stateField.getDeclaringClass().isInstance(t)) return null;
            Object tex = textureField.get(stateField.get(t));
            Object o = cutoutTexture.invoke(tex);
            return o instanceof Optional<?> opt && opt.orElse(null) instanceof ResourceLocation rl ? rl : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectFailed = true;
            System.out.println("[sleep/opt] materials: render type texture not readable (" + e + "), off");
            return null;
        }
    }

    private static AbstractTexture mask(RenderType t) {
        AbstractTexture m = MASKS.get(t);
        if (m != null) return m;
        m = NONE;
        ResourceLocation rl = texture(t);
        if (rl != null && rl.getPath().endsWith(".png")) {
            ResourceLocation s = rl.withPath(rl.getPath().substring(0, rl.getPath().length() - 4) + "_s.png");
            if (Minecraft.getInstance().getResourceManager().getResource(s).isPresent()) m = Minecraft.getInstance().getTextureManager().getTexture(s);
            else missing++;
        }
        MASKS.put(t, m);
        return m;
    }

    /** resource reload: masks are looked up again */
    public static void clear() {
        MASKS.clear();
        MIPPED.clear();
    }

    private static final Map<RenderType, Boolean> MIPPED = new IdentityHashMap<>();
    public static long mipmapped;

    /**
     * A model texture of 128 px and up gets mipmaps (vanilla builds none for entities): minified, a dense texture
     * otherwise breaks into shimmering noise; magnified it stays nearest (crisp texels up close). Its mask too.
     */
    private static void mipmaps(RenderType t, AbstractTexture mask) {
        if (MIPPED.containsKey(t)) return;
        MIPPED.put(t, Boolean.TRUE);
        ResourceLocation rl = texture(t);
        if (rl == null) return;
        int active = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13C.GL_TEXTURE0 + UNIT);
        for (AbstractTexture tex : new AbstractTexture[] {Minecraft.getInstance().getTextureManager().getTexture(rl), mask}) {
            if (tex == null || tex == NONE) continue;
            RenderSystem.bindTexture(tex.getId());
            int w = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH);
            if (w < 128) continue;
            int levels = 31 - Integer.numberOfLeadingZeros(w);
            org.lwjgl.opengl.GL11C.glTexParameteri(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LEVEL, levels);
            org.lwjgl.opengl.GL30C.glGenerateMipmap(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D);
            org.lwjgl.opengl.GL11C.glTexParameteri(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11C.GL_LINEAR_MIPMAP_LINEAR);
            org.lwjgl.opengl.GL11C.glTexParameteri(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11C.GL_NEAREST);
            mipmapped++;
        }
        RenderSystem.activeTexture(active);
    }

    /** PartBatch, before a mode 1 / 3 draw of this type with this shader: true if a mask was bound (end() after the draw) */
    static boolean begin(ShaderInstance shader, RenderType t) {
        if (!ON || t == null) return false;
        AbstractTexture m = mask(t);
        mipmaps(t, m);
        if (m == NONE) return false;
        int[] loc = PROGRAMS.computeIfAbsent(shader.getId(), id -> new int[] {GL20C.glGetUniformLocation(id, "ProbeMaterial"), GL20C.glGetUniformLocation(id, "ProbeMaterialOn")});
        if (loc[0] < 0 || loc[1] < 0) return false;
        int active = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13C.GL_TEXTURE0 + UNIT);
        RenderSystem.bindTexture(m.getId());
        RenderSystem.activeTexture(active);
        GL20C.glUniform1i(loc[0], UNIT);
        GL20C.glUniform1i(loc[1], 1);
        bound++;
        return true;
    }

    static void end(ShaderInstance shader) {
        int[] loc = PROGRAMS.get(shader.getId());
        if (loc != null && loc[1] >= 0) GL20C.glUniform1i(loc[1], 0);
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "materials: %s, %d draws with a mask, %d types without, %d textures mipmapped (128 px and up)", ON ? "on" : "off", bound, missing, mipmapped);
    }
}
