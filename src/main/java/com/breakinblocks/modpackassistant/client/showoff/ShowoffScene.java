package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ShowoffScene {
    private static final double MIN_RADIUS = 0.5;
    private static final double STRUCTURE_REACH = 1.25;
    private static final double ENTITY_REACH = 3.0;

    private final Map<RenderType, VertexRecorder> layers;
    private final List<BlockEntity> blockEntities;
    private final List<Entity> entities;
    private final @Nullable AABB exactBounds;
    private AABB bounds;
    private List<AABB> silhouette;
    private boolean measuring;
    private int revision;

    ShowoffScene(Map<RenderType, VertexRecorder> layers, List<BlockEntity> blockEntities, List<Entity> entities,
                 AABB bounds, @Nullable AABB exactBounds) {
        this.layers = new LinkedHashMap<>(layers);
        this.blockEntities = new ArrayList<>(blockEntities);
        this.entities = new ArrayList<>(entities);
        this.exactBounds = exactBounds;
        this.bounds = bounds;
        this.silhouette = exactBounds == null ? List.of() : List.of(bounds);
    }

    Vec3 center() {
        return bounds.getCenter();
    }

    double radius() {
        return Math.max(MIN_RADIUS, new Vec3(bounds.getXsize(), bounds.getYsize(), bounds.getZsize()).length() / 2.0);
    }

    double measureReach() {
        return radius() * (exactBounds != null ? STRUCTURE_REACH : ENTITY_REACH);
    }

    int revision() {
        return revision;
    }

    boolean measuring() {
        return measuring;
    }

    void startMeasuring() {
        measuring = true;
    }

    void measured(@Nullable AABB measuredBounds, List<AABB> columns) {
        measuring = false;
        if (measuredBounds != null && !columns.isEmpty()) {
            bounds = measuredBounds;
            silhouette = List.copyOf(columns);
        }
        revision++;
    }

    Vector4f screenBounds(Quaternionf rotation) {
        if (silhouette.isEmpty()) {
            float radius = (float) radius();
            return new Vector4f(-radius, radius, -radius, radius);
        }
        Vec3 center = center();
        Vector4f result = new Vector4f(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE);
        Vector3f corner = new Vector3f();
        for (AABB box : silhouette) {
            for (int i = 0; i < 8; i++) {
                corner.set((float) (((i & 1) == 0 ? box.minX : box.maxX) - center.x),
                        (float) (((i & 2) == 0 ? box.minY : box.maxY) - center.y),
                        (float) (((i & 4) == 0 ? box.minZ : box.maxZ) - center.z));
                rotation.transform(corner);
                result.x = Math.min(result.x, corner.x);
                result.y = Math.max(result.y, corner.x);
                result.z = Math.min(result.z, corner.y);
                result.w = Math.max(result.w, corner.y);
            }
        }
        return result;
    }

    void render(PoseStack poseStack, MultiBufferSource.BufferSource buffers, Quaternionf cameraOrientation) {
        List<RenderType> translucent = List.of(RenderType.translucent(), RenderType.tripwire());
        for (RenderType type : RenderType.chunkBufferLayers()) {
            if (!translucent.contains(type)) {
                drawLayer(poseStack, buffers, type);
            }
        }
        if (!blockEntities.isEmpty() || !entities.isEmpty()) {
            drawFeatures(poseStack, buffers, cameraOrientation);
        }
        for (RenderType type : translucent) {
            drawLayer(poseStack, buffers, type);
        }
    }

    private void drawLayer(PoseStack poseStack, MultiBufferSource.BufferSource buffers, RenderType type) {
        VertexRecorder recorder = layers.get(type);
        if (recorder == null || recorder.isEmpty()) {
            return;
        }
        recorder.replay(buffers.getBuffer(type), poseStack.last());
        buffers.endBatch(type);
    }

    private void drawFeatures(PoseStack poseStack, MultiBufferSource.BufferSource buffers, Quaternionf cameraOrientation) {
        Minecraft minecraft = Minecraft.getInstance();
        Lighting.setupForEntityInInventory();

        Iterator<BlockEntity> placed = blockEntities.iterator();
        while (placed.hasNext()) {
            BlockEntity blockEntity = placed.next();
            BlockPos pos = blockEntity.getBlockPos();
            PoseStack local = copy(poseStack);
            local.translate(pos.getX(), pos.getY(), pos.getZ());
            try {
                renderBlockEntity(minecraft, blockEntity, local, buffers);
            } catch (RuntimeException e) {
                ModpackAssistant.LOGGER.warn("Dropping block entity at {} from the showoff view after it failed to render", pos, e);
                placed.remove();
            }
        }

        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        dispatcher.overrideCameraOrientation(cameraOrientation);
        dispatcher.setRenderShadow(false);
        try {
            Iterator<Entity> iterator = entities.iterator();
            while (iterator.hasNext()) {
                Entity entity = iterator.next();
                try {
                    dispatcher.render(entity, entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), 1.0F,
                            copy(poseStack), buffers, LightTexture.FULL_BRIGHT);
                } catch (RuntimeException e) {
                    ModpackAssistant.LOGGER.warn("Dropping {} from the showoff view after it failed to render", entity.getType(), e);
                    iterator.remove();
                }
            }
        } finally {
            dispatcher.setRenderShadow(true);
        }
        buffers.endBatch();
    }

    private static <T extends BlockEntity> void renderBlockEntity(Minecraft minecraft, T blockEntity, PoseStack poseStack, MultiBufferSource buffers) {
        BlockEntityRenderer<T> renderer = minecraft.getBlockEntityRenderDispatcher().getRenderer(blockEntity);
        if (renderer != null) {
            renderer.render(blockEntity, 1.0F, poseStack, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
    }

    private static PoseStack copy(PoseStack poseStack) {
        PoseStack copy = new PoseStack();
        copy.last().pose().set(poseStack.last().pose());
        copy.last().normal().set(poseStack.last().normal());
        return copy;
    }
}
