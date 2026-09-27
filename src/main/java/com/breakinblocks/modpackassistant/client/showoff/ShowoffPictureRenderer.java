package com.breakinblocks.modpackassistant.client.showoff;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.jspecify.annotations.Nullable;

final class ShowoffPictureRenderer extends PictureInPictureRenderer<ShowoffRenderState> {
    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("Showoff preview");
    private @Nullable ShowoffRenderState rendered;

    ShowoffPictureRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<ShowoffRenderState> getRenderStateClass() {
        return ShowoffRenderState.class;
    }

    @Override
    protected boolean textureIsReadyToBlit(ShowoffRenderState state) {
        return state.equals(rendered);
    }

    @Override
    protected void renderToTexture(ShowoffRenderState state, PoseStack poseStack) {
        int guiScale = Minecraft.getInstance().gameRenderer.getGameRenderState().windowRenderState.guiScale;
        rendered = null;
        ShowoffDraw.draw(state.session().scene(), state.view(), (state.x1() - state.x0()) * guiScale, (state.y1() - state.y0()) * guiScale,
                bufferSource, projection, projectionBuffer);
        rendered = state;
    }

    @Override
    protected String getTextureLabel() {
        return "showoff";
    }

    @Override
    public void close() {
        super.close();
        projectionBuffer.close();
    }
}
