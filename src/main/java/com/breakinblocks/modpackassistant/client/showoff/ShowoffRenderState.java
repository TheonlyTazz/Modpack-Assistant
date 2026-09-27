package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;

record ShowoffRenderState(
        ShowoffSession session,
        ShowoffView view,
        int revision,
        int x0,
        int y0,
        int x1,
        int y1,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {
    ShowoffRenderState(ShowoffSession session, ShowoffView view, int revision, int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea) {
        this(session, view, revision, x0, y0, x1, y1, scissorArea, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }

    @Override
    public float scale() {
        return 1.0F;
    }
}
