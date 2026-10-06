package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.showoff.ShowoffBackground;
import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.breakinblocks.modpackassistant.util.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;

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
    private int sidebarWidth = SIDEBAR;
    private int swatchSize = SWATCH;
    private int swatchColumns = COLUMNS;
    private int limbTop;
    private int titleWidth;
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
    private @Nullable EditBox playerInput;
    private final PlayerPoseSlider[] poseSliders = new PlayerPoseSlider[3];
    private int pendingLookupTicks;
    private String pendingLookup = "";
    private boolean equipmentOpen;
    private @Nullable EquipmentSlot pickerSlot;
    private List<ItemStack> pickerItems = List.of();
    private int pickerScroll;
    private @Nullable EditBox pickerSearch;
    private final Map<EquipmentSlot, List<ItemStack>> pickerChoices = new EnumMap<>(EquipmentSlot.class);

    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST,
            EquipmentSlot.MAINHAND, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

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
        sidebarWidth = session.player() == null ? SIDEBAR : 110;
        swatchSize = session.player() != null && height < 360 ? 10 : SWATCH;
        swatchColumns = session.player() != null && height < 360 ? 8 : COLUMNS;
        sidebarLeft = panelRight - PADDING - sidebarWidth;
        previewLeft = panelLeft + PADDING;
        previewTop = panelTop + PADDING + (session.player() == null ? font.lineHeight + 4 : BUTTON_HEIGHT + 4);
        previewRight = sidebarLeft - PADDING;
        footerTop = panelBottom - PADDING - font.lineHeight;
        previewBottom = footerTop - 4 - BUTTON_HEIGHT - PADDING;
        swatchTop = previewTop + font.lineHeight + 3;
        int rows = (ShowoffBackground.SWATCHES.size() + swatchColumns - 1) / swatchColumns;
        transparentTop = swatchTop + rows * (swatchSize + GAP);
        int guiScale = minecraft.getWindow().getGuiScale();
        session.previewSize((previewRight - previewLeft) * guiScale, (previewBottom - previewTop) * guiScale);

        int actionHeight = session.player() != null && height < 360 ? 14 : BUTTON_HEIGHT;
        int buttonTop = previewBottom + PADDING;
        addRenderableWidget(Button.builder(Messages.SHOWOFF_BUTTON_DONE.get(), button -> onClose())
                .bounds(sidebarLeft, buttonTop, sidebarWidth, actionHeight)
                .build());
        buttonTop -= actionHeight + GAP;
        addRenderableWidget(Button.builder(Messages.SHOWOFF_BUTTON_SCREENSHOT.get(), button -> ShowoffCapture.request(session, "", 0, 0))
                .bounds(sidebarLeft, buttonTop, sidebarWidth, actionHeight)
                .build());
        buttonTop -= actionHeight + GAP;
        addRenderableWidget(Button.builder(Messages.SHOWOFF_BUTTON_RESET.get(), button -> {
                    session.view(ShowoffView.DEFAULT);
                    if (session.player() != null) {
                        session.player().reset();
                        syncPose();
                    }
                })
                .bounds(sidebarLeft, buttonTop, sidebarWidth, actionHeight)
                .build());

        PlayerShowoff player = session.player();
        titleWidth = previewRight - previewLeft;
        if (player != null) {
            titleWidth = Math.min(font.width(title) + PADDING, Math.max(30, (previewRight - previewLeft) / 2 - PADDING));
            int inputLeft = previewLeft + titleWidth + PADDING;
            playerInput = new EditBox(font, inputLeft, panelTop + PADDING, previewRight - inputLeft, BUTTON_HEIGHT,
                    Component.literal("Playername/UUID"));
            playerInput.setMaxLength(36);
            playerInput.setHint(Component.literal("Playername/UUID"));
            playerInput.setValue(session.playerInput());
            playerInput.setResponder(value -> {
                session.playerInput(value);
                player.inputChanged();
                pendingLookup = value;
                pendingLookupTicks = 8;
            });
            addRenderableWidget(playerInput);
            limbTop = transparentTop + swatchSize + GAP + 2;
            int controlsTop = limbTop + font.lineHeight + GAP;
            int controlHeight = Math.min(BUTTON_HEIGHT, (buttonTop - GAP - controlsTop - 3 * GAP) / 4);
            if (controlHeight < 8) {
                throw new IllegalStateException("Showoff GUI is too small for player limb controls");
            }
            Button limbSelector = addRenderableWidget(Button.builder(Component.literal(PlayerShowoff.PART_NAMES[session.posePart()]), button -> {
                session.posePart((session.posePart() + 1) % PlayerShowoff.PARTS);
                button.setMessage(Component.literal(PlayerShowoff.PART_NAMES[session.posePart()]));
                syncPose();
            }).bounds(sidebarLeft, controlsTop, sidebarWidth, controlHeight).build());
            limbSelector.setTooltip(Tooltip.create(Component.literal("Click to select the next limb")));
            for (int axis = 0; axis < 3; axis++) {
                poseSliders[axis] = addRenderableWidget(new PlayerPoseSlider(sidebarLeft,
                        controlsTop + (axis + 1) * (controlHeight + GAP), sidebarWidth, controlHeight, session, axis));
            }
        }
        int sliderTop = previewBottom + PADDING;
        int sliderWidth = (previewRight - previewLeft - PADDING) / 2;
        yawSlider = addRenderableWidget(ShowoffAngleSlider.yaw(previewLeft, sliderTop, sliderWidth, BUTTON_HEIGHT, session));
        pitchSlider = addRenderableWidget(ShowoffAngleSlider.pitch(previewRight - sliderWidth, sliderTop, sliderWidth, BUTTON_HEIGHT, session));
        if (pickerSlot != null) {
            if (pickerSearch != null) {
                pickerSearch.setX(pickerSearchLeft());
                pickerSearch.setY(pickerSearchTop());
                pickerSearch.setWidth(pickerSearchWidth());
            }
            pickerScroll = Math.min(pickerScroll, Math.max(0, pickerRows() - pickerVisibleRows()));
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, BACKDROP);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (yawSlider != null && pitchSlider != null) {
            yawSlider.sync();
            pitchSlider.sync();
        }
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL);
        graphics.outline(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop, BORDER);

        int background = session.background();
        if (ShowoffBackground.isTransparent(background)) {
            checkerboard(graphics, previewLeft, previewTop, previewRight, previewBottom);
        } else {
            graphics.fill(previewLeft, previewTop, previewRight, previewBottom, background);
        }
        graphics.submitPictureInPictureRenderState(new ShowoffRenderState(session, session.view(), session.scene().revision(),
                previewLeft, previewTop, previewRight, previewBottom, graphics.peekScissorStack()));
        graphics.outline(previewLeft - 1, previewTop - 1, previewRight - previewLeft + 2, previewBottom - previewTop + 2, BORDER);

        graphics.text(font, fit(title.getString(), titleWidth), previewLeft, panelTop + PADDING + (session.player() == null ? 0 : 6), TEXT);
        graphics.text(font, Messages.SHOWOFF_LABEL_BACKGROUND.get(), sidebarLeft, previewTop, TEXT);
        extractSwatches(graphics, mouseX, mouseY);
        if (session.player() != null) {
            extractEquipmentButton(graphics, mouseX, mouseY);
            graphics.text(font, Component.literal("Limbs"), sidebarLeft, limbTop, TEXT);
            graphics.text(font, fit(session.player().status(), sidebarWidth), sidebarLeft, panelTop + PADDING, MUTED);
            if (inside(mouseX, mouseY, sidebarLeft, panelTop + PADDING, sidebarWidth, font.lineHeight)) {
                graphics.setTooltipForNextFrame(font, Component.literal(session.player().status()), mouseX, mouseY);
            }
        }

        ShowoffView view = session.view();
        Component status = Messages.SHOWOFF_STATUS.get(format(view.yaw()), format(view.pitch()), format(view.zoom()));
        int statusWidth = font.width(status);
        graphics.text(font, status, previewRight - statusWidth, footerTop, MUTED);
        Component hint = Messages.SHOWOFF_HINT.get();
        if (font.width(hint) + statusWidth + PADDING * 2 <= previewRight - previewLeft) {
            graphics.text(font, hint, previewLeft, footerTop, MUTED);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);
        if (equipmentOpen) {
            extractEquipmentOverlay(graphics, mouseX, mouseY);
            if (pickerSlot != null && pickerSearch != null) {
                pickerSearch.extractRenderState(graphics, mouseX, mouseY, a);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (pendingLookupTicks > 0 && --pendingLookupTicks == 0 && session.player() != null) {
            session.player().lookup(pendingLookup);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (pickerSlot != null && event.key() == 256) {
            closePicker();
            return true;
        }
        if (pickerSlot != null) {
            if (pickerSearch != null) pickerSearch.keyPressed(event);
            return true;
        }
        if (equipmentOpen && event.key() == 256) {
            equipmentOpen = false;
            return true;
        }
        if (playerInput != null && playerInput.isFocused() && (event.key() == 257 || event.key() == 335) && session.player() != null) {
            pendingLookup = playerInput.getValue();
            session.playerInput(pendingLookup);
            pendingLookupTicks = 0;
            session.player().lookup(pendingLookup);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (pickerSlot != null) {
            if (pickerSearch != null) pickerSearch.charTyped(event);
            return true;
        }
        return super.charTyped(event);
    }

    private void syncPose() {
        for (PlayerPoseSlider slider : poseSliders) {
            if (slider != null) {
                slider.sync();
            }
        }
    }

    private void extractSwatches(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<ChatFormatting> swatches = ShowoffBackground.SWATCHES;
        int background = session.background();
        for (int i = 0; i < swatches.size(); i++) {
            int x = swatchLeft(i);
            int y = swatchTop(i);
            int color = ShowoffBackground.of(swatches.get(i));
            graphics.fill(x, y, x + swatchSize, y + swatchSize, color);
            outlineSwatch(graphics, x, y, swatchSize, color == background, inside(mouseX, mouseY, x, y, swatchSize, swatchSize));
        }
        checkerboard(graphics, sidebarLeft, transparentTop, sidebarLeft + sidebarWidth, transparentTop + swatchSize);
        Component label = Messages.SHOWOFF_LABEL_TRANSPARENT.get();
        graphics.text(font, label, sidebarLeft + (sidebarWidth - font.width(label)) / 2, transparentTop + (swatchSize - font.lineHeight) / 2 + 1, TEXT);
        outlineSwatch(graphics, sidebarLeft, transparentTop, sidebarWidth, ShowoffBackground.isTransparent(background),
                inside(mouseX, mouseY, sidebarLeft, transparentTop, sidebarWidth, swatchSize));

        int hovered = swatchAt(mouseX, mouseY);
        if (hovered >= 0) {
            graphics.setTooltipForNextFrame(font, Component.literal(swatches.get(hovered).getName()), mouseX, mouseY);
        }
    }

    private void outlineSwatch(GuiGraphicsExtractor graphics, int x, int y, int width, boolean selected, boolean hovered) {
        if (selected) {
            graphics.outline(x - 1, y - 1, width + 2, swatchSize + 2, TEXT);
        } else if (hovered) {
            graphics.outline(x - 1, y - 1, width + 2, swatchSize + 2, MUTED);
        }
    }

    private static void checkerboard(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        graphics.fill(left, top, right, bottom, CHECKER_DARK);
        for (int y = top; y < bottom; y += CHECKER_SIZE) {
            for (int x = left + ((y - top) / CHECKER_SIZE % 2) * CHECKER_SIZE; x < right; x += CHECKER_SIZE * 2) {
                graphics.fill(x, y, Math.min(x + CHECKER_SIZE, right), Math.min(y + CHECKER_SIZE, bottom), CHECKER_LIGHT);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (session.player() != null && equipmentOpen && pickerSlot == null
                && inside(event.x(), event.y(), previewLeft + PADDING, previewTop + PADDING, 24, 24)) {
            if (event.button() == 0) equipmentOpen = false;
            return true;
        }
        if (session.player() != null && equipmentOpen) {
            boolean handled = equipmentClick(event.x(), event.y(), event.button(), event);
            if (handled) return true;
        }
        if (session.player() != null && inside(event.x(), event.y(), previewLeft + PADDING, previewTop + PADDING, 24, 24)) {
            equipmentOpen = !equipmentOpen;
            pickerSlot = null;
            if (equipmentOpen) {
                clearFocus();
                rotating = false;
                panning = false;
            }
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        int swatch = swatchAt(event.x(), event.y());
        if (swatch != NO_SWATCH) {
            ShowoffClient.background(swatch == TRANSPARENT_SWATCH ? ShowoffBackground.TRANSPARENT : ShowoffBackground.of(ShowoffBackground.SWATCHES.get(swatch)));
            return true;
        }
        if (inside(event.x(), event.y(), previewLeft, previewTop, previewRight - previewLeft, previewBottom - previewTop)) {
            if (event.button() == 0) {
                rotating = true;
                return true;
            }
            if (event.button() == 1 || event.button() == 2) {
                panning = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (pickerSlot != null) {
            if (pickerSearch != null) pickerSearch.mouseDragged(event, dx, dy);
            return true;
        }
        ShowoffView view = session.view();
        if (rotating) {
            session.view(view.withAngle(view.yaw() + (float) dx * ROTATE_DEGREES_PER_PIXEL, view.pitch() + (float) dy * ROTATE_DEGREES_PER_PIXEL));
            return true;
        }
        if (panning) {
            float panX = view.panX() + (float) (dx / Math.max(1, previewRight - previewLeft));
            float panY = view.panY() - (float) (dy / Math.max(1, previewBottom - previewTop));
            session.view(view.withPan(panX, panY));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        rotating = false;
        panning = false;
        if (pickerSlot != null) {
            if (pickerSearch != null) pickerSearch.mouseReleased(event);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (pickerSlot != null) {
            if (pickerSlot != null && inside(x, y, pickerLeft(), pickerTop(), pickerWidth(), pickerHeight())) {
                int rows = pickerRows();
                pickerScroll = Math.max(0, Math.min(Math.max(0, rows - pickerVisibleRows()), pickerScroll - (int) Math.signum(scrollY)));
            }
            return true;
        }
        if (inside(x, y, previewLeft, previewTop, previewRight - previewLeft, previewBottom - previewTop)) {
            ShowoffView view = session.view();
            session.view(view.withZoom((float) (view.zoom() * Math.pow(ZOOM_STEP, scrollY))));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        ShowoffClient.closed(session);
    }

    private int swatchAt(double x, double y) {
        for (int i = 0; i < ShowoffBackground.SWATCHES.size(); i++) {
            if (inside(x, y, swatchLeft(i), swatchTop(i), swatchSize, swatchSize)) {
                return i;
            }
        }
        return inside(x, y, sidebarLeft, transparentTop, sidebarWidth, swatchSize) ? TRANSPARENT_SWATCH : NO_SWATCH;
    }

    private int swatchLeft(int index) {
        return sidebarLeft + index % swatchColumns * (swatchSize + GAP);
    }

    private int swatchTop(int index) {
        return swatchTop + index / swatchColumns * (swatchSize + GAP);
    }

    private static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private void extractEquipmentButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = previewLeft + PADDING;
        int top = previewTop + PADDING;
        boolean hovered = inside(mouseX, mouseY, left, top, 24, 24);
        graphics.fill(left, top, left + 24, top + 24, hovered ? 0xFF5A5A5A : 0xFF303030);
        graphics.outline(left, top, 24, 24, equipmentOpen ? TEXT : BORDER);
        graphics.item(new ItemStack(Items.IRON_CHESTPLATE), left + 4, top + 4);
        if (hovered) {
            graphics.setTooltipForNextFrame(Component.literal("Equipment"), mouseX, mouseY);
        }
    }

    private void extractEquipmentOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int crossLeft = equipmentCrossLeft();
        int crossTop = equipmentCrossTop();
        graphics.fill(crossLeft - 6, crossTop - 6, crossLeft + 78, crossTop + 112, 0xE8101010);
        graphics.outline(crossLeft - 6, crossTop - 6, 84, 118, BORDER);
        slotButton(graphics, EquipmentSlot.HEAD, crossLeft + 27, crossTop, mouseX, mouseY);
        slotButton(graphics, EquipmentSlot.OFFHAND, crossLeft, crossTop + 27, mouseX, mouseY);
        slotButton(graphics, EquipmentSlot.CHEST, crossLeft + 27, crossTop + 27, mouseX, mouseY);
        slotButton(graphics, EquipmentSlot.MAINHAND, crossLeft + 54, crossTop + 27, mouseX, mouseY);
        slotButton(graphics, EquipmentSlot.LEGS, crossLeft + 27, crossTop + 54, mouseX, mouseY);
        slotButton(graphics, EquipmentSlot.FEET, crossLeft + 27, crossTop + 81, mouseX, mouseY);
        if (pickerSlot != null) {
            extractPicker(graphics, mouseX, mouseY);
        }
    }

    private void slotButton(GuiGraphicsExtractor graphics, EquipmentSlot slot, int left, int top, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, left, top, 24, 24);
        graphics.fill(left, top, left + 24, top + 24, hovered ? 0xFF5A5A5A : 0xFF292929);
        graphics.outline(left, top, 24, 24, slot == pickerSlot ? TEXT : BORDER);
        ItemStack stack = session.player().equipment(slot);
        if (stack.isEmpty()) {
            graphics.centeredText(font, Component.literal(slot.getName().substring(0, 1).toUpperCase(Locale.ROOT)), left + 12, top + 8, MUTED);
            if (hovered) graphics.setTooltipForNextFrame(Component.literal(slot.getName()), mouseX, mouseY);
        } else {
            graphics.item(stack, left + 4, top + 4);
            if (hovered) graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
        }
    }

    private void extractPicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = pickerLeft();
        int top = pickerTop();
        int width = pickerWidth();
        int height = pickerHeight();
        graphics.fill(left, top, left + width, top + height, 0xF0181818);
        graphics.outline(left, top, width, height, TEXT);
        graphics.text(font, Component.literal("Item Picker · " + pickerSlot.getName()), left + 8, top + 7, TEXT);
        int gridTop = top + 39;
        int cell = 24;
        graphics.fill(left + 8, gridTop, left + 32, gridTop + 24, inside(mouseX, mouseY, left + 8, gridTop, 24, 24) ? 0xFF5A5A5A : 0xFF292929);
        graphics.outline(left + 8, gridTop, 24, 24, BORDER);
        graphics.centeredText(font, Component.literal("×"), left + 20, gridTop + 8, MUTED);
        if (inside(mouseX, mouseY, left + 8, gridTop, 24, 24)) {
            graphics.setTooltipForNextFrame(Component.literal("Clear " + pickerSlot.getName()), mouseX, mouseY);
        }
        int columns = pickerColumns();
        int gridLeft = left + 8;
        int itemsTop = gridTop + 27;
        int start = pickerScroll * columns;
        for (int i = 0; i < pickerVisibleRows() * columns; i++) {
            int index = start + i;
            if (index >= pickerItems.size()) break;
            int x = gridLeft + (i % columns) * cell;
            int y = itemsTop + (i / columns) * cell;
            ItemStack stack = pickerItems.get(index);
            boolean hovered = inside(mouseX, mouseY, x, y, 24, 24);
            graphics.fill(x, y, x + 24, y + 24, hovered ? 0xFF5A5A5A : 0xFF292929);
            graphics.item(stack, x + 4, y + 4);
            if (hovered) graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
        }
        if (pickerItems.isEmpty()) {
            graphics.centeredText(font, Component.literal("No matching items"), left + width / 2, itemsTop + 8, MUTED);
        }
        if (pickerRows() > pickerVisibleRows()) {
            int trackTop = itemsTop;
            int trackBottom = itemsTop + pickerVisibleRows() * cell;
            int thumbHeight = Math.max(8, (trackBottom - trackTop) * pickerVisibleRows() / pickerRows());
            int thumbTop = trackTop + (trackBottom - trackTop - thumbHeight) * pickerScroll
                    / Math.max(1, pickerRows() - pickerVisibleRows());
            graphics.fill(left + width - 5, trackTop, left + width - 2, trackBottom, 0xFF303030);
            graphics.fill(left + width - 5, thumbTop, left + width - 2, thumbTop + thumbHeight, MUTED);
        }
    }

    private boolean equipmentClick(double x, double y, int button, MouseButtonEvent event) {
        if (pickerSlot != null) {
            if (!inside(x, y, pickerLeft(), pickerTop(), pickerWidth(), pickerHeight())) {
                closePicker();
                return true;
            }
            if (button == 0 && inside(x, y, pickerSearchLeft(), pickerSearchTop(), pickerSearchWidth(), 18)) {
                if (pickerSearch != null) pickerSearch.mouseClicked(event, false);
                return true;
            }
            int gridTop = pickerTop() + 39;
            if (button == 0 && inside(x, y, pickerLeft() + 8, gridTop, 24, 24)) {
                session.player().equip(pickerSlot, ItemStack.EMPTY);
                closePicker();
                return true;
            }
            int columns = pickerColumns();
            int cell = 24;
            int gridLeft = pickerLeft() + 8;
            int itemsTop = gridTop + 27;
            if (button != 0 || !inside(x, y, gridLeft, itemsTop, columns * cell, pickerVisibleRows() * cell)) {
                return true;
            }
            int col = (int) ((x - gridLeft) / cell);
            int row = (int) ((y - itemsTop) / cell);
            if (col >= 0 && col < columns && row >= 0 && row < pickerVisibleRows()) {
                int index = pickerScroll * columns + row * columns + col;
                if (index >= 0 && index < pickerItems.size()) {
                    session.player().equip(pickerSlot, pickerItems.get(index).copy());
                    closePicker();
                }
            }
            return true;
        }
        int toggleLeft = previewLeft + PADDING;
        int toggleTop = previewTop + PADDING;
        if (inside(x, y, toggleLeft, toggleTop, 24, 24)) {
            if (button == 0) {
                equipmentOpen = false;
                pickerSlot = null;
            }
            return true;
        }
        int left = equipmentCrossLeft();
        int top = equipmentCrossTop();
        EquipmentSlot slot = slotAt(x, y, left, top);
        if (slot != null) {
            if (button == 1) {
                session.player().equip(slot, ItemStack.EMPTY);
                return true;
            }
            if (button != 0) return true;
            pickerSlot = slot;
            ensurePickerSearch();
            pickerSearch.setFocused(false);
            pickerSearch.setValue("");
            pickerSearch.setFocused(true);
            pickerItems = pickerChoices(slot);
            pickerScroll = 0;
            return true;
        }
        if (!inside(x, y, left - 6, top - 6, 84, 118)) {
            equipmentOpen = false;
            return false;
        }
        return true;
    }

    private @Nullable EquipmentSlot slotAt(double x, double y, int left, int top) {
        int[][] positions = {{27, 0}, {0, 27}, {27, 27}, {54, 27}, {27, 54}, {27, 81}};
        for (int i = 0; i < positions.length; i++) {
            if (inside(x, y, left + positions[i][0], top + positions[i][1], 24, 24)) return EQUIPMENT_SLOTS[i];
        }
        return null;
    }

    private List<ItemStack> pickerChoices(EquipmentSlot slot) {
        return pickerChoices.computeIfAbsent(slot, ShowoffScreen::itemChoices);
    }

    private static List<ItemStack> itemChoices(EquipmentSlot slot) {
        List<ItemStack> result = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ItemStack stack = new ItemStack(item);
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (slot.getType() == EquipmentSlot.Type.HAND || equippable != null && equippable.slot() == slot) {
                result.add(stack);
            }
        }
        return List.copyOf(result);
    }

    private void refreshPickerItems() {
        if (pickerSlot == null) return;
        String query = pickerSearch == null ? "" : pickerSearch.getValue().toLowerCase(Locale.ROOT);
        pickerItems = pickerChoices(pickerSlot).stream()
                .filter(stack -> matchesPickerQuery(stack, query))
                .toList();
        pickerScroll = 0;
    }

    private static boolean matchesPickerQuery(ItemStack stack, String query) {
        if (query.isBlank()) return true;
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
        return name.contains(query) || id.contains(query);
    }

    private int equipmentCrossLeft() { return previewLeft + PADDING; }
    private int equipmentCrossTop() { return previewTop + 34; }
    private int pickerLeft() { return (width - pickerWidth()) / 2; }
    private int pickerTop() { return Math.max(panelTop + 8, height / 2 - pickerHeight() / 2); }
    private int pickerWidth() { return Math.min(320, Math.max(192, width - 24)); }
    private int pickerHeight() { return Math.min(220, Math.max(120, height - 24)); }
    private int pickerColumns() { return Math.max(1, (pickerWidth() - 16) / 24); }
    private int pickerVisibleRows() { return Math.max(1, (pickerHeight() - 66) / 24); }
    private int pickerRows() { return (pickerItems.size() + pickerColumns() - 1) / pickerColumns(); }
    private int pickerSearchLeft() { return pickerLeft() + 8; }
    private int pickerSearchTop() { return pickerTop() + 17; }
    private int pickerSearchWidth() { return pickerWidth() - 16; }

    private void closePicker() {
        pickerSlot = null;
        pickerScroll = 0;
        if (pickerSearch != null) pickerSearch.setFocused(false);
    }

    private void ensurePickerSearch() {
        if (pickerSearch == null) {
            pickerSearch = new EditBox(font, pickerSearchLeft(), pickerSearchTop(), pickerSearchWidth(), 18,
                    Component.literal("Search items"));
            pickerSearch.setHint(Component.literal("Search items..."));
            pickerSearch.setMaxLength(64);
            pickerSearch.setResponder(value -> refreshPickerItems());
        } else {
            pickerSearch.setX(pickerSearchLeft());
            pickerSearch.setY(pickerSearchTop());
            pickerSearch.setWidth(pickerSearchWidth());
        }
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
