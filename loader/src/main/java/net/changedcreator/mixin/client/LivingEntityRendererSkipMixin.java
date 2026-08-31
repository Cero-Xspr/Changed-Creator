package net.changedcreator.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.changedcreator.appearance.EditedModel;
import net.changedcreator.appearance.EditedModelLayer;
import net.ltxprogrammer.changed.entity.ChangedEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide vanilla cubes for the duration of a ChangedEntity render that has an edited model. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererSkipMixin {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void changedcreator$beginSkip(LivingEntity entity, float yaw, float pt, PoseStack pose,
                                          MultiBufferSource buf, int light, CallbackInfo ci) {
        if (!(entity instanceof ChangedEntity ce)) return;
        ResourceLocation id = EditedModelLayer.formIdOf(ce);
        if (id != null && EditedModel.get(id) != null) {
            EditedModel.SKIP_VANILLA.set(Boolean.TRUE);
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void changedcreator$endSkip(LivingEntity entity, float yaw, float pt, PoseStack pose,
                                        MultiBufferSource buf, int light, CallbackInfo ci) {
        EditedModel.SKIP_VANILLA.set(Boolean.FALSE);
    }
}
