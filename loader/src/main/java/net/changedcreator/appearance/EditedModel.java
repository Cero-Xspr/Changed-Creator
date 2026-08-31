package net.changedcreator.appearance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.changedcreator.editor.ModelExtractor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed editor model tree ({@code models/<id>__edit.json}) drawn in-game,
 * posed with the live vanilla {@link ModelPart} animation.
 */
public final class EditedModel {
    /** When true, {@code ModelPart.compile} skips vanilla cubes so this overlay is the body. */
    public static final ThreadLocal<Boolean> SKIP_VANILLA = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Temp tint (r,g,b in 0-255) applied as vertex color during the transform animation. */
    public static final ThreadLocal<float[]> TINT = ThreadLocal.withInitial(() -> null);

    private static net.minecraft.resources.ResourceLocation tintTex = null;

    /** Diagnostics: how many editor-created (uvLayout) blocks this model has. */
    private static int countCustom(Node node) {
        int n = 0;
        for (Cube c : node.cubes) if (c.isCustom) n++;
        for (Node ch : node.children) n += countCustom(ch);
        return n;
    }

    public int countCustomBlocks() {
        return root != null ? countCustom(root) : 0;
    }

    /** A 1x1 pure-white texture so TINT vertex color shows as a SOLID color (no image multiply). */
    public static net.minecraft.resources.ResourceLocation getTintTexture() {
        if (tintTex == null) {
            try {
                net.minecraft.client.renderer.texture.DynamicTexture dt =
                        new net.minecraft.client.renderer.texture.DynamicTexture(1, 1, false);
                dt.getPixels().setPixelRGBA(0, 0, 0xFFFFFFFF);
                dt.upload();
                tintTex = net.minecraft.client.Minecraft.getInstance().getTextureManager()
                        .register("changedcreator/tint_white", dt);
            } catch (Exception e) {
                return null;
            }
        }
        return tintTex;
    }

    public final Node root;
    private final long mtime;
    private static final Map<ResourceLocation, EditedModel> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private EditedModel(Node root, long mtime) {
        this.root = root;
        this.mtime = mtime;
    }

    public static EditedModel get(ResourceLocation formId) {
        if (formId == null) return null;
        var file = ModelExtractor.editFileFor(formId);
        if (!Files.isRegularFile(file)) {
            CACHE.remove(formId);
            return null;
        }
        long mt;
        try {
            mt = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return CACHE.get(formId);
        }
        EditedModel cached = CACHE.get(formId);
        if (cached != null && cached.mtime == mt) return cached;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject rootJson = json.has("root") && json.get("root").isJsonObject()
                    ? json.getAsJsonObject("root") : json;
            EditedModel parsed = new EditedModel(Node.parse(rootJson, true), mt);
            CACHE.put(formId, parsed);
            return parsed;
        } catch (Exception e) {
            return cached;
        }
    }

    public void render(Object model, PoseStack pose, VertexConsumer vc, int light, int overlay, float partialTick, boolean spawnOnly) {
        Map<String, ModelPart> named = collectNamedParts(model);
        renderNode(root, named, pose, vc, light, overlay, partialTick, spawnOnly);
    }

    /**
     * Morph-time render: draw only the subtree belonging to {@code limbPart}, inside
     * the space Changed set up for that limb in {@code renderMorphedLimb} (the stack
     * already carries the transitioned humanoid↔beast parent-chain matrix). Ancestors
     * contribute their transforms as usual, only the matched node's custom cubes (and
     * its descendants') get the spawn animation, everything vanilla is skipped (Changed
     * morphs the vanilla cubes itself).
     */
    public boolean renderLimbSubtree(Object model, ModelPart limbPart, PoseStack pose, VertexConsumer vc, int light, int overlay, float progress) {
        if (root == null || limbPart == null) return false;
        Map<String, ModelPart> named = collectNamedParts(model);
        return renderPruned(root, named, limbPart, false, pose, vc, light, overlay, progress);
    }

    private boolean renderPruned(Node node, Map<String, ModelPart> named, ModelPart part, boolean found,
                                 PoseStack pose, VertexConsumer vc, int light, int overlay, float progress) {
        ModelPart live = named.get(node.name);
        boolean here = !found && live == part;
        if (!found && !here && !subtreeHasPart(node, named, part)) return false;
        pose.pushPose();
        if (live != null) {
            live.translateAndRotate(pose);
        } else {
            pose.translate(node.pos[0] / 16f, node.pos[1] / 16f, node.pos[2] / 16f);
            pose.mulPose(Axis.ZP.rotation(node.rot[2]));
            pose.mulPose(Axis.YP.rotation(node.rot[1]));
            pose.mulPose(Axis.XP.rotation(node.rot[0]));
        }
        if (here || found) {
            float[] origin = new float[]{0, 0, 0}; // node-local origin = the animated parent joint
            for (Cube cube : node.cubes) {
                if (!cube.isCustom) continue;
                cube.emit(pose, vc, light, overlay, progress, origin);
            }
        }
        for (Node child : node.children) renderPruned(child, named, part, found || here, pose, vc, light, overlay, progress);
        pose.popPose();
        return here || found;
    }

    private static boolean subtreeHasPart(Node node, Map<String, ModelPart> named, ModelPart part) {
        if (named.get(node.name) == part) return true;
        for (Node ch : node.children) if (subtreeHasPart(ch, named, part)) return true;
        return false;
    }

    private void renderNode(Node node, Map<String, ModelPart> named, PoseStack pose, VertexConsumer vc, int light, int overlay, float partialTick, boolean spawnOnly) {
        ModelPart live = named.get(node.name);
        pose.pushPose();
        if (live != null) {
            live.translateAndRotate(pose);
        } else {
            pose.translate(node.pos[0] / 16f, node.pos[1] / 16f, node.pos[2] / 16f);
            pose.mulPose(Axis.ZP.rotation(node.rot[2]));
            pose.mulPose(Axis.YP.rotation(node.rot[1]));
            pose.mulPose(Axis.XP.rotation(node.rot[0]));
        }
        boolean liveMatched = live != null;
        // Spawn point = THIS node's local origin (0,0,0), i.e. the part's pivot where
        // it is attached to its parent. Because the live animated pose has already been
        // applied to `pose`, this origin tracks the ANIMATED parent joint — so blocks
        // grow outward from the moving parent, not from a static (pre-animation) spot.
        float[] origin = new float[]{0, 0, 0};
        // If this part did NOT match a live animated ModelPart, its pose is static —
        // spawning from it would look like the block appears at the OLD model position.
        // In that case skip the slide and show the block directly at its target.
        float spawnT = (liveMatched && spawnOnly) ? partialTick : 1f;
        for (Cube cube : node.cubes) {
            if (spawnOnly && !cube.isCustom) continue; // animation: only editor-created blocks
            cube.emit(pose, vc, light, overlay, spawnT, origin);
        }
        for (Node child : node.children) renderNode(child, named, pose, vc, light, overlay, partialTick, spawnOnly);
        pose.popPose();
    }

    private static Map<String, ModelPart> collectNamedParts(Object model) {
        Map<String, ModelPart> out = new LinkedHashMap<>();
        if (model == null) return out;
        if (model instanceof ModelPart mp) {
            out.put("root", mp);
            walkChildren(mp, out);
            return out;
        }
        for (Class<?> cls = model.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (!ModelPart.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(model);
                    if (v instanceof ModelPart part) {
                        out.put(f.getName(), part);
                        walkChildren(part, out);
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return out;
    }

    private static void walkChildren(ModelPart part, Map<String, ModelPart> out) {
        try {
            java.lang.reflect.Field children = ModelPart.class.getDeclaredField("children");
            children.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ModelPart> map = (Map<String, ModelPart>) children.get(part);
            if (map == null) return;
            for (var e : map.entrySet()) {
                out.putIfAbsent(e.getKey(), e.getValue());
                walkChildren(e.getValue(), out);
            }
        } catch (ReflectiveOperationException ignored) {
            try {
                java.lang.reflect.Field children = ModelPart.class.getDeclaredField("f_104213_");
                children.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, ModelPart> map = (Map<String, ModelPart>) children.get(part);
                if (map == null) return;
                for (var e : map.entrySet()) {
                    out.putIfAbsent(e.getKey(), e.getValue());
                    walkChildren(e.getValue(), out);
                }
            } catch (ReflectiveOperationException ignored2) {
            }
        }
    }

    private static boolean isDuplicatePartName(String name) {
        if (name == null || name.isEmpty()) return true;
        if (name.startsWith("f_")) return true;
        return name.equals("rootModelPart") || name.equals("NULL_PART") || name.equals("root");
    }

    public static final class Node {
        public final String name;
        public final float[] pos;
        public final float[] rot;
        public final List<Cube> cubes;
        public final List<Node> children;

        Node(String name, float[] pos, float[] rot, List<Cube> cubes, List<Node> children) {
            this.name = name;
            this.pos = pos;
            this.rot = rot;
            this.cubes = cubes;
            this.children = children;
        }

        static Node parse(JsonObject o, boolean rootLevel) {
            String name = o.has("name") ? o.get("name").getAsString() : "part";
            float[] pos = vec3(o, "pos", 0, 0, 0);
            float[] rot = vec3(o, "rot", 0, 0, 0);
            List<Cube> cubes = new ArrayList<>();
            if (o.has("cubes") && o.get("cubes").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("cubes")) {
                    if (e.isJsonObject()) cubes.add(Cube.parse(e.getAsJsonObject()));
                }
            }
            List<Node> children = new ArrayList<>();
            if (o.has("children") && o.get("children").isJsonArray()) {
                List<JsonObject> siblings = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("children")) {
                    if (e.isJsonObject()) siblings.add(e.getAsJsonObject());
                }
                // root level: drop SRG/duplicate overlays + any child whose name is
                // already contained by another sibling's subtree (e.g. a root Tail
                // that also lives under Torso).
                if (rootLevel) {
                    siblings = dedupeSiblings(siblings);
                }
                for (JsonObject child : siblings) {
                    children.add(parse(child, false));
                }
            }
            return new Node(name, pos, rot, cubes, children);
        }

        private static List<JsonObject> dedupeSiblings(List<JsonObject> siblings) {
            List<JsonObject> filtered = new ArrayList<>();
            for (JsonObject s : siblings) {
                String n = s.has("name") ? s.get("name").getAsString() : "";
                if (isDuplicatePartName(n)) continue;
                filtered.add(s);
            }
            List<JsonObject> kept = new ArrayList<>();
            for (int i = 0; i < filtered.size(); i++) {
                JsonObject s = filtered.get(i);
                String name = s.has("name") ? s.get("name").getAsString() : "";
                if (nameInsideOtherSibling(name, filtered, i)) continue;
                kept.add(s);
            }
            return kept;
        }

        private static boolean nameInsideOtherSibling(String name, List<JsonObject> siblings, int self) {
            for (int i = 0; i < siblings.size(); i++) {
                if (i == self) continue;
                if (subtreeContains(siblings.get(i), name)) return true;
            }
            return false;
        }

        private static boolean subtreeContains(JsonObject node, String name) {
            String n = node.has("name") ? node.get("name").getAsString() : "";
            if (n.equals(name)) return true;
            if (node.has("children") && node.get("children").isJsonArray()) {
                for (JsonElement e : node.getAsJsonArray("children")) {
                    if (e.isJsonObject() && subtreeContains(e.getAsJsonObject(), name)) return true;
                }
            }
            return false;
        }
    }

    public static final class Cube {
        public final float[] min;
        public final float[] max;
        public final float[] rot;
        public final float[] animOrigin; // spawn point during transform (part-local), nullable
        public final boolean isCustom;   // editor-created block (has uvLayout) -> gets the spawn animation
        public final List<Face> faces;

        Cube(float[] min, float[] max, float[] rot, float[] animOrigin, boolean isCustom, List<Face> faces) {
            this.min = min;
            this.max = max;
            this.rot = rot;
            this.animOrigin = animOrigin;
            this.isCustom = isCustom;
            this.faces = faces;
        }

        static Cube parse(JsonObject o) {
            float[] min = vec3(o, "min", -4, -4, -4);
            float[] max = vec3(o, "max", 4, 4, 4);
            float[] rot = vec3(o, "rot", 0, 0, 0);
            float[] animOrigin = null;
            if (o.has("animOrigin") && o.get("animOrigin").isJsonArray()) {
                animOrigin = vec3(o, "animOrigin", 0, 0, 0);
            }
            // Editor-created cubes carry uvLayout (allocated by the UV packer);
            // vanilla-extracted ones do not. Custom blocks get the spawn animation
            // even if they were saved before the animOrigin field existed.
            boolean isCustom = o.has("uvLayout");
            List<Face> faces = new ArrayList<>();
            if (o.has("faces") && o.get("faces").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("faces")) {
                    if (e.isJsonObject()) {
                        Face f = Face.parse(e.getAsJsonObject());
                        if (f != null) faces.add(f);
                    }
                }
            }
            return new Cube(min, max, rot, animOrigin, isCustom, faces);
        }

        void emit(PoseStack pose, VertexConsumer vc, int light, int overlay, float partialTick, float[] origin) {
            pose.pushPose();
            float cx = (min[0] + max[0]) / 2f;
            float cy = (min[1] + max[1]) / 2f;
            float cz = (min[2] + max[2]) / 2f;
            // Spawn window 3/6..5/6 of the linearly-timed 6s progression (= seconds
            // 3..5). Blocks slide from the part center and grow; no hover hold.
            if (isCustom && partialTick < 1f) {
                float t = (partialTick - 0.5f) / (5f / 6f - 0.5f);
                t = Math.max(0f, Math.min(1f, t));
                t = 1f - (1f - t) * (1f - t) * (1f - t); // easeOutCubic
                cx = origin[0] + (cx - origin[0]) * t;
                cy = origin[1] + (cy - origin[1]) * t;
                cz = origin[2] + (cz - origin[2]) * t;
                pose.translate(cx / 16f, cy / 16f, cz / 16f);
                float s = Math.max(0.05f, t);
                pose.scale(s, s, s);
            } else {
                pose.translate(cx / 16f, cy / 16f, cz / 16f);
            }
            if (rot[2] != 0) pose.mulPose(Axis.ZP.rotation(rot[2]));
            pose.mulPose(Axis.YP.rotation(rot[1]));
            pose.mulPose(Axis.XP.rotation(rot[0]));
            PoseStack.Pose last = pose.last();
            Matrix4f m = last.pose();
            Matrix3f nrm = last.normal();
            if (faces.isEmpty()) {
                emitBox(m, nrm, vc, light, overlay,
                        (min[0] - cx) / 16f, (min[1] - cy) / 16f, (min[2] - cz) / 16f,
                        (max[0] - cx) / 16f, (max[1] - cy) / 16f, (max[2] - cz) / 16f);
            } else {
                for (Face f : faces) f.emit(m, nrm, vc, light, overlay, cx, cy, cz);
            }
            pose.popPose();
        }
    }

    public static final class Face {
        public final float[][] p;
        public final float[][] uv;
        public final float[] normal;

        Face(float[][] p, float[][] uv, float[] normal) {
            this.p = p;
            this.uv = uv;
            this.normal = normal;
        }

        static Face parse(JsonObject o) {
            if (!o.has("verts") || !o.get("verts").isJsonArray()) return null;
            JsonArray verts = o.getAsJsonArray("verts");
            if (verts.size() < 4) return null;
            float[][] p = new float[4][3];
            float[][] uv = new float[4][2];
            for (int i = 0; i < 4; i++) {
                JsonObject v = verts.get(i).getAsJsonObject();
                JsonArray pp = v.getAsJsonArray("p");
                JsonArray uu = v.getAsJsonArray("uv");
                p[i][0] = pp.get(0).getAsFloat();
                p[i][1] = pp.get(1).getAsFloat();
                p[i][2] = pp.get(2).getAsFloat();
                uv[i][0] = uu.get(0).getAsFloat();
                uv[i][1] = uu.get(1).getAsFloat();
            }
            float[] n = new float[]{0, 1, 0};
            if (o.has("normal") && o.get("normal").isJsonArray()) {
                JsonArray na = o.getAsJsonArray("normal");
                n[0] = na.get(0).getAsFloat();
                n[1] = na.get(1).getAsFloat();
                n[2] = na.get(2).getAsFloat();
            }
            return new Face(p, uv, n);
        }

        void emit(Matrix4f m, Matrix3f nrm, VertexConsumer vc, int light, int overlay, float cx, float cy, float cz) {
            // Recompute the normal from the quad winding so mirror-flipped faces
            // shade toward the real light (never trust a possibly-mirrored normal).
            float ax = (p[1][0] - p[0][0]), ay = (p[1][1] - p[0][1]), az = (p[1][2] - p[0][2]);
            float bx = (p[2][0] - p[0][0]), by = (p[2][1] - p[0][1]), bz = (p[2][2] - p[0][2]);
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= len < 1e-6 ? 1 : len;
            ny /= len < 1e-6 ? 1 : len;
            nz /= len < 1e-6 ? 1 : len;
            Vector3f nn = new Vector3f(nx, ny, nz);
            nrm.transform(nn);
            // Entity RenderType draws QUADS (4 verts), not triangles.
            float[] tint = TINT.get();
            int cr = tint != null ? (int) tint[0] : 255;
            int cg = tint != null ? (int) tint[1] : 255;
            int cb = tint != null ? (int) tint[2] : 255;
            for (int i = 0; i < 4; i++) {
                float x = (p[i][0] - cx) / 16f;
                float y = (p[i][1] - cy) / 16f;
                float z = (p[i][2] - cz) / 16f;
                vc.vertex(m, x, y, z)
                        .color(cr, cg, cb, 255)
                        .uv(uv[i][0], uv[i][1])
                        .overlayCoords(overlay)
                        .uv2(light)
                        .normal(nn.x, nn.y, nn.z)
                        .endVertex();
            }
        }
    }

    private static void emitBox(Matrix4f m, Matrix3f nrm, VertexConsumer vc, int light, int overlay,
                                float x0, float y0, float z0, float x1, float y1, float z1) {
        quad(m, nrm, vc, light, overlay, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1);
        quad(m, nrm, vc, light, overlay, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1);
        quad(m, nrm, vc, light, overlay, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0);
        quad(m, nrm, vc, light, overlay, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0);
        quad(m, nrm, vc, light, overlay, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0);
        quad(m, nrm, vc, light, overlay, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0);
    }

    private static void quad(Matrix4f m, Matrix3f nrm, VertexConsumer vc, int light, int overlay,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float nx, float ny, float nz) {
        Vector3f n = new Vector3f(nx, ny, nz);
        nrm.transform(n);
        float[] tint = TINT.get();
        int cr = tint != null ? (int) tint[0] : 255;
        int cg = tint != null ? (int) tint[1] : 255;
        int cb = tint != null ? (int) tint[2] : 255;
        float[] xs = {x0, x1, x2, x3};
        float[] ys = {y0, y1, y2, y3};
        float[] zs = {z0, z1, z2, z3};
        float[] u = {0, 1, 1, 0};
        float[] v = {0, 0, 1, 1};
        for (int i = 0; i < 4; i++) {
            vc.vertex(m, xs[i], ys[i], zs[i])
                    .color(cr, cg, cb, 255)
                    .uv(u[i], v[i])
                    .overlayCoords(overlay)
                    .uv2(light)
                    .normal(n.x, n.y, n.z)
                    .endVertex();
        }
    }

    private static float[] vec3(JsonObject o, String key, float dx, float dy, float dz) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return new float[]{dx, dy, dz};
        JsonArray a = o.getAsJsonArray(key);
        return new float[]{
                a.size() > 0 ? a.get(0).getAsFloat() : dx,
                a.size() > 1 ? a.get(1).getAsFloat() : dy,
                a.size() > 2 ? a.get(2).getAsFloat() : dz
        };
    }
}
