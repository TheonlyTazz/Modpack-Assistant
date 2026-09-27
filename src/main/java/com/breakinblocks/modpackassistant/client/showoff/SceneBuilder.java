package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class SceneBuilder {
    private static final double MIN_ENTITY_SIZE = 0.5;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final ClientLevel level;
    private final Map<RenderType, VertexRecorder> layers = new LinkedHashMap<>();
    private final List<BlockEntity> blockEntities = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private @Nullable AABB entityBounds;
    private @Nullable AABB blockBounds;

    private SceneBuilder(ClientLevel level) {
        this.level = level;
    }

    static ShowoffScene structure(CompoundTag data, ClientLevel level) {
        return new SceneBuilder(level).buildStructure(data);
    }

    static @Nullable ShowoffScene entity(ResourceLocation typeId, CompoundTag nbt, ClientLevel level) {
        return new SceneBuilder(level).buildEntity(typeId, nbt);
    }

    private ShowoffScene buildStructure(CompoundTag data) {
        ListTag size = data.getList("size", Tag.TAG_INT);
        int sizeX = Math.max(1, size.getInt(0));
        int sizeY = Math.max(1, size.getInt(1));
        int sizeZ = Math.max(1, size.getInt(2));

        HolderLookup<Block> blockLookup = level.holderLookup(Registries.BLOCK);
        ListTag paletteTag = data.contains("palettes", Tag.TAG_LIST)
                ? data.getList("palettes", Tag.TAG_LIST).getList(0)
                : data.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i)));
        }

        Holder<Biome> biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(Biomes.PLAINS).orElse(null);
        StructureBlockGetter blocks = new StructureBlockGetter(level.getLightEngine(), biome, sizeY + 2);
        ListTag blockList = data.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockTag = blockList.getCompound(i);
            ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
            BlockPos pos = new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2));
            int index = blockTag.getInt("state");
            if (index < 0 || index >= palette.size()) {
                continue;
            }
            CompoundTag nbt = blockTag.contains("nbt", Tag.TAG_COMPOUND) ? blockTag.getCompound("nbt") : null;
            BlockState stored = palette.get(index);
            BlockState state = displayed(stored, nbt, blockLookup);
            if (state.isAir()) {
                continue;
            }
            blocks.put(pos, state);
            blockBounds = blockBounds == null ? new AABB(pos) : blockBounds.minmax(new AABB(pos));
            if (state.hasBlockEntity()) {
                BlockEntity blockEntity = createBlockEntity(pos, state, state == stored ? nbt : null);
                if (blockEntity != null) {
                    blocks.putBlockEntity(pos, blockEntity);
                }
            }
        }

        tesselate(blocks);
        for (BlockEntity blockEntity : blocks.blockEntities()) {
            if (minecraft.getBlockEntityRenderDispatcher().getRenderer(blockEntity) != null) {
                blockEntities.add(blockEntity);
            }
        }

        ListTag entityList = data.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entityList.size(); i++) {
            CompoundTag entityTag = entityList.getCompound(i);
            ListTag posTag = entityTag.getList("pos", Tag.TAG_DOUBLE);
            Vec3 pos = new Vec3(posTag.getDouble(0), posTag.getDouble(1), posTag.getDouble(2));
            if (entityTag.contains("nbt", Tag.TAG_COMPOUND)) {
                addTemplateEntity(entityTag.getCompound("nbt"), pos);
            }
        }

        AABB exact = blockBounds;
        AABB bounds = exact != null ? exact : entityBounds != null ? entityBounds : new AABB(0.0, 0.0, 0.0, sizeX, sizeY, sizeZ);
        if (exact != null && entityBounds != null) {
            bounds = bounds.minmax(entityBounds);
        }
        return new ShowoffScene(layers, blockEntities, entities, bounds, exact);
    }

    private @Nullable ShowoffScene buildEntity(ResourceLocation typeId, CompoundTag nbt) {
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId);
        if (type.isEmpty()) {
            return null;
        }
        CompoundTag tag = nbt.copy();
        tag.putString("id", typeId.toString());
        Entity root = EntityType.loadEntityRecursive(tag, level, entity -> entity);
        if (root == null) {
            return null;
        }
        root.moveTo(0.0, 0.0, 0.0, 0.0F, 0.0F);
        root.setYBodyRot(0.0F);
        root.setYHeadRot(0.0F);
        positionPassengers(root);
        addEntity(root);
        for (Entity passenger : root.getIndirectPassengers()) {
            addEntity(passenger);
        }
        if (entities.isEmpty() || entityBounds == null) {
            return null;
        }
        return new ShowoffScene(layers, blockEntities, entities, entityBounds, null);
    }

    private void tesselate(StructureBlockGetter blocks) {
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        RandomSource random = RandomSource.create();
        PoseStack poseStack = new PoseStack();

        for (Long2ObjectMap.Entry<BlockState> entry : blocks.blocks()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            BlockState state = entry.getValue();
            try {
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    VertexRecorder recorder = recorder(ItemBlockRenderTypes.getRenderLayer(fluid));
                    recorder.offset(SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getX())),
                            SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getY())),
                            SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getZ())));
                    try {
                        dispatcher.renderLiquid(pos, blocks, recorder, state, fluid);
                    } finally {
                        recorder.offset(0.0F, 0.0F, 0.0F);
                    }
                }
                if (state.getRenderShape() == RenderShape.MODEL) {
                    BakedModel model = dispatcher.getBlockModel(state);
                    ModelData modelData = model.getModelData(blocks, pos, state, ModelData.EMPTY);
                    long seed = state.getSeed(pos);
                    random.setSeed(seed);
                    for (RenderType type : model.getRenderTypes(state, random, modelData)) {
                        poseStack.pushPose();
                        poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
                        dispatcher.getModelRenderer().tesselateBlock(blocks, model, state, pos, poseStack, recorder(type), true,
                                random, seed, OverlayTexture.NO_OVERLAY, modelData, type);
                        poseStack.popPose();
                    }
                }
            } catch (RuntimeException e) {
                ModpackAssistant.LOGGER.debug("Skipping {} at {} in the showoff view", state, pos, e);
            }
        }
    }

    private VertexRecorder recorder(RenderType type) {
        return layers.computeIfAbsent(type, ignored -> new VertexRecorder());
    }

    private BlockState displayed(BlockState state, @Nullable CompoundTag nbt, HolderLookup<Block> blockLookup) {
        if (state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.STRUCTURE_BLOCK)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (!state.is(Blocks.JIGSAW) || nbt == null) {
            return state;
        }
        String finalState = nbt.contains("final_state", Tag.TAG_STRING) ? nbt.getString("final_state") : "minecraft:air";
        try {
            BlockState replacement = BlockStateParser.parseForBlock(blockLookup, finalState, true).blockState();
            return replacement.is(Blocks.STRUCTURE_VOID) ? Blocks.AIR.defaultBlockState() : replacement;
        } catch (CommandSyntaxException e) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    private @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
        try {
            BlockEntity blockEntity = null;
            if (nbt != null && nbt.contains("id")) {
                blockEntity = BlockEntity.loadStatic(pos, state, nbt, level.registryAccess());
            }
            if (blockEntity == null && state.getBlock() instanceof EntityBlock entityBlock) {
                blockEntity = entityBlock.newBlockEntity(pos, state);
            }
            if (blockEntity != null) {
                blockEntity.setLevel(level);
            }
            return blockEntity;
        } catch (RuntimeException e) {
            ModpackAssistant.LOGGER.debug("Skipping the block entity of {} at {} in the showoff view", state, pos, e);
            return null;
        }
    }

    private void addTemplateEntity(CompoundTag nbt, Vec3 pos) {
        CompoundTag tag = nbt.copy();
        ListTag posTag = new ListTag();
        posTag.add(DoubleTag.valueOf(pos.x));
        posTag.add(DoubleTag.valueOf(pos.y));
        posTag.add(DoubleTag.valueOf(pos.z));
        tag.put("Pos", posTag);
        tag.remove("UUID");
        try {
            EntityType.create(tag, level).ifPresent(entity -> {
                entity.moveTo(pos.x, pos.y, pos.z, entity.getYRot(), entity.getXRot());
                entity.setYBodyRot(entity.getYRot());
                entity.setYHeadRot(entity.getYRot());
                addEntity(entity);
            });
        } catch (RuntimeException e) {
            ModpackAssistant.LOGGER.debug("Skipping a template entity in the showoff view", e);
        }
    }

    private void positionPassengers(Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            vehicle.positionRider(passenger);
            positionPassengers(passenger);
        }
    }

    private void addEntity(Entity entity) {
        try {
            if (entity instanceof Display display) {
                display.tick();
            }
            entity.setCustomNameVisible(false);
            entities.add(entity);
            AABB box = entity.getBoundingBox();
            if (box.getSize() < MIN_ENTITY_SIZE) {
                box = box.inflate(MIN_ENTITY_SIZE);
            }
            entityBounds = entityBounds == null ? box : entityBounds.minmax(box);
        } catch (RuntimeException e) {
            ModpackAssistant.LOGGER.debug("Skipping {} in the showoff view", entity.getType(), e);
        }
    }
}
