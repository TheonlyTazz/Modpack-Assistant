package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.breakinblocks.modpackassistant.util.Messages;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.util.Mth;

import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

final class ShowoffAngleSlider extends AbstractSliderButton {
    private static final float SYNC_TOLERANCE = 0.05F;

    private final ShowoffSession session;
    private final Messages.Msg label;
    private final float min;
    private final float max;
    private final Function<ShowoffView, Float> read;
    private final BiFunction<ShowoffView, Float, ShowoffView> write;
    private float angle;

    ShowoffAngleSlider(int x, int y, int width, int height, ShowoffSession session, Messages.Msg label, float min, float max,
                       Function<ShowoffView, Float> read, BiFunction<ShowoffView, Float, ShowoffView> write) {
        super(x, y, width, height, label.get(""), 0.0);
        this.session = session;
        this.label = label;
        this.min = min;
        this.max = max;
        this.read = read;
        this.write = write;
        show(read.apply(session.view()));
    }

    static ShowoffAngleSlider yaw(int x, int y, int width, int height, ShowoffSession session) {
        return new ShowoffAngleSlider(x, y, width, height, session, Messages.SHOWOFF_SLIDER_YAW, -180.0F, 180.0F,
                ShowoffView::yaw, (view, yaw) -> view.withAngle(yaw, view.pitch()));
    }

    static ShowoffAngleSlider pitch(int x, int y, int width, int height, ShowoffSession session) {
        return new ShowoffAngleSlider(x, y, width, height, session, Messages.SHOWOFF_SLIDER_PITCH,
                ShowoffView.MIN_PITCH, ShowoffView.MAX_PITCH, ShowoffView::pitch, (view, pitch) -> view.withAngle(view.yaw(), pitch));
    }

    void sync() {
        float current = read.apply(session.view());
        if (Mth.degreesDifferenceAbs(current, angle) > SYNC_TOLERANCE) {
            show(current);
        }
    }

    @Override
    protected void updateMessage() {
        setMessage(label.get(String.format(Locale.ROOT, "%.1f", angle)));
    }

    @Override
    protected void applyValue() {
        angle = Math.round(min + (float) value * (max - min));
        session.view(write.apply(session.view(), angle));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0) {
            return false;
        }
        float stepped = Mth.clamp(Math.round(angle) + (float) Math.signum(scrollY), min, max);
        show(stepped);
        session.view(write.apply(session.view(), stepped));
        return true;
    }

    private void show(float angle) {
        this.angle = angle;
        value = Mth.clamp((angle - min) / (max - min), 0.0, 1.0);
        updateMessage();
    }
}
