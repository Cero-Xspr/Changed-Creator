package net.changedcreator.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.changedcreator.ChangedCreator;
import net.changedcreator.appearance.FormAppearance;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Import/export of editor files. An exported file is a single JSON:
 * <pre>{@code
 * {
 *   "changedcreator_export": 1,
 *   "id": "changedcreator:my_form",
 *   "definition": { ... full LatexFormDefinition ... },
 *   "appearance": { "tint": "#ff0000", "texture": "changedcreator:textures/entity/xxx.png" },
 *   "texturePngBase64": "..."
 * }
 * }</pre>
 * Import writes the definition (restart to take effect), the appearance entry
 * (hot) and the PNG texture into the config directory.
 */
public class EditorImportExport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record ImportResult(String id, java.util.List<String> hotApplied,
                               java.util.List<String> needRestart, boolean ok, String message) {
    }

    /** Imports a single exported editor file into the config directory. */
    public static ImportResult importFile(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject payload = JsonParser.parseReader(reader).getAsJsonObject();
            if (!payload.has("changedcreator_export")) {
                return new ImportResult(null, java.util.List.of(), java.util.List.of(),
                        false, "不是编辑器导出文件（缺少 changedcreator_export）");
            }
            String id = payload.has("id") ? payload.get("id").getAsString() : null;
            if (id == null || id.isBlank()) {
                return new ImportResult(null, java.util.List.of(), java.util.List.of(),
                        false, "文件缺少 id");
            }
            var hotApplied = new java.util.ArrayList<String>();
            var needRestart = new java.util.ArrayList<String>();

            // 1) definition
            if (payload.has("definition") && payload.get("definition").isJsonObject()) {
                Path formsDir = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("forms");
                Files.createDirectories(formsDir);
                Files.writeString(formsDir.resolve(id + ".json"),
                        GSON.toJson(payload.getAsJsonObject("definition")));
                needRestart.add("definition");
            }

            // 2) appearance
            if (payload.has("appearance") && payload.get("appearance").isJsonObject()) {
                Path appearanceFile = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("appearance.json");
                Map<String, Object> all = readAppearanceMap(appearanceFile);
                all.put(id, GSON.fromJson(payload.getAsJsonObject("appearance"), Object.class));
                Files.writeString(appearanceFile, GSON.toJson(all));
                hotApplied.add("tint/texture");
                FormAppearance.forceReload();
            }

            // 3) texture png
            if (payload.has("texturePngBase64") && payload.get("texturePngBase64").isJsonPrimitive()) {
                try {
                    byte[] png = Base64.getDecoder().decode(payload.get("texturePngBase64").getAsString());
                    Path texDir = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("textures");
                    Files.createDirectories(texDir);
                    String safe = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                    Files.write(texDir.resolve(safe.replace('/', '_') + ".png"), png);
                    hotApplied.add("texture.png");
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Bad base64 texture in {}", file);
                }
            }

            return new ImportResult(id, hotApplied, needRestart, true,
                    "导入成功：" + id + "（" + String.join("、", hotApplied) + " 已热生效；" +
                            String.join("、", needRestart) + " 需重启）");
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to import {}", file, e);
            return new ImportResult(null, java.util.List.of(), java.util.List.of(),
                    false, "导入失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readAppearanceMap(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Object> map = GSON.fromJson(reader, Map.class);
            return map != null ? map : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The directory users drop exported files into for in-game import. */
    public static Path importsDir() {
        return FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("imports");
    }
}
