package net.changedcreator.mixin;

import com.mojang.datafixers.util.Pair;
import net.changedcreator.appearance.FormAppearance;
import net.ltxprogrammer.changed.entity.variant.TransfurVariantInstance;
import net.ltxprogrammer.changed.util.Color3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Appearance color overrides for a transfurred PLAYER's variant instance:
 *   - getTransfurColor(): latex body/transform-animation color
 *   - getColors():       UI colors (survival inventory background, ability radial menu)
 * Both are keyed by the variant's real form id (getParent().getFormId()).
 */
@Mixin(TransfurVariantInstance.class)
public abstract class TransfurVariantInstanceMixin {
    @Inject(method = "getTransfurColor", at = @At("HEAD"), remap = false, cancellable = true)
    private void changedcreator$overrideTint(CallbackInfoReturnable<Color3> cir) {
        TransfurVariantInstance<?> self = (TransfurVariantInstance<?>) (Object) this;
        Color3 override = FormAppearance.getTintForForm(self.getParent().getFormId());
        if (override != null) {
            cir.setReturnValue(override);
        }
    }

    @Inject(method = "getColors", at = @At("HEAD"), remap = false, cancellable = true)
    private void changedcreator$overrideColors(CallbackInfoReturnable<Pair<Color3, Color3>> cir) {
        TransfurVariantInstance<?> self = (TransfurVariantInstance<?>) (Object) this;
        Color3 tint = FormAppearance.getTintForForm(self.getParent().getFormId());
        if (tint != null) {
            cir.setReturnValue(Pair.of(tint, Color3.WHITE));
        }
    }
}
