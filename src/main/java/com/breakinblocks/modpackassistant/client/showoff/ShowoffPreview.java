package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

final class ShowoffPreview implements AutoCloseable {
    private @Nullable ShowoffTarget target;
    private @Nullable Key rendered;

    void draw(GuiGraphics graphics, ShowoffSession session, int x0, int y0, int x1, int y1, int pixelWidth, int pixelHeight) {
        if (pixelWidth <= 0 || pixelHeight <= 0) {
            return;
        }
        Key key = new Key(session.view(), session.scene().revision(), pixelWidth, pixelHeight);
        if (target == null || !key.equals(rendered)) {
            graphics.flush();
            if (target == null || target.width() != pixelWidth || target.height() != pixelHeight) {
                close();
                target = new ShowoffTarget(pixelWidth, pixelHeight);
            }
            ShowoffScene scene = session.scene();
            ShowoffView view = session.view();
            target.draw(() -> ShowoffDraw.draw(scene, view, pixelWidth, pixelHeight));
            rendered = key;
        }
        blit(graphics, target.textureId(), x0, y0, x1, y1);
    }

    private static void blit(GuiGraphics graphics, int texture, int x0, int y0, int x1, int y1) {
        graphics.flush();
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(pose, x0, y0, 0.0F).setUv(0.0F, 1.0F);
        builder.addVertex(pose, x0, y1, 0.0F).setUv(0.0F, 0.0F);
        builder.addVertex(pose, x1, y1, 0.0F).setUv(1.0F, 0.0F);
        builder.addVertex(pose, x1, y0, 0.0F).setUv(1.0F, 1.0F);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    @Override
    public void close() {
        if (target != null) {
            target.close();
            target = null;
        }
        rendered = null;
    }

    private record Key(ShowoffView view, int revision, int width, int height) {
    }
}
