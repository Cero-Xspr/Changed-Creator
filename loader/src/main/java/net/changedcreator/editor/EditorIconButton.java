package net.changedcreator.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A small square button in the vanilla button style: the vanilla widgets button
 * sprite as the base (hover state included) with the editor icon overlaid in the
 * middle. No text is drawn.
 * Used on both the title screen and the pause screen.
 */
public class EditorIconButton extends Button {
    private final ResourceLocation icon;

    public EditorIconButton(int x, int y, Component message, ResourceLocation icon) {
        super(x, y, 20, 20, Component.literal(""), // empty message: no text rendered under the icon
                b -> Minecraft.getInstance().setScreen(new EditorScreen()),
                Button.DEFAULT_NARRATION);
        this.icon = icon;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        // Vanilla button base (sprite + text) first, then the icon covers the text.
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTicks);
        // Icon: the user's 20x40 texture has its content in the TOP-LEFT 20x20;
        // blit that region aligned to the button's top-left corner (no offset).
        int x = getX();
        int y = getY();
        guiGraphics.blit(icon, x, y, 20, 20, 0, 0, 20, 20, 20, 40);
    }
}
