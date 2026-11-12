package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/**
 * Event sites rewritten after every other transformation of their class: a Mixin extension kept last in Mixin's list,
 * so its postApply sees the class after every mod's mixins and after MixinExtras' late injectors (@WrapOperation,
 * @WrapWithCondition - applied in MixinExtras' own extension). A post another mod wraps or redirects is no longer a post
 * call there and stays as it is; every other post is rewritten - a conflict cannot arise, whatever the mixins' order.
 * (1.1.1-1.1.2 rewrote in OptMixinPlugin.postApply, before those, and left every method another mod's mixins touch
 * alone: next to Collections Of Optimizations all 50 of ForgeHooksClient's posts.) When Mixin's extension list is out
 * of reach the rewrite stays in postApply, MixinTouch's index guarding it.
 */
public final class LateEventSites implements IExtension {
    private static final Set<String> MARKED = ConcurrentHashMap.newKeySet();
    private static Extensions registry;
    private static Field all, active;

    private LateEventSites() {}

    /** Adds the extension at the end of Mixin's list; false when that list is out of reach. */
    public static synchronized boolean register() {
        if (registry != null) return true;
        try {
            if (!(MixinEnvironment.getCurrentEnvironment().getActiveTransformer() instanceof IMixinTransformer t)
                    || !(t.getExtensions() instanceof Extensions ext)) return false;
            all = Extensions.class.getDeclaredField("extensions");
            active = Extensions.class.getDeclaredField("activeExtensions");
            all.setAccessible(true);
            active.setAccessible(true);
            LateEventSites self = new LateEventSites();
            ext.add(self);
            registry = ext;
            self.keepLast();
            System.out.println("[sleep/opt] event sites: rewritten after every other transformation (a Mixin extension after MixinExtras')");
            return true;
        } catch (Throwable e) {
            System.out.println("[sleep/opt] event sites: Mixin's extensions out of reach (" + e + "): rewritten in postApply, other mods' methods left alone");
            registry = null;
            return false;
        }
    }

    /** A class EventSiteMixin carries, rewritten at the end of its transformation. */
    public static boolean mark(String internalName) {
        if (registry == null) return false;
        MARKED.add(internalName);
        return true;
    }

    /**
     * Last in both of Mixin's lists: an extension registered later (MixinExtras keeps its late ones at the end of the
     * list whenever it adds one) would otherwise run after this one.
     */
    @SuppressWarnings("unchecked")
    private void keepLast() {
        try {
            List<IExtension> a = (List<IExtension>) active.get(registry);
            if (a != null && !a.isEmpty() && a.get(a.size() - 1) == this) return;
            List<IExtension> l = (List<IExtension>) all.get(registry);
            l.remove(this);
            l.add(this);
            List<IExtension> moved = new ArrayList<>(a == null ? List.of() : a);
            moved.remove(this);
            moved.add(this);
            active.set(registry, Collections.unmodifiableList(moved));
        } catch (Throwable ignored) {
        }
    }

    @Override public boolean checkActive(MixinEnvironment environment) { return true; }

    /** a class' posts per method before any mixin (only classes that post): the ones gone at the end other mods wrapped */
    private static final java.util.Map<String, java.util.Map<String, Integer>> BEFORE = new ConcurrentHashMap<>();

    @Override public void preApply(ITargetClassContext context) {
        keepLast();
        ClassNode cn = context.getClassNode();
        java.util.Map<String, Integer> posts = EventSiteTransformer.postsPerMethod(cn);
        if (posts != null) BEFORE.put(cn.name, posts);
    }

    @Override public void postApply(ITargetClassContext context) {
        ClassNode cn = context.getClassNode();
        java.util.Map<String, Integer> before = BEFORE.remove(cn.name);
        if (MARKED.remove(cn.name)) EventSiteTransformer.transform(cn, true, before);
        OverrideHooks.apply(cn);
        if (FieldLog.any()) FieldLog.rewrite(cn);   // reads checked at the merge (RenderFieldFlow, notes/71)
    }

    /** true when the extension runs (postApply after every other mixin) */
    public static boolean active() {
        return registry != null;
    }

    @Override public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {}
}
