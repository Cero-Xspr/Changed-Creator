package net.changedcreator.mixin.client;

import net.changedcreator.appearance.FormAppearance;
import net.ltxprogrammer.changed.client.tfanimations.TransfurAnimator;
import net.ltxprogrammer.changed.entity.ChangedEntity;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Overrides the texture of the latex BODY (the variant's changed-entity instance
 * rendered as the player's form).
 *
 * ROOT CAUSE of the "skin pollution" bug: during the transfur animation the HUMAN
 * transition model is rendered as the Player entity (getTextureLocation(player)
 * normally returns the player's own skin). Our player-fallback branch previously
 * overrode ANY entity - including Player - so the latex texture was painted onto
 * the player model. Fix: never override when the rendered entity IS a Player.
 *
 * Also preserve Changed's capture phase (TransfurAnimator.isCapturing) untouched.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Redirect(method = "getRenderType",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getTextureLocation(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/resources/ResourceLocation;"))
    @SuppressWarnings("rawtypes")
    private ResourceLocation changedcreator$overrideTexture(LivingEntityRenderer renderer, Entity entity) {
        // 1) The Player entity renders the player's own skin - NEVER override it
        //    (this is what the transfur animation's human model uses).
        if (entity instanceof Player) {
            return renderer.getTextureLocation(entity);
        }
        // 2) Preserve Changed's capture phase (model-part capture during the animation).
        if (TransfurAnimator.isCapturing()) {
            return renderer.getTextureLocation(entity);
        }

        // 3) The latex body renders the variant's changed-entity instance.
        ResourceLocation override = FormAppearance.getTextureOverride(entity);
        if (override == null && entity instanceof ChangedEntity) {
            Player player = FormAppearance.RENDERING_PLAYER.get();
            if (player != null) {
                var instance = ProcessTransfur.getPlayerTransfurVariant(player);
                if (instance != null) {
                    override = FormAppearance.getTextureForForm(instance.getFormId());
                }
            }
        }
        return override != null ? override : renderer.getTextureLocation(entity);
    }
}

