package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

final class ShowoffMeasure {
    private static final int SIZE = 512;
    private static final double RETRY_GROWTH = 4.0;
    private static final int MAX_ATTEMPTS = 3;
    private static final List<Attempt> QUEUE = new ArrayList<>();
    private static @Nullable ShowoffTarget target;

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
        while (!QUEUE.isEmpty()) {
            Attempt attempt = QUEUE.remove(0);
            try {
                measure(attempt);
            } catch (RuntimeException e) {
                ModpackAssistant.LOGGER.warn("Could not measure the showoff view; framing falls back to hitboxes", e);
                attempt.scene().measured(null, List.of());
            }
        }
    }

    private static void measure(Attempt attempt) {
        ShowoffScene scene = attempt.scene();
        ViewBounds front = renderView(attempt, new Quaternionf());
        ViewBounds top = renderView(attempt, new Quaternionf().rotationX(Mth.HALF_PI));
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

    private static @Nullable ViewBounds renderView(Attempt attempt, Quaternionf rotation) {
        float scale = (float) (SIZE / (2.0 * attempt.reach()));
        ShowoffTarget measureTarget = target();
        measureTarget.draw(() -> ShowoffDraw.render(attempt.scene(), rotation, attempt.center(), attempt.reach(), scale,
                SIZE / 2.0F, SIZE / 2.0F, SIZE, SIZE));
        try (NativeImage image = measureTarget.read()) {
            return scan(image, scale);
        }
    }

    private static @Nullable ViewBounds scan(NativeImage image, float scale) {
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
            for (int y = 0; y < SIZE; y++) {
                if ((image.getPixelRGBA(x, y) >>> 24) != 0) {
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

    private static ShowoffTarget target() {
        if (target == null) {
            target = new ShowoffTarget(SIZE, SIZE);
        }
        return target;
    }

    private record Attempt(ShowoffScene scene, Vec3 center, double reach, int number) {
    }

    private record ViewBounds(float[] low, float[] high, float bottom, float top, float scale, boolean clipped) {
        float left(int column) {
            return (column - SIZE / 2.0F) / scale;
        }
    }
}
