package net.changedcreator;

import com.mojang.logging.LogUtils;
import net.changedcreator.load.LatexFormLoader;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ChangedCreator.MODID)
public class ChangedCreator {
    public static final String MODID = "changedcreator";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ChangedCreator(FMLJavaModLoadingContext context) {
        final IEventBus modEventBus = context.getModEventBus();

        // Register the transfur-variant DeferredRegister BEFORE loading any JSON definitions.
        LatexFormLoader.registerRegistry(modEventBus);
        // Load custom latex form definitions (config/changedcreator/forms/*.json) and register each
        // TransfurVariant lazily when the changed:latex_variant registry event fires.
        // Note: forms are only picked up at game startup; there is no hot-reload.
        LatexFormLoader.loadFromConfig();

        // Load appearance overrides (textures/tints per form id) - consumed by our mixins.
        net.changedcreator.appearance.FormAppearance.load();

        // After common setup, verify every loaded form is actually present in the registry.
        modEventBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) ->
                event.enqueueWork(LatexFormLoader::verifyRegisteredForms));

        // Start the embedded editor WebUI server on the client (random port).
        modEventBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) ->
                event.enqueueWork(net.changedcreator.editor.EditorServer::start));

        LOGGER.info("Changed Creator loaded");
    }
}
