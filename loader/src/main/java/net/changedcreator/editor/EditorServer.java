package net.changedcreator.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.changedcreator.ChangedCreator;
import net.changedcreator.appearance.FormAppearance;
import net.ltxprogrammer.changed.init.ChangedRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded local HTTP server for the 胶兽编辑器 (latex-form editor) WebUI.
 *
 * Serves:
 *   - the editor web app (static resources from the mod jar)
 *   - REST API for listing/editing forms, fetching model trees and textures,
 *     saving edited forms back to the config directory, and hot-applying
 *     appearance changes (tint / texture).
 *
 * The server listens on a random free port and is started at client setup.
 * The in-game editor screen shows the URL.
 */
public class EditorServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static HttpServer server;
    private static int port = -1;

    public static boolean isRunning() {
        return server != null;
    }

    public static int getPort() {
        return port;
    }

    public static String getUrl() {
        return isRunning() ? "http://127.0.0.1:" + port : "";
    }

    private static final int PREFERRED_PORT = 28654;

    /** Starts the embedded HTTP server on a fixed port (28654), falling back to a random free port. */
    public static void start() {
        if (server != null) return;
        try {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", PREFERRED_PORT), 0);
            } catch (java.io.IOException e) {
                LOGGER.warn("[Changed Creator] Port {} busy, using a random port", PREFERRED_PORT);
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            }
            port = server.getAddress().getPort();
            registerRoutes(server);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            LOGGER.info("[Changed Creator] Editor WebUI available at {}", getUrl());
        } catch (IOException e) {
            LOGGER.error("[Changed Creator] Failed to start editor server", e);
            server = null;
            port = -1;
        }
    }

    private static void registerRoutes(HttpServer srv) {
        srv.createContext("/", EditorServer::handleRoot);
        srv.createContext("/web/", EditorServer::handleStatic);
        srv.createContext("/api/", EditorServer::handleApi);
    }

    // ------------------------------------------------------------------ routing

    private static void handleRoot(HttpExchange ex) throws IOException {
        if ("/".equals(ex.getRequestURI().getPath())) {
            serveResource(ex, "index.html", "text/html");
        } else {
            sendText(ex, 404, "Not found");
        }
    }

    private static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(); // e.g. /web/app.js
        String resource = path.substring("/web/".length());
        String mime = switch (ext(resource)) {
            case "js" -> "application/javascript";
            case "css" -> "text/css";
            case "html" -> "text/html";
            default -> "application/octet-stream";
        };
        serveResource(ex, resource, mime);
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1);
    }

    private static void serveResource(HttpExchange ex, String resource, String mime) throws IOException {
        try (InputStream in = EditorServer.class.getClassLoader()
                .getResourceAsStream("assets/changedcreator/editor/" + resource)) {
            if (in == null) {
                sendText(ex, 404, "Not found: " + resource);
                return;
            }
            byte[] data = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
        }
    }

    // ------------------------------------------------------------------ API

    private static void handleApi(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(); // e.g. /api/forms
        try {
            if (path.equals("/api/forms")) {
                if ("GET".equals(ex.getRequestMethod())) apiListForms(ex);
                else sendText(ex, 405, "Method not allowed");
            } else if (path.equals("/api/examples")) {
                apiListExamples(ex);
            } else if (path.equals("/api/entity-types")) {
                sendJson(ex, 200, HotRegister.latexEntityIds());
            } else if (path.equals("/api/abilities")) {
                java.util.List<String> abilities = new ArrayList<>();
                net.ltxprogrammer.changed.init.ChangedRegistry.ABILITY.get().getKeys().forEach(k -> abilities.add(k.toString()));
                java.util.Collections.sort(abilities);
                sendJson(ex, 200, abilities);
            } else if (path.equals("/api/hot-register")) {
                if ("POST".equals(ex.getRequestMethod())) apiHotRegister(ex);
                else sendText(ex, 405, "Method not allowed");
            } else if (path.equals("/api/delete-form")) {
                if ("POST".equals(ex.getRequestMethod())) apiDeleteForm(ex);
                else sendText(ex, 405, "Method not allowed");
            } else if (path.equals("/api/save")) {
                if ("POST".equals(ex.getRequestMethod())) apiSave(ex);
                else sendText(ex, 405, "Method not allowed");
            } else if (path.equals("/api/hot-apply")) {
                if ("POST".equals(ex.getRequestMethod())) apiHotApply(ex);
                else sendText(ex, 405, "Method not allowed");
            } else if (path.startsWith("/api/forms/")) {
                String id = path.substring("/api/forms/".length());
                if (id.endsWith("/model")) {
                    apiModel(ex, id.substring(0, id.length() - "/model".length()));
                } else if (id.endsWith("/texture.png")) {
                    apiTexture(ex, id.substring(0, id.length() - "/texture.png".length()));
                } else if (id.endsWith("/emissive.png")) {
                    apiTextureSuffixed(ex, id.substring(0, id.length() - "/emissive.png".length()), "_emissive");
                } else if (id.endsWith("/debug")) {
                    apiDebug(ex, id.substring(0, id.length() - "/debug".length()));
                } else {
                    apiForm(ex, id);
                }
            } else {
                sendText(ex, 404, "Unknown API: " + path);
            }
        } catch (RuntimeException e) {
            LOGGER.error("API error on {}", path, e);
            sendText(ex, 500, "Server error: " + e.getMessage());
        }
    }

    /** All editable custom forms: config/changedcreator/forms/*.json merged with appearance. */
    private static void apiListForms(HttpExchange ex) throws IOException {
        List<JsonObject> list = new ArrayList<>();
        Path formsDir = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("forms");
        if (Files.isDirectory(formsDir)) {
            try (var stream = Files.newDirectoryStream(formsDir, "*.json")) {
                for (Path file : stream) {
                    JsonObject def = readJson(file);
                    if (def == null) continue;
                    String id = def.has("id") ? def.get("id").getAsString() : null;
                    if (id == null) continue;
                    JsonObject item = new JsonObject();
                    item.addProperty("id", fullFormId(id));
                    item.addProperty("file", file.getFileName().toString());
                    item.add("definition", def);
                    item.add("appearance", appearanceFor(fullFormId(id)));
                    list.add(item);
                }
            }
        }
        sendJson(ex, 200, list);
    }

    private static void apiListExamples(HttpExchange ex) throws IOException {
        List<JsonObject> list = new ArrayList<>();
        ChangedRegistry.TRANSFUR_VARIANT.get().getEntries().forEach(entry -> {
            // entry.getKey() is a ResourceKey; .location() gives "changed:form_xxx"
            String id = entry.getKey().location().toString();
            if (id.startsWith(ChangedCreator.MODID + ":")) return; // our own forms are not "examples"
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("name", id);
            item.add("appearance", appearanceFor(id));
            list.add(item);
        });
        sendJson(ex, 200, list);
    }

    /** Single form: definition + appearance + cached model + texture availability. */
    private static void apiForm(HttpExchange ex, String id) throws IOException {
        String fullId = fullFormId(id);
        ResourceLocation formId = ResourceLocation.tryParse(fullId);
        if (formId == null) {
            sendText(ex, 400, "Bad form id: " + id);
            return;
        }
        Path file = formFile(fullId);
        JsonObject def = Files.isRegularFile(file) ? readJson(file) : null;
        JsonObject out = new JsonObject();
        out.addProperty("id", fullId);
        // Original/external forms have no config file; return an empty definition so
        // the editor can still preview their model/texture (editing them creates a copy).
        out.add("definition", def != null ? def : new JsonObject());
        out.add("appearance", appearanceFor(fullId));
        String model = ModelExtractor.getModelJson(formId);
        if (model != null) out.add("model", JsonParser.parseString(model));
        out.addProperty("textureUrl", "/api/forms/" + fullId + "/texture.png");
        out.addProperty("hasTexture", textureFile(formId) != null || defaultTexture(formId) != null);
        sendJson(ex, 200, out);
    }

    private static void apiModel(HttpExchange ex, String id) throws IOException {
        ResourceLocation formId = ResourceLocation.tryParse(fullFormId(id));
        if (formId == null) {
            sendText(ex, 400, "Bad form id: " + id);
            return;
        }
        String model = ModelExtractor.getModelJson(formId);
        if (model == null) {
            sendText(ex, 404, "No model cached for " + id + " (enter a world as this form to generate it)");
            return;
        }
        sendText(ex, 200, model, "application/json");
    }

    /** Diagnostic: shows each step of model extraction to locate failures (e.g. red_wolf). */
    private static void apiDebug(HttpExchange ex, String id) throws IOException {
        ResourceLocation formId = ResourceLocation.tryParse(fullFormId(id));
        if (formId == null) {
            sendText(ex, 400, "Bad form id: " + id);
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("formId", id);
        try {
            var variant = ChangedRegistry.TRANSFUR_VARIANT.get().getValue(formId);
            out.addProperty("variant", variant != null ? variant.toString() : null);
            if (variant != null && variant.ctor.get() != null) {
                var type = variant.ctor.get();
                out.addProperty("entityType", type.toString());
                var renderer = ModelExtractor.getRendererFor(type);
                out.addProperty("renderer", renderer != null ? renderer.getClass().getName() : null);
                if (renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> ler) {
                    Object model = ler.getModel();
                    out.addProperty("modelClass", model != null ? model.getClass().getName() : null);
                    out.addProperty("modelFieldCount", model != null
                            ? ModelExtractor.countModelPartFields(model) : -1);
                }
            }
            out.addProperty("cachedModel", ModelExtractor.getModelJson(formId) != null);
        } catch (RuntimeException e) {
            out.addProperty("error", e.toString());
        }
        sendJson(ex, 200, out);
    }

    /** Serves the form's texture PNG: exported override, else the jar's default for the base entity. */
    private static void apiTexture(HttpExchange ex, String id) throws IOException {
        apiTextureSuffixed(ex, id, "");
    }

    /** Serves a suffixed texture PNG ("" for main, "_emissive" for glow) from config/changedcreator/textures/. */
    private static void apiTextureSuffixed(HttpExchange ex, String id, String suffix) throws IOException {
        ResourceLocation formId = ResourceLocation.tryParse(fullFormId(id));
        byte[] data = null;
        if (formId != null) {
            Path override = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("textures")
                    .resolve(formId.getPath().replace('/', '_') + suffix + ".png");
            if (Files.isRegularFile(override)) {
                data = Files.readAllBytes(override);
            } else if (suffix.isEmpty()) {
                ResourceLocation def = defaultTexture(formId);
                if (def != null) {
                    try (InputStream in = EditorServer.class.getClassLoader()
                            .getResourceAsStream("assets/" + def.getNamespace() + "/" + def.getPath())) {
                        if (in != null) data = in.readAllBytes();
                    }
                }
            }
        }
        if (data == null) {
            sendText(ex, 404, "No texture for " + id + suffix);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    /** Hot-register a form at runtime (no restart). Body: {"id": "xspr"}. */
    private static void apiHotRegister(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject payload;
        try {
            payload = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            sendText(ex, 400, "Invalid JSON body: " + e.getMessage());
            return;
        }
        String id = payload.has("id") ? payload.get("id").getAsString() : null;
        if (id == null || id.isBlank()) {
            sendText(ex, 400, "Missing form id");
            return;
        }
        try {
            String message = HotRegister.registerForm(fullFormId(id));
            JsonObject out = new JsonObject();
            out.addProperty("registered", true);
            out.addProperty("message", message);
            sendJson(ex, 200, out);
        } catch (RuntimeException e) {
            JsonObject out = new JsonObject();
            out.addProperty("registered", false);
            out.addProperty("message", "热注册失败：" + e.getMessage());
            sendJson(ex, 200, out);
        }
    }

    /** Delete a custom form: registry + config files. Body: {"id": "xspr"}. */
    private static void apiDeleteForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject payload;
        try {
            payload = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            sendText(ex, 400, "Invalid JSON body: " + e.getMessage());
            return;
        }
        String id = payload.has("id") ? payload.get("id").getAsString() : null;
        if (id == null || id.isBlank()) {
            sendText(ex, 400, "Missing form id");
            return;
        }
        try {
            String message = HotRegister.deleteForm(fullFormId(id));
            JsonObject out = new JsonObject();
            out.addProperty("deleted", true);
            out.addProperty("message", message);
            sendJson(ex, 200, out);
        } catch (RuntimeException e) {
            JsonObject out = new JsonObject();
            out.addProperty("deleted", false);
            out.addProperty("message", "删除失败：" + e.getMessage());
            sendJson(ex, 200, out);
        }
    }

    /**
     * Save an edited form: writes forms/<id>.json, appearance.json and the PNG texture
     * (if provided). Returns what hot-applied now vs. what needs a restart.
     */
    private static void apiSave(HttpExchange ex) throws IOException {        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject payload;
        try {
            payload = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            sendText(ex, 400, "Invalid JSON body: " + e.getMessage());
            return;
        }
        if (!payload.has("definition") || !payload.get("definition").isJsonObject()) {
            sendText(ex, 400, "Missing 'definition' object");
            return;
        }
        JsonObject def = payload.getAsJsonObject("definition");
        String id = def.has("id") ? def.get("id").getAsString() : null;
        if (id == null || id.isBlank()) {
            sendText(ex, 400, "Missing form id");
            return;
        }
        List<String> hotApplied = new ArrayList<>();
        List<String> needRestart = new ArrayList<>();

        // 1) definition file (id / base_entity / transfur_mode / abilities / properties) -> restart
        Path formsDir = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("forms");
        Files.createDirectories(formsDir);
        Path defFile = formsDir.resolve(id + ".json");
        Files.writeString(defFile, GSON.toJson(def));
        needRestart.add("definition");

        // 2) appearance (tint / texture) -> hot
        JsonObject appearance = payload.has("appearance") && payload.get("appearance").isJsonObject()
                ? payload.getAsJsonObject("appearance") : new JsonObject();
        Path appearanceFile = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("appearance.json");
        Map<String, Object> all = readAppearanceMap(appearanceFile);
        if (appearance.size() == 0) all.remove(fullFormId(id));
        else all.put(fullFormId(id), GSON.fromJson(appearance, Object.class));
        Files.writeString(appearanceFile, GSON.toJson(all));
        hotApplied.add("tint/texture");

        // 3) texture PNG (base64) -> hot (reload via /reload or resource manager)
        if (payload.has("texturePng") && payload.get("texturePng").isJsonPrimitive()) {
            try {
                byte[] png = java.util.Base64.getDecoder().decode(payload.get("texturePng").getAsString());
                ResourceLocation formId = ResourceLocation.tryParse(id);
                if (formId != null) {
                    Path texDir = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("textures");
                    Files.createDirectories(texDir);
                    Files.write(texDir.resolve(formId.getPath().replace('/', '_') + ".png"), png);
                    hotApplied.add("texture.png");
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Bad base64 texture for {}", id);
            }
        }
        // 3c) edited model tree -> config/changedcreator/models/<ns>__<path>__edit.json
        if (payload.has("model") && payload.get("model").isJsonObject()) {
            ResourceLocation formId = ResourceLocation.tryParse(fullFormId(id));
            if (formId != null) {
                String modelJson = GSON.toJson(payload.getAsJsonObject("model"));
                if (net.changedcreator.editor.ModelExtractor.saveEditedModel(formId, modelJson)) {
                    hotApplied.add("model");
                } else {
                    needRestart.add("model(write-failed)");
                }
            }
        }

        // 3b) glow/emissive PNG (base64) -> config/changedcreator/textures/<id>_emissive.png
        if (payload.has("emissivePng") && payload.get("emissivePng").isJsonPrimitive()) {
            try {
                byte[] png = java.util.Base64.getDecoder().decode(payload.get("emissivePng").getAsString());
                ResourceLocation formId = ResourceLocation.tryParse(id);
                if (formId != null) {
                    Path texDir = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("textures");
                    Files.createDirectories(texDir);
                    Files.write(texDir.resolve(formId.getPath().replace('/', '_') + "_emissive.png"), png);
                    hotApplied.add("emissive.png");
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Bad base64 emissive texture for {}", id);
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("saved", true);
        out.add("hotApplied", GSON.toJsonTree(hotApplied));
        out.add("needRestart", GSON.toJsonTree(needRestart));
        out.addProperty("message", "已保存。tint/贴图/模型编辑立即写入配置；形态定义需热注册或重启。");
        sendJson(ex, 200, out);
    }

    /** Re-apply appearance file immediately (FormAppearance polls mtime anyway; this just forces a check). */
    private static void apiHotApply(HttpExchange ex) throws IOException {
        // Trigger a forced reload of appearance.json into FormAppearance.
        FormAppearance.forceReload();
        JsonObject out = new JsonObject();
        out.addProperty("applied", true);
        sendJson(ex, 200, out);
    }

    // ------------------------------------------------------------------ helpers

    /** Form ids in config files are bare (e.g. "red_wolf"); resolve them to full ids ("changedcreator:red_wolf"). */
    private static String fullFormId(String id) {
        return (id == null || id.contains(":")) ? id : ChangedCreator.MODID + ":" + id;
    }

    /** The bare id used for config file names ("changedcreator:red_wolf" -> "red_wolf"). */
    private static String bareId(String id) {
        if (id == null) return null;
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }

    private static Path formFile(String id) {
        return FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("forms").resolve(bareId(id) + ".json");
    }

    private static JsonObject readJson(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** appearance entry for a form id (empty object if none). */
    private static JsonObject appearanceFor(String id) {
        Path appearanceFile = FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("appearance.json");
        try (Reader reader = Files.newBufferedReader(appearanceFile)) {
            JsonObject all = JsonParser.parseReader(reader).getAsJsonObject();
            if (all.has(id) && all.get(id).isJsonObject()) return all.getAsJsonObject(id);
        } catch (IOException | RuntimeException ignored) {
        }
        return new JsonObject();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readAppearanceMap(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Object> map = GSON.fromJson(reader, Map.class);
            return map != null ? map : new java.util.LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            return new java.util.LinkedHashMap<>();
        }
    }

    /** Exported texture for a form: config/changedcreator/textures/<path>.png */
    private static Path textureFile(ResourceLocation formId) {
        return FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID)
                .resolve("textures").resolve(formId.getPath().replace('/', '_') + ".png");
    }

    /** Default texture of the form's base entity renderer, resolved from the registry (needs a world). */
    private static ResourceLocation defaultTexture(ResourceLocation formId) {
        var variant = ChangedRegistry.TRANSFUR_VARIANT.get().getValue(formId);
        if (variant == null || variant.ctor.get() == null) return null;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return null;
        try {
            var entity = variant.ctor.get().create(level);
            var renderer = net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            return renderer.getTextureLocation(entity);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void sendJson(HttpExchange ex, int code, Object payload) throws IOException {
        sendText(ex, code, GSON.toJson(payload), "application/json");
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        sendText(ex, code, text, "text/plain; charset=utf-8");
    }

    private static void sendText(HttpExchange ex, int code, String text, String mime) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }
}
