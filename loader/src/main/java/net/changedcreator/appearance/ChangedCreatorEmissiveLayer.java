package net.changedcreator.appearance;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.changedcreator.appearance.FormAppearance;
import net.ltxprogrammer.changed.entity.ChangedEntity;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * Emissive (glow) layer for custom forms.
 *
 * Renders the form's glow texture (config/changedcreator/textures/<id>_emissive.png)
 * with an emissive render type, resolving the form id through the entity->player
 * mapping so the glow follows the PLAYER's custom form (not the base entity).
 */
public class ChangedCreatorEmissiveLayer extends RenderLayer<ChangedEntity, net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel<ChangedEntity>> {

    public ChangedCreatorEmissiveLayer(RenderLayerParent<ChangedEntity, net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel<ChangedEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       ChangedEntity entity, float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        ResourceLocation glow = emissiveFor(entity);
        if (glow == null) return;
        RenderType rt = RenderType.eyes(glow); // additive glow shader (no Sampler2 requirement)
        VertexConsumer vc = buffer.getBuffer(rt);
        this.getParentModel().renderToBuffer(poseStack, vc, 0xF000F0, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
    }

    /** Resolves the glow texture for the entity: prefer the owning player's custom form id. */
    private static ResourceLocation emissiveFor(ChangedEntity entity) {
        ResourceLocation formId = null;
        Player player = FormAppearance.getPlayerOfEntity(entity);
        if (player != null) {
            var vi = ProcessTransfur.getPlayerTransfurVariant(player);
            if (vi != null) formId = vi.getFormId();
        }
        if (formId == null) {
            var variant = entity.getSelfVariant();
            if (variant != null) formId = variant.getFormId();
        }
        return formId != null ? FormAppearance.getEmissiveForForm(formId) : null;
    }
}
