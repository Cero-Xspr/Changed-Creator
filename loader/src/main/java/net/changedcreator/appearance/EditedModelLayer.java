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
 * Draws the editor-saved model ({@code __edit.json}) in place of vanilla cubes,
 * following Changed's own transfur timeline as closely as possible:
 *
 * <ul>
 *   <li><b>Normal rendering</b>: the full edited model (extracted + custom cubes)
 *       with its real texture — one single code path, always.</li>
 *   <li><b>Morph segment (progress &lt; ~0.85, measured from {@code [CC-morph]} logs:
 *       Changed's morph ends there)</b>: this layer draws NOTHING. The body is
 *       Changed's own transitioned geometry and {@code TransfurAnimatorMixin} draws
 *       the custom blocks inside the morphed limb space with the real texture and
 *       the spawn animation.</li>
 *   <li><b>Capture outside the morph segment</b>: identical to normal rendering —
 *       no tint shell, no fade, no inflation. The segment boundary stays seamless
 *       because the transitioned pose at alpha=1 IS the captured pose this layer
 *       uses (exactly like vanilla, where the morph geometry hands over to the
 *       beast model).</li>
 * </ul>
 */
public class EditedModelLayer extends RenderLayer<ChangedEntity, net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel<ChangedEntity>> {

    /** Progress where Changed stops rendering the morph geometry (measured 0.84). */
    private static final float MORPH_SEGMENT_END = 0.85f;

    public EditedModelLayer(RenderLayerParent<ChangedEntity, net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel<ChangedEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       ChangedEntity entity, float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        ResourceLocation formId = formIdOf(entity);
        if (formId == null) return;
        EditedModel edited = EditedModel.get(formId);
        if (edited == null) return;
        if (TransfurAnimator.isCapturing()) {
            Player owner = FormAppearance.getPlayerOfEntity(entity);
            if (owner == null) owner = FormAppearance.RENDERING_PLAYER.get();
            var vi = owner != null ? ProcessTransfur.getPlayerTransfurVariant(owner) : null;
            if (vi == null) return;
            if (vi.getTransfurProgression(partialTick) < MORPH_SEGMENT_END) return;
        }
        ResourceLocation tex = FormAppearance.getTextureForForm(formId);
        if (tex == null) tex = this.getTextureLocation(entity);
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(tex));
        edited.render(this.getParentModel(), poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, 1f, false);
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
