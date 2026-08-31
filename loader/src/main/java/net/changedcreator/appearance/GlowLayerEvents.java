package net.changedcreator.appearance;

import net.changedcreator.ChangedCreator;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Lazily attaches the glow layers once the client renderers exist (first client tick). */
@Mod.EventBusSubscriber(modid = ChangedCreator.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class GlowLayerEvents {
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        GlowLayerHook.ensureAttachedOnce();
    }
}
