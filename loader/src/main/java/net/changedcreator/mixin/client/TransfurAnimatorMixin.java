package net.changedcreator.mixin.client;

import net.changedcreator.appearance.EditedModelLayer;
import net.changedcreator.appearance.EditedPlayerLayer;
import net.ltxprogrammer.changed.client.tfanimations.TransfurAnimator;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code isLayerAllowed} only lets Changed's own layers render during the transfur
 * transition — force ours through.
 *
 * <p>[Hook removed per user request: injecting into {@code renderMorphedLimb} made
 * many blocks behave strangely. The morph pose is now computed entirely inside
 * {@link EditedModelLayer}: humanoid part poses (frozen player model) ↔ beast part
 * poses (frozen advanced model) lerped by the same eased alpha Changed uses —
 * {@code easeInOutSine(getMorphProgression-era alpha)} — with limb pairing from the
 * public {@code Limb} enum. No Changed rendering internals are touched.]
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
}
