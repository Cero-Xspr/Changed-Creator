package net.changedcreator.appearance;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
 */
public class EditedModelLayer extends RenderLayer<ChangedEntity, net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel<ChangedEntity>> {

    public EditedModelLayer(RenderLayerParent<ChangedEntity, net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel<ChangedEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       ChangedEntity entity, float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        // During the transfur transition the editor blocks are drawn per-limb by
        // TransfurAnimatorMixin inside the morphed limb space (animated joint,
        // tint color, spawn animation). Drawing here too would duplicate them in
        // the static capture-phase pose at the old position.
        if (net.ltxprogrammer.changed.client.tfanimations.TransfurAnimator.isCapturing()) return;
        ResourceLocation formId = formIdOf(entity);
        if (formId == null) return;
        EditedModel edited = EditedModel.get(formId);
        if (edited == null) return;
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
