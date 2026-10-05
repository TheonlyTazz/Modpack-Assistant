package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.showoff.ShowoffBackground;
import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.breakinblocks.modpackassistant.util.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

final class ShowoffScreen extends Screen {
    private static final int BACKDROP = 0xFF000000;
    private static final int PANEL = 0xFF141414;
    private static final int BORDER = 0xFF3F3F3F;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFA0A0A0;
    private static final int CHECKER_LIGHT = 0xFF3C3C3C;
    private static final int CHECKER_DARK = 0xFF262626;
    private static final int CHECKER_SIZE = 8;
    private static final int PADDING = 6;
    private static final int SWATCH = 16;
    private static final int GAP = 2;
    private static final int COLUMNS = 4;
    private static final int SIDEBAR = COLUMNS * SWATCH + (COLUMNS - 1) * GAP;
    private static final int BUTTON_HEIGHT = 20;
    private static final float ROTATE_DEGREES_PER_PIXEL = 0.8F;
    private static final double ZOOM_STEP = 1.15;
    private static final int TRANSPARENT_SWATCH = -1;
    private static final int NO_SWATCH = -2;
    private static final String ELLIPSIS = "...";

    private final ShowoffSession session;
    private final ShowoffPreview preview = new ShowoffPreview();
    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int previewLeft;
    private int previewTop;
    private int previewRight;
    private int previewBottom;
    private int footerTop;
    private int sidebarLeft;
    private int swatchTop;
    private int transparentTop;
    private boolean rotating;
    private boolean panning;
    private @Nullable ShowoffAngleSlider yawSlider;
    private @Nullable ShowoffAngleSlider pitchSlider;

    ShowoffScreen(ShowoffSession session) {
        super(session.title());
        this.session = session;
    }

    @Override
    protected void init() {
        int margin = Math.max(10, Math.min(width, height) / 16);
        panelLeft = margin;
        panelTop = margin;
        panelRight = width - margin;
        panelBottom = height - margin;
        sidebarLeft = panelRight - PADDING - SIDEBAR;
        previewLeft = panelLeft + PADDING;
        previewTop = panelTop + PADDING + font.lineHeight + 4;
        previewRight = sidebarLeft - PADDING;
        footerTop = panelBottom - PADDING - font.lineHeight;
        previewBottom = footerTop - 4 - BUTTON_HEIGHT - PADDING;
        swatchTop = previewTop + font.lineHeight + 3;
        int rows = (ShowoffBackground.SWATCHES.size() + COLUMNS - 1) / COLUMNS;
        transparentTop = swatchTop + rows * (SWATCH + GAP);
        int guiScale = guiScale();
        session.previewSize((previewRight - previewLeft) * guiScale, (previewBottom - previewTop) * guiScale);

        int buttonTop = previewBottom + PADDING;
        addRenderableWidget(Button.builder(Messages.SHOWOFF_BUTTON_DONE.get(), button -> onClose())
                .bounds(sidebarLeft, buttonTop, SIDEBAR, BUTTON_HEIGHT)
                .build());
        buttonTop -= BUTTON_HEIGHT + GAP;
        addRenderableWidget(Button.builder(Messages.SHOWOFF_BUTTON_SCREENSHOT.get(), button -> ShowoffCapture.request(session, "", 0, 0))
                .bounds(sidebarLeft, buttonTop, SIDEBAR, BUTTON_HEIGHT)
                .build());
        buttonTop -= BUTTON_HEIGHT + GAP;
        addRenderableWidget(Button.builder(Messages.SHOWOFF_BUTTON_RESET.get(), button -> session.view(ShowoffView.DEFAULT))
                .bounds(sidebarLeft, buttonTop, SIDEBAR, BUTTON_HEIGHT)
                .build());

        int sliderTop = previewBottom + PADDING;
        int sliderWidth = (previewRight - previewLeft - PADDING) / 2;
        yawSlider = addRenderableWidget(ShowoffAngleSlider.yaw(previewLeft, sliderTop, sliderWidth, BUTTON_HEIGHT, session));
        pitchSlider = addRenderableWidget(ShowoffAngleSlider.pitch(previewRight - sliderWidth, sliderTop, sliderWidth, BUTTON_HEIGHT, session));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL);
        graphics.renderOutline(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop, BORDER);

        int background = session.background();
        if (ShowoffBackground.isTransparent(background)) {
            checkerboard(graphics, previewLeft, previewTop, previewRight, previewBottom);
        } else {
            graphics.fill(previewLeft, previewTop, previewRight, previewBottom, background);
        }
        int guiScale = guiScale();
        preview.draw(graphics, session, previewLeft, previewTop, previewRight, previewBottom,
                (previewRight - previewLeft) * guiScale, (previewBottom - previewTop) * guiScale);
        graphics.renderOutline(previewLeft - 1, previewTop - 1, previewRight - previewLeft + 2, previewBottom - previewTop + 2, BORDER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (yawSlider != null && pitchSlider != null) {
            yawSlider.sync();
            pitchSlider.sync();
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, fit(title.getString(), previewRight - previewLeft), previewLeft, panelTop + PADDING, TEXT);
        graphics.drawString(font, Messages.SHOWOFF_LABEL_BACKGROUND.get(), sidebarLeft, previewTop, TEXT);
        renderSwatches(graphics, mouseX, mouseY);

        ShowoffView view = session.view();
        Component status = Messages.SHOWOFF_STATUS.get(format(view.yaw()), format(view.pitch()), format(view.zoom()));
        int statusWidth = font.width(status);
        graphics.drawString(font, status, previewRight - statusWidth, footerTop, MUTED);
        Component hint = Messages.SHOWOFF_HINT.get();
        if (font.width(hint) + statusWidth + PADDING * 2 <= previewRight - previewLeft) {
            graphics.drawString(font, hint, previewLeft, footerTop, MUTED);
        }

        int hovered = swatchAt(mouseX, mouseY);
        if (hovered >= 0) {
            graphics.renderTooltip(font, Component.literal(ShowoffBackground.SWATCHES.get(hovered).getName()), mouseX, mouseY);
        }
    }

    private void renderSwatches(GuiGraphics graphics, int mouseX, int mouseY) {
        List<ChatFormatting> swatches = ShowoffBackground.SWATCHES;
        int background = session.background();
        for (int i = 0; i < swatches.size(); i++) {
            int x = swatchLeft(i);
            int y = swatchTop(i);
            int color = ShowoffBackground.of(swatches.get(i));
            graphics.fill(x, y, x + SWATCH, y + SWATCH, color);
            outlineSwatch(graphics, x, y, SWATCH, color == background, inside(mouseX, mouseY, x, y, SWATCH, SWATCH));
        }
        checkerboard(graphics, sidebarLeft, transparentTop, sidebarLeft + SIDEBAR, transparentTop + SWATCH);
        Component label = Messages.SHOWOFF_LABEL_TRANSPARENT.get();
        graphics.drawString(font, label, sidebarLeft + (SIDEBAR - font.width(label)) / 2, transparentTop + (SWATCH - font.lineHeight) / 2 + 1, TEXT);
        outlineSwatch(graphics, sidebarLeft, transparentTop, SIDEBAR, ShowoffBackground.isTransparent(background),
                inside(mouseX, mouseY, sidebarLeft, transparentTop, SIDEBAR, SWATCH));
    }

    private void outlineSwatch(GuiGraphics graphics, int x, int y, int width, boolean selected, boolean hovered) {
        if (selected) {
            graphics.renderOutline(x - 1, y - 1, width + 2, SWATCH + 2, TEXT);
        } else if (hovered) {
            graphics.renderOutline(x - 1, y - 1, width + 2, SWATCH + 2, MUTED);
        }
    }

    private static void checkerboard(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.fill(left, top, right, bottom, CHECKER_DARK);
        for (int y = top; y < bottom; y += CHECKER_SIZE) {
            for (int x = left + ((y - top) / CHECKER_SIZE % 2) * CHECKER_SIZE; x < right; x += CHECKER_SIZE * 2) {
                graphics.fill(x, y, Math.min(x + CHECKER_SIZE, right), Math.min(y + CHECKER_SIZE, bottom), CHECKER_LIGHT);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int swatch = swatchAt(mouseX, mouseY);
        if (swatch != NO_SWATCH) {
            ShowoffClient.background(swatch == TRANSPARENT_SWATCH ? ShowoffBackground.TRANSPARENT : ShowoffBackground.of(ShowoffBackground.SWATCHES.get(swatch)));
            return true;
        }
        if (inside(mouseX, mouseY, previewLeft, previewTop, previewRight - previewLeft, previewBottom - previewTop)) {
            if (button == 0) {
                rotating = true;
                return true;
            }
            if (button == 1 || button == 2) {
                panning = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        ShowoffView view = session.view();
        if (rotating) {
            session.view(view.withAngle(view.yaw() + (float) dragX * ROTATE_DEGREES_PER_PIXEL, view.pitch() + (float) dragY * ROTATE_DEGREES_PER_PIXEL));
            return true;
        }
        if (panning) {
            float panX = view.panX() + (float) (dragX / Math.max(1, previewRight - previewLeft));
            float panY = view.panY() - (float) (dragY / Math.max(1, previewBottom - previewTop));
            session.view(view.withPan(panX, panY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        rotating = false;
        panning = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inside(mouseX, mouseY, previewLeft, previewTop, previewRight - previewLeft, previewBottom - previewTop)) {
            ShowoffView view = session.view();
            session.view(view.withZoom((float) (view.zoom() * Math.pow(ZOOM_STEP, scrollY))));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        preview.close();
        ShowoffClient.closed(session);
    }

    private int guiScale() {
        return (int) minecraft.getWindow().getGuiScale();
    }

    private int swatchAt(double x, double y) {
        for (int i = 0; i < ShowoffBackground.SWATCHES.size(); i++) {
            if (inside(x, y, swatchLeft(i), swatchTop(i), SWATCH, SWATCH)) {
                return i;
            }
        }
        return inside(x, y, sidebarLeft, transparentTop, SIDEBAR, SWATCH) ? TRANSPARENT_SWATCH : NO_SWATCH;
    }

    private int swatchLeft(int index) {
        return sidebarLeft + index % COLUMNS * (SWATCH + GAP);
    }

    private int swatchTop(int index) {
        return swatchTop + index / COLUMNS * (SWATCH + GAP);
    }

    private static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private String fit(String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        return font.plainSubstrByWidth(text, width - font.width(ELLIPSIS)) + ELLIPSIS;
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
