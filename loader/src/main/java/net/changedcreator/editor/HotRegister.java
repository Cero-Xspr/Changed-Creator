package net.changedcreator.editor;

import com.mojang.logging.LogUtils;
import net.changedcreator.ChangedCreator;
import net.changedcreator.load.LatexFormDefinition;
import net.changedcreator.load.LatexFormLoader;
import net.ltxprogrammer.changed.entity.variant.TransfurVariant;
import net.ltxprogrammer.changed.init.ChangedRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import org.slf4j.Logger;

import java.util.List;

/**
 * Runtime registration of a latex form WITHOUT restarting the game.
 *
 * Forge's {@link ForgeRegistry} exposes official unfreeze()/register()/freeze()
 * methods; we build the TransfurVariant the same way startup registration does
 * and insert it into the changed:latex_variant registry at runtime.
 *
 * Caveats:
 *  - must be run on BOTH sides (server registers the form, clients register for
 *    rendering); the editor API runs on the client, so call it there too
 *  - already-connected players won't see the new form until they rejoin (Forge
 *    syncs registries only at connection time)
 */
public class HotRegister {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Registers a form at runtime; returns a user-facing message. Throws on failure. */
    public static String registerForm(String formId) throws RuntimeException {
        ResourceLocation fullId = formId.contains(":") ? ResourceLocation.tryParse(formId)
                : ResourceLocation.fromNamespaceAndPath(ChangedCreator.MODID, formId);
        if (fullId == null) throw new IllegalArgumentException("非法形态 id: " + formId);

        IForgeRegistry<TransfurVariant<?>> reg = ChangedRegistry.TRANSFUR_VARIANT.get();
        boolean existed = reg.containsKey(fullId);
        if (!(reg instanceof ForgeRegistry<?> forgeRegistry)) {
            throw new IllegalStateException("注册表不是 ForgeRegistry，无法热注册");
        }

        // Load the definition file
        java.nio.file.Path formsDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve(ChangedCreator.MODID).resolve("forms");
        java.nio.file.Path file = formsDir.resolve(ResourceLocation.tryParse(fullId.toString()).getPath() + ".json");
        LatexFormDefinition def;
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) {
            def = new com.google.gson.GsonBuilder().setLenient().create().fromJson(reader, LatexFormDefinition.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法读取形态文件 " + file + ": " + e.getMessage());
        }
        if (def == null || def.id == null) throw new IllegalStateException("形态文件无效: " + file);

        // Validate abilities NOW so a typo surfaces here instead of crashing /transfur later.
        if (def.abilities != null) {
            for (String a : def.abilities) {
                ResourceLocation aid = ResourceLocation.tryParse(a);
                if (aid == null || net.ltxprogrammer.changed.init.ChangedRegistry.ABILITY.get().getValue(aid) == null) {
                    throw new IllegalArgumentException("未知能力 '" + a + "'（可用能力列表见编辑器「能力」提示）");
                }
            }
        }

        TransfurVariant<?> variant = LatexFormLoader.buildVariant(def);

        @SuppressWarnings("unchecked")
        ForgeRegistry<TransfurVariant<?>> fr = (ForgeRegistry<TransfurVariant<?>>) forgeRegistry;
        synchronized (fr) {
            fr.unfreeze();
            try {
                if (existed) {
                    forceModifiable(fr);
                    fr.remove(fullId); // replace a stale/old registration (e.g. wrong base_entity)
                }
                fr.register(fullId, variant);
                LOGGER.info("[Changed Creator] HOT-REGISTERED form {} -> base {} ({})", fullId, def.baseEntity,
                        existed ? "replaced" : "new");
            } finally {
                fr.freeze();
            }
        }
        return "已热注册 " + fullId + "（" + (existed ? "覆盖旧版本" : "新增") + "）。已连接玩家需重进世界才能看到。";
    }

    private static final java.lang.reflect.Field IS_MODIFIABLE = findModifiableField();

    private static java.lang.reflect.Field findModifiableField() {
        try {
            java.lang.reflect.Field f = ForgeRegistry.class.getDeclaredField("isModifiable");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Cannot find ForgeRegistry.isModifiable", e);
        }
    }

    /** remove() is guarded by a final isModifiable flag; flip it via reflection. */
    private static void forceModifiable(ForgeRegistry<?> fr) {
        try {
            IS_MODIFIABLE.setBoolean(fr, true);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot make ForgeRegistry modifiable", e);
        }
    }

    /** All registered latex entity ids (for the base_entity dropdown). */
    public static List<String> latexEntityIds() {
        java.util.TreeSet<String> ids = new java.util.TreeSet<>();
        TransfurVariant.getPublicTransfurVariants().forEach(v -> {
            try {
                if (v.ctor.get() != null) {
                    ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(v.ctor.get());
                    if (key != null) ids.add(key.toString());
                }
            } catch (RuntimeException ignored) {
            }
        });
        return new java.util.ArrayList<>(ids);
    }

    /**
     * Removes a custom form at runtime: registry entry + config files (forms json,
     * appearance entry, exported texture, cached model). Returns a user-facing message.
     */
    public static String deleteForm(String formId) throws RuntimeException {
        ResourceLocation fullId = formId.contains(":") ? ResourceLocation.tryParse(formId)
                : ResourceLocation.fromNamespaceAndPath(ChangedCreator.MODID, formId);
        if (fullId == null) throw new IllegalArgumentException("非法形态 id: " + formId);

        java.nio.file.Path cfg = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID);
        String bare = fullId.getPath();

        // 1) registry entry (hot removal)
        IForgeRegistry<TransfurVariant<?>> reg = ChangedRegistry.TRANSFUR_VARIANT.get();
        if (reg.containsKey(fullId) && reg instanceof ForgeRegistry<?> forgeRegistry) {
            @SuppressWarnings("unchecked")
            ForgeRegistry<TransfurVariant<?>> fr = (ForgeRegistry<TransfurVariant<?>>) forgeRegistry;
            synchronized (fr) {
                fr.unfreeze();
                try {
                    forceModifiable(fr);
                    fr.remove(fullId);
                    LOGGER.info("[Changed Creator] HOT-REMOVED form {}", fullId);
                } finally {
                    fr.freeze();
                }
            }
        }

        // 2) config files
        java.util.List<String> removed = new java.util.ArrayList<>();
        try {
            java.nio.file.Path def = cfg.resolve("forms").resolve(bare + ".json");
            if (java.nio.file.Files.deleteIfExists(def)) removed.add("forms/" + bare + ".json");
        } catch (java.io.IOException ignored) {
        }
        try {
            java.nio.file.Path tex = cfg.resolve("textures").resolve(bare.replace('/', '_') + ".png");
            if (java.nio.file.Files.deleteIfExists(tex)) removed.add("textures/" + bare + ".png");
        } catch (java.io.IOException ignored) {
        }
        try {
            String safe = fullId.getNamespace() + "__" + bare.replace('/', '_');
            java.nio.file.Path model = cfg.resolve("models").resolve(safe + "__v2.json");
            if (java.nio.file.Files.deleteIfExists(model)) removed.add("models/缓存");
            java.nio.file.Path edited = cfg.resolve("models").resolve(safe + "__edit.json");
            if (java.nio.file.Files.deleteIfExists(edited)) removed.add("models/编辑");
        } catch (java.io.IOException ignored) {
        }
        try {
            java.nio.file.Path glow = cfg.resolve("textures").resolve(bare.replace('/', '_') + "_emissive.png");
            if (java.nio.file.Files.deleteIfExists(glow)) removed.add("textures/" + bare + "_emissive.png");
        } catch (java.io.IOException ignored) {
        }
        // appearance entry
        try {
            java.nio.file.Path appFile = cfg.resolve("appearance.json");
            if (java.nio.file.Files.isRegularFile(appFile)) {
                com.google.gson.JsonObject all;
                try (java.io.Reader r = java.nio.file.Files.newBufferedReader(appFile)) {
                    all = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
                }
                if (all.has(fullId.toString())) {
                    all.remove(fullId.toString());
                    java.nio.file.Files.writeString(appFile, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(all));
                    removed.add("appearance.json");
                }
            }
        } catch (Exception ignored) {
        }

        return "已删除 " + fullId + "（" + (removed.isEmpty() ? "仅移除注册" : String.join("、", removed)) + "）";
    }
}
