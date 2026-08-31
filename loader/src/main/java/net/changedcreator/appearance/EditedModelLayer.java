package net.changedcreator.appearance;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.ltxprogrammer.changed.client.tfanimations.TransfurAnimator;
import net.ltxprogrammer.changed.entity.ChangedEntity;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * Draws the editor-saved model ({@code __edit.json}) in place of vanilla cubes.
 *
 * <p>During the transfur transition the timeline has TWO segments (measured from
 * the {@code [CC-morph]} logs: the Changed morph ends around progress 0.84):
 * <ul>
 *   <li><b>morph segment (progress &lt; 0.85)</b>: {@code TransfurAnimatorMixin} draws
 *       the editor's custom blocks per-limb inside the animated morph space; the body
 *       is Changed's morph geometry. This layer draws nothing (drawing here would
 *       duplicate the blocks in the static capture pose).</li>
 *   <li><b>post-morph segment (progress ≥ 0.85)</b>: Changed stops morph rendering and
 *       renders the capture-phase beast, where vanilla cubes are skipped — without this
 *       layer NOTHING would draw the body. So the layer takes over: a textured base
 *       model plus a tinted, 1.04-inflated cover whose custom blocks keep their spawn
 *       state. In the last ~0.6s (progress 0.9..1) the tint cover FADES OUT over the
 *       textured base — a real crossfade into the final form.</li>
 * </ul>
 */
public class EditedModelLayer extends RenderLayer<ChangedEntity, net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel<ChangedEntity>> {

    private static final float POST_MORPH_START = 0.85f;
    private static final float FADE_START = 0.9f;

    public EditedModelLayer(RenderLayerParent<ChangedEntity, net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel<ChangedEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       ChangedEntity entity, float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        boolean capturing = TransfurAnimator.isCapturing();
        ResourceLocation formId = formIdOf(entity);
        if (formId == null) return;
        EditedModel edited = EditedModel.get(formId);
        if (edited == null) return;

        if (!capturing) {
            ResourceLocation tex = FormAppearance.getTextureForForm(formId);
            if (tex == null) tex = this.getTextureLocation(entity);
            VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(tex));
            edited.render(this.getParentModel(), poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, 1f, false);
            return;
        }

        // ---- capture phase (transition) ----
        Player owner = FormAppearance.getPlayerOfEntity(entity);
        if (owner == null) owner = FormAppearance.RENDERING_PLAYER.get();
        var vi = owner != null ? ProcessTransfur.getPlayerTransfurVariant(owner) : null;
        if (vi == null) return;
        float progress = vi.getTransfurProgression(partialTick);
        if (progress < POST_MORPH_START) return; // morph segment: limb hook draws the blocks

        // Textured base under the tint cover (so the fade reveals the real model).
        ResourceLocation tex = FormAppearance.getTextureForForm(formId);
        if (tex == null) tex = this.getTextureLocation(entity);
        VertexConsumer base = buffer.getBuffer(RenderType.entityTranslucent(tex));
        edited.render(this.getParentModel(), poseStack, base, packedLight, OverlayTexture.NO_OVERLAY, 1f, false);

        // Tinted shell over the EXTRACTED cubes only (custom blocks are already
        // textured, the morph hook draws them during the morph segment), fading out
        // over progress 0.9..1 to hand the body back to its real texture.
        float fadeAlpha = progress >= FADE_START
                ? Math.max(0f, 1f - (progress - FADE_START) / (1f - FADE_START)) : 1f;
        if (fadeAlpha <= 0.01f) return;
        var tint = FormAppearance.getTintForForm(formId);
        float[] rgba = tint != null
                ? new float[]{tint.red() * 255f, tint.green() * 255f, tint.blue() * 255f, fadeAlpha * 255f}
                : new float[]{255f, 255f, 255f, fadeAlpha * 255f};
        ResourceLocation tintTex = EditedModel.getTintTexture();
        if (tintTex == null) return;
        EditedModel.TINT.set(rgba);
        try {
            VertexConsumer cover = buffer.getBuffer(fadeAlpha >= 0.999f
                    ? RenderType.entitySolid(tintTex)
                    : RenderType.entityTranslucent(tintTex));
            // extractedOnly=true + progress<1 keeps the emit inflate active so the
            // shell sits above the textured base without z-fighting.
            edited.render(this.getParentModel(), poseStack, cover, packedLight, OverlayTexture.NO_OVERLAY, progress, false, true);
        } finally {
            EditedModel.TINT.set(null);
        }
    }

    public static ResourceLocation formIdOf(ChangedEntity entity) {
        Player player = FormAppearance.getPlayerOfEntity(entity);
        if (player != null) {
            var vi = ProcessTransfur.getPlayerTransfurVariant(player);
            if (vi != null) return vi.getFormId();
        }
        var variant = entity.getSelfVariant();
        return variant != null ? variant.getFormId() : null;
    }
}
