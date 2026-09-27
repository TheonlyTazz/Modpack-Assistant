package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector4f;

final class ShowoffDraw {
    private static final float FILL = 0.9F;
    private static final float MIN_DEPTH = 1000.0F;
    private static final float MIN_HALF_EXTENT = 0.25F;

    private ShowoffDraw() {
    }

    static void draw(ShowoffScene scene, ShowoffView view, int width, int height) {
        Quaternionf rotation = new Quaternionf()
                .rotationX(view.pitch() * Mth.DEG_TO_RAD)
                .rotateY(view.yaw() * Mth.DEG_TO_RAD);
        Vector4f bounds = scene.screenBounds(rotation);
        float halfWidth = Math.max(MIN_HALF_EXTENT, (bounds.y - bounds.x) / 2.0F);
        float halfHeight = Math.max(MIN_HALF_EXTENT, (bounds.w - bounds.z) / 2.0F);
        float scale = view.zoom() * FILL * Math.min(width / (2.0F * halfWidth), height / (2.0F * halfHeight));
        float middleX = (bounds.x + bounds.y) / 2.0F;
        float middleY = (bounds.z + bounds.w) / 2.0F;
        render(scene, rotation, scene.center(), scene.radius(), scale,
                width / 2.0F + view.panX() * width - middleX * scale,
                height / 2.0F - view.panY() * height + middleY * scale,
                width, height);
    }

    static void render(ShowoffScene scene, Quaternionf rotation, Vec3 center, double reach, float scale, float originX, float originY,
                       int width, int height) {
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        buffers.endBatch();
        float depth = Math.max(MIN_DEPTH, (float) (reach * scale * 2.0) + 16.0F);
        float fogStart = RenderSystem.getShaderFogStart();
        float[] shaderColor = RenderSystem.getShaderColor().clone();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, width, height, 0.0F, -depth, depth), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableDepthTest();
        try {
            PoseStack poseStack = new PoseStack();
            poseStack.translate(originX, originY, 0.0F);
            poseStack.scale(scale, -scale, scale);
            poseStack.mulPose(rotation);
            poseStack.translate(-center.x, -center.y, -center.z);
            scene.render(poseStack, buffers, rotation.conjugate(new Quaternionf()).rotateY(Mth.PI));
            buffers.endBatch();
        } finally {
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            RenderSystem.setShaderFogStart(fogStart);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }
}
