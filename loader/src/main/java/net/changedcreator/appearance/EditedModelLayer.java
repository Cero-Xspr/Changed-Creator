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
 * [RESTORED to pre-takeover state (fde46494) per user request.]
 * Draws the editor-saved model ({@code __edit.json}) in place of vanilla cubes.
 * During the transfur transition it renders ONLY the editor-created blocks in the
 * pure tint color with the spawn animation, in the capture-phase (static) pose.
 */
public class EditedModelLayer extends RenderLayer<ChangedEntity, net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel<ChangedEntity>> {

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
        // During the transfur transition: hide the vanilla latex cubes (SKIP_VANILLA,
        // set by LivingEntityRendererSkipMixin) and render ONLY the editor-created
        // blocks (isCustom) in the tint color, each sliding from the parent center.
        boolean animating = net.ltxprogrammer.changed.client.tfanimations.TransfurAnimator.isCapturing();
        boolean spawnOnly = animating;
        ResourceLocation tex = FormAppearance.getTextureForForm(formId);
        if (tex == null) tex = this.getTextureLocation(entity);
        float progress = 1f;
        if (animating) {
            var tint = FormAppearance.getTintForForm(formId);
            EditedModel.TINT.set(tint != null
                    ? new float[]{tint.red() * 255f, tint.green() * 255f, tint.blue() * 255f} : null);
            // Real spawn animation: blocks slide from the parent center to their
            // target as the transfur progression goes 0 -> 1.
            Player owner = FormAppearance.getPlayerOfEntity(entity);
            if (owner == null) owner = FormAppearance.RENDERING_PLAYER.get();
            var vi = owner != null ? ProcessTransfur.getPlayerTransfurVariant(owner) : null;
            if (vi != null) progress = vi.getTransfurProgression(partialTick);
            com.mojang.logging.LogUtils.getLogger().info(
                    "[CC-anim] form={} progress={} customBlocks={} owner={}",
                    formId, String.format("%.2f", progress), edited.countCustomBlocks(), owner != null);
        }
        net.minecraft.resources.ResourceLocation tt = animating ? EditedModel.getTintTexture() : null;
        VertexConsumer vc = buffer.getBuffer(
                tt != null ? RenderType.entitySolid(tt) : RenderType.entityTranslucent(tex));
        try {
            edited.render(this.getParentModel(), poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, progress, spawnOnly);
        } finally {
            if (animating) EditedModel.TINT.set(null);
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
