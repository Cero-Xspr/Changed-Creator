package net.changedcreator.mixin.client;

import net.changedcreator.appearance.FormAppearance;
import net.ltxprogrammer.changed.client.FormRenderHandler;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Provides the rendering context for a transfurred player's form while it is
 * being rendered:
 *   - sets FormAppearance.RENDERING_PLAYER (consumed by LivingEntityRendererMixin
 *     for the latex body texture override)
 *   - records the variant entity -> player mapping (consumed by
 *     LatexParticlesLayerMixin for async drip-particle sampling AND by the
 *     first-person hand texture override below)
 *
 * First-person hand: FormRenderHandler.renderHand renders the variant's
 * changed-entity arm using renderer.getTextureLocation(vi.getChangedEntity()).
 * We redirect it and resolve the owning player via the entity->player map
 * (recorded during renderForm), so the hand matches the body texture.
 */
@Mixin(FormRenderHandler.class)
public abstract class FormRenderHandlerMixin {
    @Inject(method = "renderForm", at = @At("HEAD"), remap = false)
    private static void changedcreator$enterRenderForm(Player player,
                                                       com.mojang.blaze3d.vertex.PoseStack poseStack,
                                                       net.minecraft.client.renderer.MultiBufferSource buffer,
                                                       int packedLight, float partialTicks,
                                                       CallbackInfo ci) {
        FormAppearance.RENDERING_PLAYER.set(player);
        var instance = ProcessTransfur.getPlayerTransfurVariant(player);
        if (instance != null) {
            FormAppearance.recordEntityPlayer(instance.getChangedEntity(), player);
        }
    }

    @Inject(method = "renderForm", at = @At("RETURN"), remap = false)
    private static void changedcreator$exitRenderForm(Player player,
                                                      com.mojang.blaze3d.vertex.PoseStack poseStack,
                                                      net.minecraft.client.renderer.MultiBufferSource buffer,
                                                      int packedLight, float partialTicks,
                                                      CallbackInfo ci) {
        FormAppearance.RENDERING_PLAYER.remove();
    }

    @Redirect(method = "renderHand(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/model/geom/PartPose;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IFZ)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;getTextureLocation(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/resources/ResourceLocation;"))
    @SuppressWarnings("rawtypes")
    private static ResourceLocation changedcreator$overrideHandTexture(EntityRenderer renderer, Entity entity) {
        // entity here is the variant's changed-entity instance (not the Player),
        // so resolve the owning player via the entity->player map.
        Player player = entity != null ? FormAppearance.getPlayerOfEntity(entity) : null;
        if (player == null) player = FormAppearance.RENDERING_PLAYER.get();
        if (player != null) {
            var instance = ProcessTransfur.getPlayerTransfurVariant(player);
            if (instance != null) {
                ResourceLocation override = FormAppearance.getTextureForForm(instance.getFormId());
                if (override != null) {
                    return override;
                }
            }
        }
        return renderer.getTextureLocation(entity);
    }
}
