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

    private static boolean changedcreator$loggedThisMorph;

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
        if (!animating) changedcreator$loggedThisMorph = false;
        ResourceLocation tex = FormAppearance.getTextureForForm(formId);
        if (tex == null) tex = this.getTextureLocation(entity);
        float progress = 1f;
        if (animating) {
            Player owner = FormAppearance.getPlayerOfEntity(entity);
            if (owner == null) owner = FormAppearance.RENDERING_PLAYER.get();
            var vi = owner != null ? ProcessTransfur.getPlayerTransfurVariant(owner) : null;
            if (vi != null) progress = vi.getTransfurProgression(partialTick);
            // ---- Morph segment (progress < ~0.85, where Changed's morph geometry renders):
            // compute the transitioned pose OURSELVES (no hook): humanoid part poses
            // (frozen player model) ↔ beast part poses (frozen advanced model), lerped
            // by the same eased alpha Changed uses in renderTransfurringPlayer.
            if (progress < 0.85f) {
                if (vi == null) return;
                var renderer = net.minecraft.client.Minecraft.getInstance()
                        .getEntityRenderDispatcher().getRenderer(owner);
                net.minecraft.client.model.EntityModel<?> pm = renderer instanceof
                        net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> ler ? ler.getModel() : null;
                net.minecraft.client.model.HumanoidModel<?> humanoid =
                        pm instanceof net.minecraft.client.model.HumanoidModel<?> hm ? hm : null;
                float raw = net.ltxprogrammer.changed.client.tfanimations.TransfurAnimator.getMorphAlpha(progress);
                float alpha = (float) (-(Math.cos(Math.PI * Math.max(0f, Math.min(1f, raw))) - 1d) / 2d); // easeInOutSine
                if (!changedcreator$loggedThisMorph) {
                    changedcreator$loggedThisMorph = true;
                    com.mojang.logging.LogUtils.getLogger().info(
                            "[CC-diag] morph segment enter: humanoid={}, progress={}, alpha={}",
                            humanoid != null ? humanoid.getClass().getName() : "NULL(player renderer model is not HumanoidModel)",
                            String.format("%.3f", progress), String.format("%.3f", alpha));
                }
                var tint = FormAppearance.getTintForForm(formId);
                EditedModel.TINT.set(tint != null
                        ? new float[]{tint.red() * 255f, tint.green() * 255f, tint.blue() * 255f} : null);
                ResourceLocation tt = EditedModel.getTintTexture();
                if (tt == null) return;
                VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(tt));
                try {
                    // spawnOnly=true: only editor-created blocks, spawn-animated
                    edited.renderMorphSegment(this.getParentModel(), humanoid, alpha, poseStack, vc,
                            packedLight, OverlayTexture.NO_OVERLAY, progress);
                } finally {
                    EditedModel.TINT.set(null);
                }
                return;
            }
            // ---- Post-morph segment: the morph geometry is gone; this layer draws the
            // blocks at the static captured pose. The handover is seamless because the
            // morph pose at alpha=1 IS this static pose.
            var tint = FormAppearance.getTintForForm(formId);
            EditedModel.TINT.set(tint != null
                    ? new float[]{tint.red() * 255f, tint.green() * 255f, tint.blue() * 255f} : null);
        }
        net.minecraft.resources.ResourceLocation tt = animating ? EditedModel.getTintTexture() : null;
        // During the animation the capture-phase space carries a mirroring transform:
        // symmetric blocks land in correct positions but their winding flips, so
        // front faces get backface-culled (you saw the insides). No-cull renders
        // both sides; depth still resolves the near side.
        VertexConsumer vc = buffer.getBuffer(
                tt != null ? RenderType.entityCutoutNoCull(tt) : RenderType.entityTranslucent(tex));
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
