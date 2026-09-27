package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector4f;

final class ShowoffDraw {
    private static final float FILL = 0.9F;
    private static final float MIN_DEPTH = 1000.0F;
    private static final float MIN_HALF_EXTENT = 0.25F;

    private ShowoffDraw() {
    }

    static void draw(ShowoffScene scene, ShowoffView view, int width, int height, MultiBufferSource.BufferSource buffers,
                     Projection projection, ProjectionMatrixBuffer projectionBuffer) {
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
                width, height, buffers, projection, projectionBuffer);
    }

    static void render(ShowoffScene scene, Quaternionf rotation, Vec3 center, double reach, float scale, float originX, float originY,
                       int width, int height, MultiBufferSource.BufferSource buffers, Projection projection, ProjectionMatrixBuffer projectionBuffer) {
        float depth = Math.max(MIN_DEPTH, (float) (reach * scale * 2.0) + 16.0F);
        projection.setupOrtho(-depth, depth, width, height, true);
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);

        PoseStack poseStack = new PoseStack();
        poseStack.translate(originX, originY, 0.0F);
        poseStack.scale(scale, -scale, scale);
        poseStack.mulPose(rotation);
        poseStack.translate(-center.x, -center.y, -center.z);

        CameraRenderState camera = new CameraRenderState();
        camera.orientation = rotation.conjugate(new Quaternionf()).rotateY(Mth.PI);
        scene.render(poseStack, buffers, camera);
    }
}
