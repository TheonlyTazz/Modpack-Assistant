package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class ShowoffScene {
    private static final double MIN_RADIUS = 0.5;
    private static final double STRUCTURE_REACH = 1.25;
    private static final double ENTITY_REACH = 3.0;

    private final Map<ChunkSectionLayer, VertexRecorder> layers;
    private final List<PlacedBlockEntity> blockEntities;
    private final List<EntityRenderState> entities;
    private final @Nullable PlayerShowoff player;
    private final @Nullable AABB exactBounds;
    private final boolean strict;
    private AABB bounds;
    private List<AABB> silhouette;
    private boolean measuring;
    private @Nullable RuntimeException measurementFailure;
    private int revision;

    ShowoffScene(Map<ChunkSectionLayer, VertexRecorder> layers, List<PlacedBlockEntity> blockEntities,
                 List<EntityRenderState> entities, AABB bounds, @Nullable AABB exactBounds) {
        this(layers, blockEntities, entities, bounds, exactBounds, false, null);
    }

    ShowoffScene(Map<ChunkSectionLayer, VertexRecorder> layers, List<PlacedBlockEntity> blockEntities,
                 List<EntityRenderState> entities, AABB bounds, @Nullable AABB exactBounds, boolean strict) {
        this(layers, blockEntities, entities, bounds, exactBounds, strict, null);
    }

    ShowoffScene(Map<ChunkSectionLayer, VertexRecorder> layers, List<PlacedBlockEntity> blockEntities,
                 List<EntityRenderState> entities, AABB bounds, @Nullable AABB exactBounds, @Nullable PlayerShowoff player) {
        this(layers, blockEntities, entities, bounds, exactBounds, false, player);
    }

    ShowoffScene(Map<ChunkSectionLayer, VertexRecorder> layers, List<PlacedBlockEntity> blockEntities,
                         List<EntityRenderState> entities, AABB bounds, @Nullable AABB exactBounds,
                         boolean strict, @Nullable PlayerShowoff player) {
        this.layers = new EnumMap<>(layers);
        this.blockEntities = new ArrayList<>(blockEntities);
        this.entities = new ArrayList<>(entities);
        this.player = player;
        this.exactBounds = exactBounds;
        this.strict = strict;
        this.bounds = bounds;
        this.silhouette = exactBounds == null && player == null ? List.of() : List.of(bounds);
    }

    Vec3 center() {
        return bounds.getCenter();
    }

    double radius() {
        return Math.max(MIN_RADIUS, new Vec3(bounds.getXsize(), bounds.getYsize(), bounds.getZsize()).length() / 2.0);
    }

    int revision() {
        return revision + (player == null ? 0 : player.revision());
    }

    @Nullable PlayerShowoff player() {
        return player;
    }

    double measureReach() {
        return radius() * (exactBounds != null ? STRUCTURE_REACH : ENTITY_REACH);
    }

    boolean measuring() {
        return measuring;
    }

    @Nullable RuntimeException measurementFailure() {
        return measurementFailure;
    }

    void measurementFailed(RuntimeException failure) {
        measuring = false;
        measurementFailure = failure;
        revision++;
    }

    void startMeasuring() {
        measuring = true;
    }

    void measured(@Nullable AABB measuredBounds, List<AABB> columns) {
        if (measurementFailure != null) {
            return;
        }
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

    void render(PoseStack poseStack, MultiBufferSource.BufferSource buffers, CameraRenderState camera) {
        drawLayer(poseStack, buffers, ChunkSectionLayer.SOLID);
        drawLayer(poseStack, buffers, ChunkSectionLayer.CUTOUT);
        if (!blockEntities.isEmpty() || !entities.isEmpty() || player != null) {
            drawFeatures(poseStack, camera);
        }
        drawLayer(poseStack, buffers, ChunkSectionLayer.TRANSLUCENT);
    }

    private void drawLayer(PoseStack poseStack, MultiBufferSource.BufferSource buffers, ChunkSectionLayer layer) {
        VertexRecorder recorder = layers.get(layer);
        if (recorder == null || recorder.isEmpty()) {
            return;
        }
        RenderType type = switch (layer) {
            case SOLID -> RenderTypes.solidMovingBlock();
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
        };
        recorder.replay(buffers.getBuffer(type), poseStack.last());
        buffers.endBatch(type);
    }

    private void drawFeatures(PoseStack poseStack, CameraRenderState camera) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
        FeatureRenderDispatcher features = minecraft.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage storage = features.getSubmitNodeStorage();

        BlockEntityRenderDispatcher blockEntityDispatcher = minecraft.getBlockEntityRenderDispatcher();
        try {
            Iterator<PlacedBlockEntity> placed = blockEntities.iterator();
            while (placed.hasNext()) {
                PlacedBlockEntity blockEntity = placed.next();
                PoseStack local = copy(poseStack);
                local.translate(blockEntity.pos().getX(), blockEntity.pos().getY(), blockEntity.pos().getZ());
                try {
                    blockEntityDispatcher.submit(blockEntity.state(), local, storage, camera);
                } catch (RuntimeException e) {
                    if (strict) {
                        throw e;
                    }
                    ModpackAssistant.LOGGER.warn("Dropping block entity at {} from the showoff view after it failed to render", blockEntity.pos(), e);
                    placed.remove();
                }
            }
            EntityRenderDispatcher entityDispatcher = minecraft.getEntityRenderDispatcher();
            Iterator<EntityRenderState> states = entities.iterator();
            while (states.hasNext()) {
                EntityRenderState state = states.next();
                try {
                    entityDispatcher.submit(state, camera, state.x, state.y, state.z, copy(poseStack), storage);
                } catch (RuntimeException e) {
                    if (strict) {
                        throw e;
                    }
                    ModpackAssistant.LOGGER.warn("Dropping {} from the showoff view after it failed to render", state.entityType, e);
                    states.remove();
                }
            }
            if (player != null) {
                player.render(poseStack, storage, camera);
            }
            features.renderAllFeatures();
        } finally {
            features.clearSubmitNodes();
        }
    }

    private static PoseStack copy(PoseStack poseStack) {
        PoseStack copy = new PoseStack();
        copy.last().set(poseStack.last());
        return copy;
    }

    record PlacedBlockEntity(BlockPos pos, BlockEntityRenderState state) {
    }
}
