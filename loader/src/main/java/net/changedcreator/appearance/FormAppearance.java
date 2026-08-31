package net.changedcreator.appearance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import net.changedcreator.ChangedCreator;
import net.ltxprogrammer.changed.entity.ChangedEntity;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.ltxprogrammer.changed.util.Color3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Appearance overrides for latex forms, loaded from
 * {@code <minecraft-dir>/config/changedcreator/appearance.json}.
 *
 * This is the extension point for custom looks without touching the base entity:
 *   - "texture": override the renderer texture for this form id (entity + player forms)
 *   - "tint":    override the latex tint color (entity forms)
 *   - "model":   reserved for future per-form model overrides (not implemented yet)
 *
 * Example:
 * <pre>{@code
 * {
 *   "changedcreator:red_wolf": {
 *     "texture": "changedcreator:textures/entity/red_wolf.png",
 *     "tint": "#ff0000"
 *   }
 * }
 * }</pre>
 */
public class FormAppearance {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    /** Player currently being rendered as a latex form (set by FormRenderHandlerMixin). */
    public static final ThreadLocal<Player> RENDERING_PLAYER = new ThreadLocal<>();

    /** Latex-body entity instances currently belonging to a transfurred player (for async particle rendering). */
    private static final java.util.Map<Entity, Player> ENTITY_TO_PLAYER = new java.util.WeakHashMap<>();

    /** Records that the given latex entity belongs to the given player (set during player-form rendering). */
    public static void recordEntityPlayer(Entity entity, Player player) {
        if (entity != null && player != null) ENTITY_TO_PLAYER.put(entity, player);
    }

    /** Resolves the transfurred player owning the given entity (may be null). */
    public static Player getPlayerOfEntity(Entity entity) {
        return ENTITY_TO_PLAYER.get(entity);
    }

    private static final Map<ResourceLocation, Entry> OVERRIDES = new HashMap<>();

    /** appearance.json path + last-modified stamp for hot reload (mtime polling). */
    private static Path configFile;
    private static long lastLoadedMtime = -1;
    private static long lastCheckTime = 0;

    public static class Entry {
        public String texture;
        public String tint;
        /** Reserved for future model-layer overrides. */
        public String model;
    }

    public static void load() {
        configFile = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("appearance.json");
        reloadIfChanged(true);
    }

    /** Forces a re-read of appearance.json on the next lookup (used by the editor's hot-apply). */
    public static void forceReload() {
        lastLoadedMtime = -1;
        lastCheckTime = 0;
    }

    /**
     * Hot-reload: re-read appearance.json when its file modification time changes.
     * Called from every lookup; polls at most once every 2 seconds.
     */
    private static void reloadIfChanged(boolean force) {
        if (configFile == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastCheckTime < 2000) return;
        lastCheckTime = now;
        try {
            long mtime = Files.getLastModifiedTime(configFile).toMillis();
            if (!force && mtime == lastLoadedMtime) return;
            lastLoadedMtime = mtime;
            readFrom(configFile);
        } catch (IOException ignored) {
            // file absent/removed -> keep last known overrides
        }
    }

    private static void readFrom(Path file) {
        if (!Files.isRegularFile(file)) {
            OVERRIDES.clear();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Entry> raw = GSON.fromJson(reader, new com.google.gson.reflect.TypeToken<Map<String, Entry>>() {}.getType());
            OVERRIDES.clear();
            if (raw == null) return;
            for (Map.Entry<String, Entry> e : raw.entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                if (id == null) {
                    LOGGER.error("appearance.json: invalid form id '{}'", e.getKey());
                    continue;
                }
                if (e.getValue() == null) {
                    LOGGER.warn("appearance.json: empty entry for '{}'", e.getKey());
                    continue;
                }
                OVERRIDES.put(id, e.getValue());
            }
            LOGGER.info("Loaded {} appearance override(s) from {}", OVERRIDES.size(), file);
        } catch (JsonSyntaxException | IOException e) {
            LOGGER.error("Failed to load appearance.json: {}", e.getMessage());
        }
    }

    /** Resolve the form id of an entity being rendered: latex entity, or a transfurred player. */
    private static ResourceLocation formIdOf(Entity entity) {
        if (entity instanceof ChangedEntity changedEntity) {
            var variant = changedEntity.getSelfVariant();
            if (variant != null) return variant.getFormId();
            return null;
        }
        if (entity instanceof Player player) {
            var instance = ProcessTransfur.getPlayerTransfurVariant(player);
            if (instance != null) return instance.getFormId();
            return null;
        }
        return null;
    }

    /** Returns the override texture for the given rendered entity, or null. */
    public static ResourceLocation getTextureOverride(Entity entity) {
        ResourceLocation formId = formIdOf(entity);
        return formId != null ? getTextureForForm(formId) : null;
    }

    /** Looks up a texture override by form id (formIdOf independent). */
    public static ResourceLocation getTextureForForm(ResourceLocation formId) {
        reloadIfChanged(false);
        Entry entry = OVERRIDES.get(formId);
        if (entry != null && entry.texture != null && !entry.texture.isBlank()) {
            ResourceLocation rl = ResourceLocation.tryParse(entry.texture);
            if (rl != null) return rl;
        }
        // External texture exported by the editor: config/changedcreator/textures/<path>.png
        return ensureExternalTexture(formId);
    }

    /** Glow/emissive texture for a form: config/changedcreator/textures/<path>_emissive.png (null if none). */
    public static ResourceLocation getEmissiveForForm(ResourceLocation formId) {
        return ensureExternalTextureSuffixed(formId, "_emissive");
    }

    /** Texture manager registration cache (formId -> file mtime). */
    private static final Map<ResourceLocation, Long> EXTERNAL_MTIMES = new HashMap<>();

    private static synchronized ResourceLocation ensureExternalTexture(ResourceLocation formId) {
        return ensureExternalTextureSuffixed(formId, "");
    }

    /**
     * If the editor exported a PNG for this form (config/changedcreator/textures/<path><suffix>.png),
     * register it as a runtime texture (DynamicTexture) and return its ResourceLocation.
     * Re-registers when the file changes (hot texture updates).
     */
    private static synchronized ResourceLocation ensureExternalTextureSuffixed(ResourceLocation formId, String suffix) {
        try {
            Path file = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("textures")
                    .resolve(formId.getPath().replace('/', '_') + suffix + ".png");
            if (!Files.isRegularFile(file)) return null;
            long mtime = Files.getLastModifiedTime(file).toMillis();
            ResourceLocation cacheKey = ResourceLocation.fromNamespaceAndPath(formId.getNamespace(), formId.getPath() + suffix);
            Long last = EXTERNAL_MTIMES.get(cacheKey);
            ResourceLocation extRl = ResourceLocation.fromNamespaceAndPath(ChangedCreator.MODID,
                    "textures/external/" + formId.getPath().replace('/', '_') + suffix);
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.getTextureManager() == null) return null;
            if (last == null || last != mtime) {
                try (var in = Files.newInputStream(file)) {
                    var img = com.mojang.blaze3d.platform.NativeImage.read(in);
                    var tex = new net.minecraft.client.renderer.texture.DynamicTexture(img);
                    mc.getTextureManager().register(extRl, tex);
                }
                EXTERNAL_MTIMES.put(cacheKey, mtime);
                LOGGER.info("Registered external texture {} -> {}", extRl, file);
            }
            return extRl;
        } catch (Exception e) {
            LOGGER.warn("Failed to load external texture for {}: {}", formId, e.getMessage());
            return null;
        }
    }

    /** Returns the override tint color for the given latex entity, or null. */
    public static Color3 getTintOverride(ChangedEntity entity) {
        var variant = entity.getSelfVariant();
        return variant != null ? getTintForForm(variant.getFormId()) : null;
    }

    /** Looks up a tint override by form id (formIdOf independent). */
    public static Color3 getTintForForm(ResourceLocation formId) {
        reloadIfChanged(false);
        Entry entry = OVERRIDES.get(formId);
        if (entry == null || entry.tint == null || entry.tint.isBlank()) return null;
        try {
            String hex = entry.tint.startsWith("#") ? entry.tint.substring(1) : entry.tint;
            return Color3.fromInt(Integer.parseInt(hex, 16));
        } catch (RuntimeException e) {
            LOGGER.warn("appearance.json: invalid tint '{}' for {}", entry.tint, formId);
            return null;
        }
    }
}
