package net.changedcreator.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.changedcreator.appearance.EditedModel;
import net.changedcreator.appearance.EditedModelLayer;
import net.changedcreator.appearance.EditedPlayerLayer;
import net.changedcreator.appearance.FormAppearance;
import net.ltxprogrammer.changed.client.animations.Limb;
import net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel;
import net.ltxprogrammer.changed.client.tfanimations.TransfurAnimator;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Changed's {@code isLayerAllowed} only lets its own armor/accessory layers render
 * during the transfur transition, which drops our layers. Force them through.
 *
 * <p>The real humanoid→beast morph is drawn per-limb in {@code renderMorphedLimb}:
 * it pushes the TRANSITIONED parent-chain matrix (captured humanoid ↔ beast pose,
 * alpha-interpolated) onto the PoseStack via {@code setPose}, then calls
 * {@code EntityGeometry.render}. Injecting right at that call lets the editor's
 * added cubes be drawn inside the exact animated limb space — they grow out of the
 * MOVING morph joint instead of the static beast pose. (Rendering at
 * {@code renderMorphedEntity} RETURN would use the raw stack without the entity
 * orientation transforms — front/back mirrored — and a balanced, pose-free stack.)
 */
@Mixin(TransfurAnimator.class)
public abstract class TransfurAnimatorMixin {
    @Inject(method = "isLayerAllowed(Lnet/minecraft/client/renderer/entity/layers/RenderLayer;)Z",
            at = @At("RETURN"), cancellable = true, remap = false)
    private static void changedcreator$allowEditedLayer(RenderLayer<?, ?> layer, CallbackInfoReturnable<Boolean> cir) {
        if (layer instanceof EditedPlayerLayer || layer instanceof EditedModelLayer) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }

    private static int changedcreator$lastLoggedPct = -1;

    @Inject(method = "renderMorphedLimb", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/ltxprogrammer/changed/client/tfanimations/EntityGeometry;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"))
    private static void changedcreator$renderEditedInMorphLimb(
            LivingEntity entity, Limb limb, HumanoidModel<?> humanoid, AdvancedHumanoidModel<?> advanced,
            float limbSwing, float limbSwingAmount, net.ltxprogrammer.changed.util.Color3 color, float morphAlpha,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, ResourceLocation texture,
            boolean flagA, boolean flagB, CallbackInfo ci) {
        Player owner = entity instanceof Player p ? p : FormAppearance.getPlayerOfEntity(entity);
        if (owner == null) owner = FormAppearance.RENDERING_PLAYER.get();
        if (owner == null) return;
        var vi = ProcessTransfur.getPlayerTransfurVariant(owner);
        if (vi == null) return;
        ResourceLocation formId = vi.getFormId();
        EditedModel edited = EditedModel.get(formId);
        if (edited == null || edited.countCustomBlocks() == 0) return;
        ModelPart limbPart = limb.getModelPart(advanced);
        if (limbPart == null) return;
        ModelPart humanoidPart = limb.getModelPart(humanoid);
        float progress = vi.getTransfurProgression(Minecraft.getInstance().getPartialTick());

        int pct = (int) (progress * 100f);
        if (pct < changedcreator$lastLoggedPct) changedcreator$lastLoggedPct = -1;
        if (pct != changedcreator$lastLoggedPct) {
            changedcreator$lastLoggedPct = pct;
            com.mojang.logging.LogUtils.getLogger().info("[CC-morph] limb-rendering active, progress={}%", pct);
        }

        // The editor blocks keep their REAL texture during the morph so the spawn
        // animation is actually visible against the tinted morphing body (tinted
        // blocks blended into the silhouette and were only seen popping in at the
        // end). The tint shell is handled by EditedModelLayer's post-morph pass.
        ResourceLocation tex = FormAppearance.getTextureForForm(formId);
        if (tex == null) tex = EditedModel.getTintTexture();
        if (tex == null) return;
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(tex));
        edited.renderLimbSubtree(advanced, limbPart, humanoidPart, morphAlpha, poseStack, vc,
                packedLight, OverlayTexture.NO_OVERLAY, progress);
    }
}
