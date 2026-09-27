package com.breakinblocks.modpackassistant.client.showoff;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;

import java.util.Arrays;

final class VertexRecorder implements VertexConsumer {
    private static final int INITIAL_CAPACITY = 1024;

    private float[] positions = new float[INITIAL_CAPACITY * 3];
    private float[] normals = new float[INITIAL_CAPACITY * 3];
    private float[] uvs = new float[INITIAL_CAPACITY * 2];
    private int[] colors = new int[INITIAL_CAPACITY];
    private int[] overlays = new int[INITIAL_CAPACITY];
    private int[] lights = new int[INITIAL_CAPACITY];
    private int count;
    private float offsetX;
    private float offsetY;
    private float offsetZ;

    void offset(float x, float y, float z) {
        offsetX = x;
        offsetY = y;
        offsetZ = z;
    }

    boolean isEmpty() {
        return count == 0;
    }

    void replay(VertexConsumer out, PoseStack.Pose pose) {
        for (int i = 0; i < count; i++) {
            out.addVertex(pose, positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
                    .setColor(colors[i])
                    .setUv(uvs[i * 2], uvs[i * 2 + 1])
                    .setOverlay(overlays[i])
                    .setLight(lights[i])
                    .setNormal(pose, normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
        }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (count == colors.length) {
            grow();
        }
        int index = count++;
        positions[index * 3] = x + offsetX;
        positions[index * 3 + 1] = y + offsetY;
        positions[index * 3 + 2] = z + offsetZ;
        normals[index * 3] = 0.0F;
        normals[index * 3 + 1] = 1.0F;
        normals[index * 3 + 2] = 0.0F;
        uvs[index * 2] = 0.0F;
        uvs[index * 2 + 1] = 0.0F;
        colors[index] = -1;
        overlays[index] = OverlayTexture.NO_OVERLAY;
        lights[index] = LightCoordsUtil.FULL_BRIGHT;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        colors[count - 1] = ARGB.color(a, r, g, b);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        colors[count - 1] = color;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        uvs[(count - 1) * 2] = u;
        uvs[(count - 1) * 2 + 1] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        overlays[count - 1] = (u & 0xFFFF) | (v << 16);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        lights[count - 1] = (u & 0xFFFF) | (v << 16);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        normals[(count - 1) * 3] = x;
        normals[(count - 1) * 3 + 1] = y;
        normals[(count - 1) * 3 + 2] = z;
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    private void grow() {
        int capacity = colors.length * 2;
        positions = Arrays.copyOf(positions, capacity * 3);
        normals = Arrays.copyOf(normals, capacity * 3);
        uvs = Arrays.copyOf(uvs, capacity * 2);
        colors = Arrays.copyOf(colors, capacity);
        overlays = Arrays.copyOf(overlays, capacity);
        lights = Arrays.copyOf(lights, capacity);
    }
}
