package net.changedcreator.appearance;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Draws the editor-saved model on the PLAYER renderer, so the new cubes are also
 * visible during the transfur transition (which renders the human/player model).
 */
public class EditedPlayerLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    public EditedPlayerLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       AbstractClientPlayer entity, float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        var instance = ProcessTransfur.getPlayerTransfurVariant(entity);
        if (instance == null) return; // not transfurred -> normal player skin renders
        var formId = instance.getFormId();
        EditedModel edited = EditedModel.get(formId);
        if (edited == null) return; // no saved __edit.json for this form
        // The spawn blocks are drawn by EditedModelLayer on the latex model during
        // the transition. The human-form phase must stay untouched.
        return;
    }
}
