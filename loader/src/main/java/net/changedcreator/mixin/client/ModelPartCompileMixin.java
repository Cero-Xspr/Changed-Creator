package net.changedcreator.mixin.client;

import net.changedcreator.appearance.EditedModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skip vanilla cube geometry while an edited overlay is drawing the body. */
@Mixin(ModelPart.class)
public abstract class ModelPartCompileMixin {
    @Inject(method = "compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V", at = @At("HEAD"), cancellable = true)
    private void changedcreator$skipVanillaCubes(CallbackInfo ci) {
        if (Boolean.TRUE.equals(EditedModel.SKIP_VANILLA.get())) {
            ci.cancel();
        }
    }
}
