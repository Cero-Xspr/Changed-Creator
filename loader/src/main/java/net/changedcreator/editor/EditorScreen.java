package net.changedcreator.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * In-game editor hub: shows the WebUI address, opens the browser, lists saved
 * forms and imports exported editor files (dropped into config/changedcreator/imports/).
 */
public class EditorScreen extends Screen {
    private static final int LINE_HEIGHT = 12;

    private String status = "";
    private List<String> formLines = new ArrayList<>();
    private List<Path> importFiles = new ArrayList<>();
    private String message = "";

    public EditorScreen() {
        super(Component.literal("胶兽编辑器"));
    }

    @Override
    protected void init() {
        int w = this.width;
        int cy = 32;

        this.addRenderableWidget(Button.builder(Component.literal("在浏览器打开 WebUI"), b -> openBrowser())
                .bounds(w / 2 - 110, cy, 220, 20).build());
        cy += 26;

        this.addRenderableWidget(Button.builder(Component.literal("刷新形态列表"), b -> refresh())
                .bounds(w / 2 - 110, cy, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("导入导出文件"), b -> importAll())
                .bounds(w / 2 + 10, cy, 100, 20).build());
        cy += 26;

        this.addRenderableWidget(Button.builder(Component.literal("返回主菜单"), b -> this.onClose())
                .bounds(w / 2 - 110, cy, 220, 20).build());
        cy += 30;

        refresh();
    }

    private void openBrowser() {
        if (!EditorServer.isRunning()) {
            message = "服务器未启动（查看日志）";
            return;
        }
        String url = EditorServer.getUrl();
        // 1) AWT Desktop (works on desktops with a default browser).
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                message = "已在浏览器打开 " + url;
                return;
            }
        } catch (IOException | UnsupportedOperationException ignored) {
        }
        // 2) xdg-open (Linux, even without a "Desktop").
        try {
            new ProcessBuilder("xdg-open", url).start();
            message = "已通过 xdg-open 打开 " + url;
            return;
        } catch (IOException ignored) {
        }
        // 3) Fallback: copy the URL to the clipboard.
        copyToClipboard(url);
        message = "无法自动打开浏览器，地址已复制到剪贴板： " + url;
    }

    private void copyToClipboard(String url) {
        try {
            long window = Minecraft.getInstance().getWindow().getWindow();
            org.lwjgl.glfw.GLFW.glfwSetClipboardString(window, url);
        } catch (Throwable e) {
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(url), null);
            } catch (Throwable e2) {
                message = "无法打开浏览器，请手动访问 " + url;
            }
        }
    }

    private void refresh() {
        formLines = new ArrayList<>();
        Path formsDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve(net.changedcreator.ChangedCreator.MODID).resolve("forms");
        if (Files.isDirectory(formsDir)) {
            try (var stream = Files.newDirectoryStream(formsDir, "*.json")) {
                for (Path f : stream) formLines.add("• " + f.getFileName().toString());
            } catch (IOException e) {
                formLines.add("读取 forms 目录失败: " + e.getMessage());
            }
        }
        if (formLines.isEmpty()) formLines.add("（暂无已保存的自定义形态）");
        importFiles = new ArrayList<>();
        try {
            Path imports = EditorImportExport.importsDir();
            if (Files.isDirectory(imports)) {
                try (var stream = Files.newDirectoryStream(imports, "*.json")) {
                    for (Path f : stream) importFiles.add(f);
                } catch (IOException ignored) {
                }
            }
        } catch (Throwable t) {
            // NoClassDefFoundError happens when the jar was hot-swapped while the
            // game is running — show a hint instead of crashing the whole game.
            formLines.add("⚠ mod 已更新，请重启游戏后重试");
        }
        message = "WebUI: " + (EditorServer.isRunning() ? EditorServer.getUrl() : "（未启动）")
                + "    待导入: " + importFiles.size() + " 个文件";
    }

    private void importAll() {
        if (importFiles.isEmpty()) {
            message = "没有待导入文件（把导出的 .json 放进 config/changedcreator/imports/）";
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Path f : importFiles) {
            var result = EditorImportExport.importFile(f);
            sb.append(result.message()).append("\n");
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignored) {
            }
        }
        message = sb.toString();
        refresh();
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        int w = this.width;
        guiGraphics.drawCenteredString(this.font, this.title, w / 2, 14, 0xFFFFFF);

        int y = 150;
        guiGraphics.drawString(this.font, "已保存形态:", 20, y, 0xAAAAAA);
        y += LINE_HEIGHT;
        for (String line : formLines) {
            guiGraphics.drawString(this.font, line, 30, y, 0xFFFFFF);
            y += LINE_HEIGHT;
        }

        y += 6;
        guiGraphics.drawString(this.font, "提示：", 20, y, 0xAAAAAA);
        y += LINE_HEIGHT;
        guiGraphics.drawString(this.font, "1) 点「在浏览器打开 WebUI」开始编辑；", 30, y, 0xCCCCCC);
        y += LINE_HEIGHT;
        guiGraphics.drawString(this.font, "2) 浏览器里点「导出文件」保存到任意位置；", 30, y, 0xCCCCCC);
        y += LINE_HEIGHT;
        guiGraphics.drawString(this.font, "3) 把导出文件放进 config/changedcreator/imports/ 再点「导入」。", 30, y, 0xCCCCCC);

        int my = Math.max(80, y + 20);
        guiGraphics.drawCenteredString(this.font, Component.literal(message).getString(), w / 2, my, 0xFFFF55);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }
}
