package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class ShowoffMeasure {
    private static final int SIZE = 512;
    private static final double RETRY_GROWTH = 4.0;
    private static final int MAX_ATTEMPTS = 3;
    private static final Projection PROJECTION = new Projection();
    private static final List<Attempt> QUEUE = new ArrayList<>();
    private static @Nullable ProjectionMatrixBuffer projectionBuffer;

    private ShowoffMeasure() {
    }

    static void request(ShowoffScene scene) {
        scene.startMeasuring();
        QUEUE.add(new Attempt(scene, scene.center(), scene.measureReach(), 1));
    }

    static void clear() {
        QUEUE.clear();
    }

    static void process() {
        if (QUEUE.isEmpty()) {
            return;
        }
        List<Attempt> attempts = List.copyOf(QUEUE);
        QUEUE.clear();
        for (Attempt attempt : attempts) {
            try {
                measure(attempt);
            } catch (RuntimeException e) {
                ModpackAssistant.LOGGER.warn("Could not measure the showoff view; framing falls back to hitboxes", e);
                attempt.scene().measured(null, List.of());
            }
        }
    }

    private static void measure(Attempt attempt) {
        Views views = new Views(attempt);
        renderView(attempt, new Quaternionf(), bounds -> views.front(bounds));
        renderView(attempt, new Quaternionf().rotationX(Mth.HALF_PI), bounds -> views.top(bounds));
    }

    private static void renderView(Attempt attempt, Quaternionf rotation, ViewResult result) {
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture color = device.createTexture(() -> "Showoff measure", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                TextureFormat.RGBA8, SIZE, SIZE, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        TextureFormat depthFormat = Minecraft.getInstance().getMainRenderTarget().getDepthTexture().getFormat();
        GpuTexture depth = device.createTexture(() -> "Showoff measure depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, depthFormat, SIZE, SIZE, 1, 1);
        GpuTextureView depthView = device.createTextureView(depth);
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.clearColorAndDepthTextures(color, 0, depth, 1.0);

        float scale = (float) (SIZE / (2.0 * attempt.reach()));
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        buffers.endBatch();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.outputColorTextureOverride = colorView;
        RenderSystem.outputDepthTextureOverride = depthView;
        try {
            ShowoffDraw.render(attempt.scene(), rotation, attempt.center(), attempt.reach(), scale, SIZE / 2.0F, SIZE / 2.0F,
                    SIZE, SIZE, buffers, PROJECTION, projectionBuffer());
            buffers.endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            RenderSystem.restoreProjectionMatrix();
        }

        GpuBuffer readback = device.createBuffer(() -> "Showoff measure readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                (long) SIZE * SIZE * color.getFormat().pixelSize());
        encoder.copyTextureToBuffer(color, readback, 0L, () -> {
            @Nullable ViewBounds bounds;
            try (GpuBuffer.MappedView mapped = encoder.mapBuffer(readback, true, false)) {
                bounds = scan(mapped.data(), scale);
            } finally {
                readback.close();
                colorView.close();
                color.close();
                depthView.close();
                depth.close();
            }
            result.accept(bounds);
        }, 0);
    }

    private static @Nullable ViewBounds scan(ByteBuffer data, float scale) {
        float[] low = new float[SIZE];
        float[] high = new float[SIZE];
        int minX = SIZE;
        int maxX = -1;
        int minY = SIZE;
        int maxY = -1;
        float half = SIZE / 2.0F;
        for (int x = 0; x < SIZE; x++) {
            int top = SIZE;
            int bottom = -1;
            for (int row = 0; row < SIZE; row++) {
                if ((data.get((x + row * SIZE) * 4 + 3) & 0xFF) != 0) {
                    int y = SIZE - 1 - row;
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
            if (bottom < 0) {
                low[x] = Float.NaN;
                high[x] = Float.NaN;
                continue;
            }
            low[x] = (half - bottom - 1) / scale;
            high[x] = (half - top) / scale;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, top);
            maxY = Math.max(maxY, bottom);
        }
        if (maxX < 0) {
            return null;
        }
        boolean clipped = minX == 0 || minY == 0 || maxX == SIZE - 1 || maxY == SIZE - 1;
        return new ViewBounds(low, high, (half - maxY - 1) / scale, (half - minY) / scale, scale, clipped);
    }

    private static ProjectionMatrixBuffer projectionBuffer() {
        if (projectionBuffer == null) {
            projectionBuffer = new ProjectionMatrixBuffer("Showoff measure");
        }
        return projectionBuffer;
    }

    private interface ViewResult {
        void accept(@Nullable ViewBounds bounds);
    }

    private record Attempt(ShowoffScene scene, Vec3 center, double reach, int number) {
    }

    private record ViewBounds(float[] low, float[] high, float bottom, float top, float scale, boolean clipped) {
        float left(int column) {
            return (column - SIZE / 2.0F) / scale;
        }
    }

    private static final class Views {
        private final Attempt attempt;
        private @Nullable ViewBounds front;
        private @Nullable ViewBounds top;
        private int received;

        Views(Attempt attempt) {
            this.attempt = attempt;
        }

        void front(@Nullable ViewBounds bounds) {
            front = bounds;
            received();
        }

        void top(@Nullable ViewBounds bounds) {
            top = bounds;
            received();
        }

        private void received() {
            if (++received < 2) {
                return;
            }
            ShowoffScene scene = attempt.scene();
            if (front == null || top == null) {
                scene.measured(null, List.of());
                return;
            }
            if ((front.clipped() || top.clipped()) && attempt.number() < MAX_ATTEMPTS) {
                QUEUE.add(new Attempt(scene, attempt.center(), attempt.reach() * RETRY_GROWTH, attempt.number() + 1));
                return;
            }
            Vec3 center = attempt.center();
            List<AABB> columns = new ArrayList<>();
            AABB overall = null;
            for (int x = 0; x < SIZE; x++) {
                boolean seenFront = !Float.isNaN(front.low()[x]);
                boolean seenTop = !Float.isNaN(top.low()[x]);
                if (!seenFront && !seenTop) {
                    continue;
                }
                double minY = center.y + (seenFront ? front.low()[x] : front.bottom());
                double maxY = center.y + (seenFront ? front.high()[x] : front.top());
                double minZ = center.z - (seenTop ? top.high()[x] : top.top());
                double maxZ = center.z - (seenTop ? top.low()[x] : top.bottom());
                AABB column = new AABB(center.x + front.left(x), minY, minZ, center.x + front.left(x + 1), maxY, maxZ);
                columns.add(column);
                overall = overall == null ? column : overall.minmax(column);
            }
            scene.measured(overall, columns);
        }
    }
}
