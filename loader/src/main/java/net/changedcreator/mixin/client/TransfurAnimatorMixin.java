package net.changedcreator.mixin.client;

import net.changedcreator.appearance.EditedModel;
import net.changedcreator.appearance.EditedModelLayer;
import net.changedcreator.appearance.EditedPlayerLayer;
import net.ltxprogrammer.changed.client.renderer.model.AdvancedHumanoidModel;
import net.ltxprogrammer.changed.client.tfanimations.TransfurAnimator;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.minecraft.client.model.HumanoidModel;
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
 * [RESTORED to pre-takeover state (fde46494) per user request.]
 *
 * Changed's {@code isLayerAllowed} only lets its own armor/accessory layers render
 * during the transfur transition, which drops our layers. Allow them through.
 *
 * Also hooks {@code renderMorphedEntity}: by the time it returns, the advanced
 * (beast) model's parts carry the morph pose; the edited model is rendered with
 * the pure tint texture (spawnOnly).
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

    @Inject(method = "renderMorphedEntity", at = @At("RETURN"), remap = false)
    private static void changedcreator$renderMorphedEditedModel(
            LivingEntity entity, HumanoidModel<?> humanoid, AdvancedHumanoidModel<?> advanced,
            float limbSwing, float limbSwingAmount, net.ltxprogrammer.changed.util.Color3 color,
            float progression, com.mojang.blaze3d.vertex.PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, ResourceLocation texture, boolean b,
            CallbackInfo ci) {
        ResourceLocation formId = null;
        if (entity instanceof Player p) {
            var vi = ProcessTransfur.getPlayerTransfurVariant(p);
            if (vi != null) formId = vi.getFormId();
        }
        if (formId == null) return;
        EditedModel edited = EditedModel.get(formId);
        if (edited == null) return;
        EditedModel.TINT.set(new float[]{color.red() * 255f, color.green() * 255f, color.blue() * 255f});
        ResourceLocation tt = EditedModel.getTintTexture();
        if (tt == null) return;
        var vc = buffer.getBuffer(RenderType.entitySolid(tt));
        try {
            edited.render(advanced, poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY,
                    progression, true);
        } finally {
            EditedModel.TINT.set(null);
        }
    }
}
