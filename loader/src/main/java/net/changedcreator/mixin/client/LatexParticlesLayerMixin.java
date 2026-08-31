package net.changedcreator.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.changedcreator.appearance.FormAppearance;
import net.ltxprogrammer.changed.client.renderer.layers.LatexParticlesLayer;
import net.ltxprogrammer.changed.process.ProcessTransfur;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-sources the latex drip-particle texture for a transfurred player's form:
 * particle colors are sampled from the texture returned by the layer's texture
 * function. Particle creation is asynchronous (not inside renderForm), so the
 * entity is resolved to its owning player via FormAppearance.ENTITY_TO_PLAYER
 * (recorded while the player form is being rendered).
 */
@Mixin(LatexParticlesLayer.class)
public abstract class LatexParticlesLayerMixin {
    @Unique
    private static final ThreadLocal<LivingEntity> CURRENT_ENTITY = new ThreadLocal<>();

    @Inject(method = "createNewDripParticle", at = @At("HEAD"), remap = false)
    private void changedcreator$captureEntity(LivingEntity entity, CallbackInfo ci) {
        CURRENT_ENTITY.set(entity);
    }

    @Redirect(method = "createNewDripParticle", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/ltxprogrammer/changed/client/renderer/layers/LatexParticlesLayer;getImage(Lnet/minecraft/resources/ResourceLocation;)Lcom/mojang/blaze3d/platform/NativeImage;"))
    @SuppressWarnings("rawtypes")
    private NativeImage changedcreator$overrideParticleTexture(LatexParticlesLayer layer, ResourceLocation original) {
        Entity entity = CURRENT_ENTITY.get();
        Player player = entity != null ? FormAppearance.getPlayerOfEntity(entity) : null;
        if (player == null) player = FormAppearance.RENDERING_PLAYER.get();
        if (player != null) {
            var instance = ProcessTransfur.getPlayerTransfurVariant(player);
            if (instance != null) {
                ResourceLocation override = FormAppearance.getTextureForForm(instance.getFormId());
                if (override != null) {
                    return layer.getImage(override);
                }
            }
        }
        return layer.getImage(original);
    }
}
