package dev.lull.opt;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.InputStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ChunkMultiDraw: the vanilla chunk vertex shaders get the multi-draw offset path (see ChunkMultiDraw.patch); the
 * entity shaders the residents' path. The source is patched as it is read (the local it is stored in), not in the
 * preprocessor call: Oculus / Iris redirect that call, and a redirect of ours there gave way - the patches and with them
 * every VRAM resident were off next to them (client_entities with Oculus: 241 fps, 0 resident draws).
 */
@Mixin(Program.class)
public abstract class ChunkShaderPatchMixin {
    @Unique private static Program.Type sleep$type;
    @Unique private static String sleep$name;

    @Inject(method = "compileShaderInternal", at = @At("HEAD"))
    private static void sleep$which(Program.Type type, String name, InputStream in, String sourceName, GlslPreprocessor preprocessor,
            CallbackInfoReturnable<Integer> cir) {
        sleep$type = type;   // shaders compile on the render thread, one at a time
        sleep$name = name;
    }

    /** the source as read, before the patches (the fallback compiles it), and whether a patch changed it */
    @Unique private static String sleep$original;
    @Unique private static boolean sleep$changed;

    @ModifyVariable(method = "compileShaderInternal", at = @At("STORE"), index = 5)
    private static String sleep$patch(String src) {
        sleep$original = src;
        String out = src == null ? null : dev.lull.optsupport.ShaderPatches.apply(sleep$type, sleep$name, src);
        sleep$changed = out != null && !out.equals(src);
        return out;
    }

    /**
     * A patched shader the driver does not compile: vanilla's source compiled instead (ShaderPatches.failed), not a
     * crash. Vanilla's own failure is rethrown as it was.
     */
    @WrapMethod(method = "compileShaderInternal")
    private static int sleep$fallback(Program.Type type, String name, InputStream in, String sourceName, GlslPreprocessor preprocessor,
            Operation<Integer> original) throws java.io.IOException {
        try {
            return original.call(type, name, in, sourceName, preprocessor);
        } catch (Exception e) {
            if (!(e instanceof java.io.IOException io) || !sleep$changed || sleep$original == null) throw e;
            String vanilla = sleep$original;
            sleep$changed = false;
            String msg = io.getMessage();
            int colon = msg == null ? -1 : msg.indexOf(") : ");
            dev.lull.optsupport.ShaderPatches.failed(type, name, colon < 0 ? msg : msg.substring(colon + 4));
            // the preprocessor remembers the files it imported and leaves them out the second time (fog_distance undefined):
            // its sets of imported paths emptied for the vanilla attempt
            for (Class<?> k = preprocessor.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (java.lang.reflect.Field f : k.getDeclaredFields())
                    if (java.util.Set.class.isAssignableFrom(f.getType()) && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                        try {
                            f.setAccessible(true);
                            ((java.util.Set<?>) f.get(preprocessor)).clear();
                        } catch (Throwable t) { /* a set we cannot reach: the attempt may still fail as before */ }
            return original.call(type, name, new java.io.ByteArrayInputStream(vanilla.getBytes(java.nio.charset.StandardCharsets.UTF_8)), sourceName, preprocessor);
        }
    }
}
