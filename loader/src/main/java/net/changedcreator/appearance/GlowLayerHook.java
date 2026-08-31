package net.changedcreator.appearance;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Attaches {@link ChangedCreatorEmissiveLayer} to every LivingEntityRenderer so
 * custom forms can render their glow texture.
 *
 * FMLClientSetup runs BEFORE Minecraft constructs (no renderers exist yet), so
 * attachment is lazy: the first client tick retries until it succeeds.
 */
public class GlowLayerHook {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean attached = false;

    public static boolean isAttached() {
        return attached;
    }

    /** Called on client ticks until the renderers exist and are wired. */
    public static void ensureAttachedOnce() {
        if (attached) return;
        if (Minecraft.getInstance() == null || Minecraft.getInstance().getEntityRenderDispatcher() == null) return;
        attached = attachAll();
    }

    /** Returns true when every LivingEntityRenderer got the glow layer. */
    public static boolean attachAll() {
        try {
            Map<?, ?> renderers = net.changedcreator.editor.ModelExtractor.allRenderers();
            if (renderers.isEmpty()) return false;
            int added = 0;
            for (Object r : renderers.values()) {
                if (!(r instanceof LivingEntityRenderer<?, ?> ler)) continue;
                // The player renderer overlays the editor model during the transfur
                // transition (which renders the player model, not a ChangedEntity).
                if (r instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer) {
                    try {
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        net.minecraft.client.renderer.entity.RenderLayerParent parent =
                                (net.minecraft.client.renderer.entity.RenderLayerParent) ler;
                        net.minecraft.client.renderer.entity.layers.RenderLayer playerLayer =
                                new net.changedcreator.appearance.EditedPlayerLayer(parent);
                        ler.addLayer(playerLayer);
                        added++;
                    } catch (RuntimeException e) {
                        LOGGER.warn("[Changed Creator] Could not attach player edited layer: {}", e.getMessage());
                    }
                    continue;
                }
                // Only Changed's own renderers handle ChangedEntity instances; attaching
                // our glow layer to vanilla renderers (zombies etc.) crashes with a
                // ClassCastException when they render a non-Changed entity.
                if (!r.getClass().getName().startsWith("net.ltxprogrammer.changed.client.renderer.")) continue;
                @SuppressWarnings({"unchecked", "rawtypes"})
                net.minecraft.client.renderer.entity.RenderLayerParent parent =
                        (net.minecraft.client.renderer.entity.RenderLayerParent) ler;
                @SuppressWarnings({"unchecked", "rawtypes"})
                net.minecraft.client.renderer.entity.layers.RenderLayer layer =
                        new ChangedCreatorEmissiveLayer(parent);
                ler.addLayer(layer);
                @SuppressWarnings({"unchecked", "rawtypes"})
                net.minecraft.client.renderer.entity.layers.RenderLayer edited =
                        new EditedModelLayer(parent);
                ler.addLayer(edited);
                added++;
            }
            LOGGER.info("[Changed Creator] Attached glow layer to {} renderers", added);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Changed Creator] Failed to attach glow layers: {}", e.getMessage());
            return false;
        }
    }
}
