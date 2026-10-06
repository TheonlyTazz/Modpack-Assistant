package com.breakinblocks.modpackassistant.client.showoff;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;
import java.util.Objects;

final class PlayerPoseSlider extends AbstractSliderButton {
    private static final String[] AXES = {"Pitch", "Yaw", "Roll"};
    private final ShowoffSession session;
    private final PlayerShowoff player;
    private final int axis;
    private float angle;

    PlayerPoseSlider(int x, int y, int width, int height, ShowoffSession session, int axis) {
        super(x, y, width, height, Component.empty(), 0.5);
        this.session = session;
        this.player = Objects.requireNonNull(session.player(), "Limb sliders require a player scene");
        this.axis = Objects.checkIndex(axis, AXES.length);
        sync();
    }

    void sync() {
        angle = player.rotation(session.posePart(), axis);
        value = (angle + 180.0) / 360.0;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(AXES[axis] + " " + String.format(Locale.ROOT, "%.0f", angle)));
    }

    @Override
    protected void applyValue() {
        angle = Mth.clamp(Math.round((float) (value * 360.0 - 180.0)), -180.0F, 180.0F);
        player.rotation(session.posePart(), axis, angle);
        updateMessage();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0) {
            return false;
        }
        angle = Mth.clamp(angle + (float) Math.signum(scrollY), -180.0F, 180.0F);
        player.rotation(session.posePart(), axis, angle);
        sync();
        return true;
    }
}
