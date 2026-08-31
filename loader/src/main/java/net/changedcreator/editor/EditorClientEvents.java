package net.changedcreator.editor;

import net.changedcreator.ChangedCreator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Adds the 「胶兽编辑器」 entry points (no mixins):
 *   - main menu: a square ImageButton (like the vanilla language button) using the
 *     dark latex wolf texture as icon, placed to the LEFT of the language button;
 *   - pause menu: a plain button opening the editor screen.
 */
@Mod.EventBusSubscriber(modid = ChangedCreator.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class EditorClientEvents {
    private static final ResourceLocation EDITOR_ICON =
            ResourceLocation.fromNamespaceAndPath(ChangedCreator.MODID, "textures/gui/editor_icon.png");

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen titleScreen) {
            addTitleScreenButton(event, titleScreen);
        } else if (event.getScreen() instanceof PauseScreen pauseScreen) {
            addPauseButton(event, pauseScreen);
        }
    }

    private static void addTitleScreenButton(ScreenEvent.Init.Post event, TitleScreen screen) {
        Button langBtn = findLanguageButton(screen);
        int x;
        int y;
        if (langBtn != null) {
            x = Math.max(2, langBtn.getX() - 26); // 20x20 button + 4px gap to the left of the language button
            y = langBtn.getY();
        } else {
            x = screen.width - 150;
            y = screen.height - 32;
        }
        event.addListener(new EditorIconButton(x, y, Component.literal("胶兽编辑器"), EDITOR_ICON));
    }

    private static void addPauseButton(ScreenEvent.Init.Post event, PauseScreen screen) {
        int w = screen.width;
        // Square vanilla-style icon button, one row ABOVE the "保存并退出到标题屏幕" row
        // (kept clear of the button Changed adds on this screen).
        event.addListener(new EditorIconButton(w / 2 - 102 - 30, screen.height / 4 + 120 - 48 - 26,
                Component.literal("胶兽编辑器"), EDITOR_ICON));
    }

    /**
     * The language button on the title screen is the ImageButton whose message is
     * the translatable "narrator.button.language" (its exact screen position
     * differs across mappings/versions, so match by message key).
     */
    private static Button findLanguageButton(TitleScreen screen) {
        for (var listener : screen.children()) {
            if (listener instanceof Button btn) {
                var msg = btn.getMessage();
                if (msg instanceof net.minecraft.network.chat.MutableComponent mc
                        && mc.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                        && "narrator.button.language".equals(tc.getKey())) {
                    return btn;
                }
            }
        }
        return null;
    }
}
