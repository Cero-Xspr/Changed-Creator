package net.changedcreator.mixin.client;

import net.changedcreator.appearance.EditedModel;
import net.changedcreator.appearance.EditedModelLayer;
import net.changedcreator.appearance.EditedPlayerLayer;
import net.ltxprogrammer.changed.client.tfanimations.TransfurAnimator;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [RESTORED to pre-takeover state (fde46494) per user request.]
 *
 * Changed's {@code isLayerAllowed} only lets its own armor/accessory layers render
 * during the transfur transition, which drops our layers. Allow them through.
 *
 * [Step 1 of the re-fix: the renderMorphedEntity RETURN hook is REMOVED — it drew
 * a second, space-mirrored copy of the blocks. Only EditedModelLayer draws now.]
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
