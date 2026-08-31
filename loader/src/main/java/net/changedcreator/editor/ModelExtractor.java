package net.changedcreator.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.changedcreator.ChangedCreator;
import net.ltxprogrammer.changed.entity.ChangedEntity;
import net.ltxprogrammer.changed.entity.variant.TransfurVariantInstance;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts the {@link ModelPart} tree of a latex form's renderer model and
 * serializes it to a JSON structure the WebUI can rebuild with Three.js.
 *
 * The tree is extracted live from a transfurred player's variant entity
 * (requires being in a world, so the entity dispatcher can build the renderer).
 * It is cached to {@code config/changedcreator/models/<formId>.json} so the
 * editor keeps working from the main menu afterwards.
 *
 * JSON shape:
 * <pre>{@code
 * {
 *   "formId": "changedcreator:red_wolf",
 *   "root": {
 *     "name": "root",
 *     "pos": [x, y, z], "rot": [x, y, z], "scale": [x, y, z],
 *     "cubes": [ { "min": [..], "max": [..] } ],
 *     "children": [ { ... } ]
 *   }
 * }
 * }</pre>
 */
public class ModelExtractor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path modelsDir() {
        return FMLPaths.CONFIGDIR.get().resolve(ChangedCreator.MODID).resolve("models");
    }

    /** Cache path for the given form id, or null if the id is unusable as a file name. */
    private static Path cacheFileFor(ResourceLocation formId) {
        String safe = formId.getNamespace() + "__" + formId.getPath().replace('/', '_');
        return modelsDir().resolve(safe + "__v2.json");
    }

    /**
     * User-edited model tree. Takes priority over the extracted cache so entering a
     * world (which re-extracts the vanilla model) cannot clobber editor changes.
     */
    public static Path editFileFor(ResourceLocation formId) {
        String safe = formId.getNamespace() + "__" + formId.getPath().replace('/', '_');
        return modelsDir().resolve(safe + "__edit.json");
    }

    /** Write the editor's model tree. Returns true on success. */
    public static boolean saveEditedModel(ResourceLocation formId, String json) {
        try {
            Files.createDirectories(modelsDir());
            Files.writeString(editFileFor(formId), json);
            LOGGER.info("Saved edited model for {} to config/changedcreator/models/", formId);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to save edited model for {}: {}", formId, e.getMessage());
            return false;
        }
    }

    /** Model-tree JSON for a form: edited copy if present, then extracted cache, then live extraction. */
    public static String getModelJson(ResourceLocation formId) {
        Path edited = editFileFor(formId);
        if (Files.isRegularFile(edited)) {
            try {
                return Files.readString(edited);
            } catch (IOException e) {
                LOGGER.warn("Failed to read edited model {}: {}", edited, e.getMessage());
            }
        }
        Path file = cacheFileFor(formId);
        if (Files.isRegularFile(file)) {
            try {
                return Files.readString(file);
            } catch (IOException e) {
                LOGGER.warn("Failed to read cached model {}: {}", file, e.getMessage());
            }
        }
        // 1) Live extraction from a transfurred player (world required).
        String live = extractFromAnyPlayer(formId);
        if (live != null) return live;
        // 2) Extract straight from the client renderer registry - works even from the
        //    main menu (no world needed), so original examples preview immediately.
        return extractFromRegistry(formId);
    }

    // ------------------------------------------------------------------
    // Registry-based extraction: grab the renderer directly from the client
    // EntityRenderDispatcher (its renderers map is fully registered as soon as
    // Minecraft is constructed - even from the main menu). Original examples and
    // any registered form can therefore be previewed without entering a world.

    private static java.util.Map<net.minecraft.world.entity.EntityType<?>, net.minecraft.client.renderer.entity.EntityRenderer<?>> registryRenderers;

    private static final java.lang.reflect.Field DISPATCHER_RENDERERS = dispatcherRenderersField();

    private static java.lang.reflect.Field dispatcherRenderersField() {
        try {
            java.lang.reflect.Field f = net.minecraft.client.renderer.entity.EntityRenderDispatcher.class.getDeclaredField("renderers");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            try {
                java.lang.reflect.Field f = net.minecraft.client.renderer.entity.EntityRenderDispatcher.class.getDeclaredField("f_114362_");
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e2) {
                throw new RuntimeException("Cannot find EntityRenderDispatcher.renderers", e2);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static java.util.Map<net.minecraft.world.entity.EntityType<?>, net.minecraft.client.renderer.entity.EntityRenderer<?>> allRenderers() {
        if (registryRenderers != null) return registryRenderers;
        try {
            var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            registryRenderers = (java.util.Map) DISPATCHER_RENDERERS.get(dispatcher);
            return registryRenderers != null ? registryRenderers : java.util.Map.of();
        } catch (RuntimeException | IllegalAccessException e) {
            LOGGER.warn("Failed to access dispatcher renderers: {}", e.getMessage());
            return java.util.Map.of();
        }
    }

    private static String extractFromRegistry(ResourceLocation formId) {
        try {
            var variant = net.ltxprogrammer.changed.init.ChangedRegistry.TRANSFUR_VARIANT.get().getValue(formId);
            LOGGER.info("[CC-extract] {} variant={}", formId, variant);
            if (variant == null || variant.ctor.get() == null) return null;
            var type = variant.ctor.get();
            LOGGER.info("[CC-extract] {} entityType={}", formId, type);
            var renderer = getRendererFor(type);
            LOGGER.info("[CC-extract] {} renderer={}", formId, renderer != null ? renderer.getClass().getName() : null);
            if (!(renderer instanceof LivingEntityRenderer<?, ?> livingRenderer)) return null;
            Object model = livingRenderer.getModel();
            LOGGER.info("[CC-extract] {} model={} fields={}", formId,
                    model != null ? model.getClass().getName() : null, countModelPartFields(model));
            if (model == null) return null;
            return serialize(formId, model);
        } catch (RuntimeException e) {
            LOGGER.warn("Registry extraction failed for {}: {}", formId, e.getMessage());
            return null;
        }
    }

    /** Looks up the entity renderer for an entity type (null if not registered). */
    public static net.minecraft.client.renderer.entity.EntityRenderer<?> getRendererFor(
            net.minecraft.world.entity.EntityType<?> type) {
        return allRenderers().get(type);
    }

    /** Counts the ModelPart fields reachable on a model object (for diagnostics). */
    public static int countModelPartFields(Object model) {
        int n = 0;
        for (Class<?> cls = model.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (ModelPart.class.isAssignableFrom(f.getType())) n++;
            }
        }
        return n;
    }

    /** Attempts to extract the model tree from any player currently transfurred into the given form. */
    private static String extractFromAnyPlayer(ResourceLocation formId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        Player local = mc.player;
        TransfurVariantInstance<?> self = ProcessTransfur.getPlayerTransfurVariant(local);
        if (self != null && formId.equals(self.getFormId())) {
            return extractAndCache(local, self);
        }
        // Fall back to scanning nearby players.
        for (Player player : mc.level.players()) {
            TransfurVariantInstance<?> vi = ProcessTransfur.getPlayerTransfurVariant(player);
            if (vi != null && formId.equals(vi.getFormId())) {
                return extractAndCache(player, vi);
            }
        }
        return null;
    }

    private static String extractAndCache(Player player, TransfurVariantInstance<?> vi) {
        try {
            ChangedEntity entity = vi.getChangedEntity();
            if (entity == null) return null;
            var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            if (!(renderer instanceof LivingEntityRenderer<?, ?> livingRenderer)) return null;
            Object model = livingRenderer.getModel();
            return serialize(vi.getFormId(), model);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to extract model for {}: {}", vi.getFormId(), e.getMessage());
            return null;
        }
    }

    /**
     * Serializes the renderer model. The model is usually NOT a bare ModelPart -
     * Changed uses HumanoidModel subclasses whose head/body/arms/legs are ModelPart
     * fields. We collect every ModelPart reachable from the model object (its own
     * fields + children) under a synthetic "root" node so the WebUI can rebuild it.
     */
    private static String serialize(ResourceLocation formId, Object model) {
        Map<String, Object> rootJson = new LinkedHashMap<>();
        rootJson.put("formId", formId.toString());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "root");
        root.put("pos", new float[]{0, 0, 0});
        root.put("rot", new float[]{0, 0, 0});
        root.put("scale", new float[]{1, 1, 1});
        List<Map<String, Object>> children = new ArrayList<>();
        java.util.IdentityHashMap<ModelPart, Boolean> seen = new java.util.IdentityHashMap<>();

        if (model instanceof ModelPart part) {
            seen.put(part, Boolean.TRUE);
            Map<String, Object> p = partToJson(part, seen);
            p.put("name", "root");
            children.add(p);
        }
        // Prefer named HumanoidModel fields (head/body/arms/legs). Skip the synthetic
        // rootModelPart / SRG fields — they duplicate the same ModelPart tree and
        // would render a second stacked copy in-game.
        java.util.List<java.lang.reflect.Field> fields = new ArrayList<>();
        for (Class<?> cls = model.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (ModelPart.class.isAssignableFrom(f.getType())) fields.add(f);
            }
        }
        fields.sort((a, b) -> Integer.compare(fieldPriority(a.getName()), fieldPriority(b.getName())));
        for (java.lang.reflect.Field f : fields) {
            String fname = f.getName();
            if (shouldSkipModelField(fname)) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(model);
                if (v instanceof ModelPart mp) {
                    if (seen.containsKey(mp)) continue;
                    seen.put(mp, Boolean.TRUE);
                    Map<String, Object> p = partToJson(mp, seen);
                    p.put("name", fname);
                    children.add(p);
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        root.put("cubes", new ArrayList<>());
        root.put("children", children);
        rootJson.put("root", root);

        String json = GSON.toJson(rootJson);
        // Never overwrite a user-edited model with a freshly extracted vanilla tree.
        if (Files.isRegularFile(editFileFor(formId))) {
            LOGGER.info("Skipped cache write for {} (edited model exists)", formId);
            return json;
        }
        try {
            Files.createDirectories(modelsDir());
            try (Writer w = Files.newBufferedWriter(cacheFileFor(formId))) {
                w.write(json);
            }
            LOGGER.info("Cached model tree for {} to config/changedcreator/models/", formId);
        } catch (IOException e) {
            LOGGER.warn("Failed to cache model for {}: {}", formId, e.getMessage());
        }
        return json;
    }

    private static boolean shouldSkipModelField(String name) {
        if (name == null) return true;
        if (name.startsWith("f_")) return true; // SRG / intermediary duplicates
        if (name.equals("rootModelPart") || name.equals("NULL_PART") || name.equals("root")) return true;
        return false;
    }

    /** Named body parts first so they win IdentityHashMap seen-checks against the duplicate root. */
    private static int fieldPriority(String name) {
        if (name == null) return 50;
        return switch (name) {
            case "head", "Head" -> 0;
            case "body", "Body", "torso", "Torso" -> 1;
            case "rightArm", "RightArm", "leftArm", "LeftArm" -> 2;
            case "rightLeg", "RightLeg", "leftLeg", "LeftLeg" -> 3;
            default -> shouldSkipModelField(name) ? 90 : 10;
        };
    }

    /** Serializes a ModelPart (recursively) into a plain JSON-friendly map. */
    private static Map<String, Object> partToJson(ModelPart part) {
        return partToJson(part, new java.util.IdentityHashMap<>());
    }

    private static Map<String, Object> partToJson(ModelPart part, java.util.IdentityHashMap<ModelPart, Boolean> seen) {
        seen.put(part, Boolean.TRUE);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", "part");
        json.put("pos", new float[]{part.x, part.y, part.z});
        json.put("rot", new float[]{part.xRot, part.yRot, part.zRot});
        json.put("scale", new float[]{part.xScale, part.yScale, part.zScale});

        List<Map<String, Object>> cubes = new ArrayList<>();
        for (ModelPart.Cube cube : cubesOf(part)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("min", new float[]{cube.minX, cube.minY, cube.minZ});
            c.put("max", new float[]{cube.maxX, cube.maxY, cube.maxZ});
            c.put("faces", facesOf(cube)); // per-face vertices + UVs for exact texturing
            cubes.add(c);
        }
        json.put("cubes", cubes);

        List<Map<String, Object>> children = new ArrayList<>();
        for (Map.Entry<String, ModelPart> child : childrenOf(part).entrySet()) {
            ModelPart ch = child.getValue();
            if (seen.containsKey(ch)) continue;
            Map<String, Object> childJson = partToJson(ch, seen);
            childJson.put("name", child.getKey());
            children.add(childJson);
        }
        json.put("children", children);
        return json;
    }

    // ------------------------------------------------------------------
    // ModelPart.cubes / ModelPart.children are private in 1.20.1 with no
    // getters; access them reflectively (mojmap name in dev, SRG name at runtime).

    private static final java.lang.reflect.Field CUBES_FIELD = fieldOf("cubes", "f_104212_");
    private static final java.lang.reflect.Field CHILDREN_FIELD = fieldOf("children", "f_104213_");

    private static java.lang.reflect.Field fieldOf(String mojmapName, String srgName) {
        try {
            java.lang.reflect.Field f = ModelPart.class.getDeclaredField(mojmapName);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            try {
                java.lang.reflect.Field f = ModelPart.class.getDeclaredField(srgName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e2) {
                throw new RuntimeException("Cannot find ModelPart field " + mojmapName + "/" + srgName, e2);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ModelPart.Cube> cubesOf(ModelPart part) {
        try {
            return (List<ModelPart.Cube>) CUBES_FIELD.get(part);
        } catch (IllegalAccessException e) {
            return java.util.List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> childrenOf(ModelPart part) {
        try {
            return (Map<String, ModelPart>) CHILDREN_FIELD.get(part);
        } catch (IllegalAccessException e) {
            return java.util.Map.of();
        }
    }

    // ------------------------------------------------------------------
    // Cube face extraction (exact UV texturing).
    // ModelPart.Cube.polygons -> Polygon.vertices -> Vertex{pos, u, v}.
    // Polygon/Vertex are private nested classes in 1.20.1, so everything is
    // resolved dynamically off the instance classes (with a per-class cache).

    /** Resolves a declared field (mojmap or SRG name) on the instance's class hierarchy, cached per class+field. */
    private static final java.util.Map<String, java.lang.reflect.Field> FIELD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.lang.reflect.Field fieldIn(Object instance, String mojmapName, String srgName) {
        String key = instance.getClass().getName() + "#" + mojmapName;
        java.lang.reflect.Field f = FIELD_CACHE.get(key);
        if (f != null) return f;
        for (String name : new String[]{mojmapName, srgName}) {
            for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    FIELD_CACHE.put(key, f);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return null;
    }

    /** Exports each polygon face of a cube: 4 vertices (pos + uv) and the face normal. */
    private static List<Map<String, Object>> facesOf(ModelPart.Cube cube) {
        List<Map<String, Object>> faces = new ArrayList<>();
        try {
            Object[] polygons = (Object[]) fieldIn(cube, "polygons", "f_104341_").get(cube);
            if (polygons == null) return faces;
            for (Object polygon : polygons) {
                Object[] vertices = (Object[]) fieldIn(polygon, "vertices", "f_104359_").get(polygon);
                if (vertices == null || vertices.length != 4) continue;
                Map<String, Object> face = new LinkedHashMap<>();
                List<Map<String, Object>> verts = new ArrayList<>();
                for (Object vertex : vertices) {
                    var pos = (org.joml.Vector3f) fieldIn(vertex, "pos", "f_104371_").get(vertex);
                    float u = (Float) fieldIn(vertex, "u", "f_104372_").get(vertex);
                    float v = (Float) fieldIn(vertex, "v", "f_104373_").get(vertex);
                    Map<String, Object> vv = new LinkedHashMap<>();
                    vv.put("p", new float[]{pos.x, pos.y, pos.z});
                    vv.put("uv", new float[]{u, v});
                    verts.add(vv);
                }
                var normal = (org.joml.Vector3f) fieldIn(polygon, "normal", "f_104360_").get(polygon);
                face.put("verts", verts);
                if (normal != null) face.put("normal", new float[]{normal.x, normal.y, normal.z});
                faces.add(face);
            }
        } catch (IllegalAccessException | RuntimeException e) {
            LOGGER.warn("Failed to extract cube faces: {}", e.getMessage());
        }
        return faces;
    }
}
