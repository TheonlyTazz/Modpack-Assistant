package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.resources.model.ModelManager;
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
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class SceneBuilder {
    private static final double MIN_ENTITY_SIZE = 0.5;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final ClientLevel level;
    private final Map<ChunkSectionLayer, VertexRecorder> layers = new EnumMap<>(ChunkSectionLayer.class);
    private final List<ShowoffScene.PlacedBlockEntity> blockEntities = new ArrayList<>();
    private final List<EntityRenderState> entities = new ArrayList<>();
    private @Nullable AABB entityBounds;
    private @Nullable BoundingBox blockBounds;

    private SceneBuilder(ClientLevel level) {
        this.level = level;
    }

    static ShowoffScene structure(CompoundTag data, ClientLevel level) {
        return new SceneBuilder(level).buildStructure(data);
    }

    static @Nullable ShowoffScene entity(Identifier typeId, CompoundTag nbt, ClientLevel level) {
        if (typeId.equals(Identifier.withDefaultNamespace("mannequin"))) {
            return player();
        }
        return new SceneBuilder(level).buildEntity(typeId, nbt);
    }

    private static ShowoffScene player() {
        // Covers every limb rotation; a standing-pose silhouette would clip extended limbs.
        return new ShowoffScene(new EnumMap<>(ChunkSectionLayer.class), List.of(), List.of(),
                new AABB(-1.2, -0.2, -1.2, 1.2, 2.4, 1.2), null, new PlayerShowoff());
    }

    private ShowoffScene buildStructure(CompoundTag data) {
        ListTag size = data.getListOrEmpty("size");
        int sizeX = size.getIntOr(0, 1);
        int sizeY = size.getIntOr(1, 1);
        int sizeZ = size.getIntOr(2, 1);

        HolderLookup<Block> blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        ListTag paletteTag = data.getList("palettes").map(palettes -> palettes.getListOrEmpty(0)).orElseGet(() -> data.getListOrEmpty("palette"));
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blockLookup, paletteTag.getCompoundOrEmpty(i)));
        }

        Holder<Biome> biome = level.registryAccess().lookupOrThrow(Registries.BIOME).get(Biomes.PLAINS).orElse(null);
        StructureBlockGetter blocks = new StructureBlockGetter(biome, sizeY + 2);
        data.getListOrEmpty("blocks").compoundStream().forEach(blockTag -> {
            ListTag posTag = blockTag.getListOrEmpty("pos");
            BlockPos pos = new BlockPos(posTag.getIntOr(0, 0), posTag.getIntOr(1, 0), posTag.getIntOr(2, 0));
            int index = blockTag.getIntOr("state", 0);
            if (index < 0 || index >= palette.size()) {
                return;
            }
            CompoundTag nbt = blockTag.getCompound("nbt").orElse(null);
            BlockState state = displayed(palette.get(index), nbt, blockLookup);
            if (state.isAir()) {
                return;
            }
            blocks.put(pos, state);
            blockBounds = blockBounds == null ? new BoundingBox(pos) : blockBounds.encapsulate(pos);
            if (state.hasBlockEntity()) {
                BlockEntity blockEntity = createBlockEntity(pos, state, state == palette.get(index) ? nbt : null);
                if (blockEntity != null) {
                    blocks.putBlockEntity(pos, blockEntity);
                }
            }
        });

        tesselate(blocks);
        for (BlockEntity blockEntity : blocks.blockEntities()) {
            BlockEntityRenderState state = extract(minecraft.getBlockEntityRenderDispatcher(), blockEntity);
            if (state != null) {
                blockEntities.add(new ShowoffScene.PlacedBlockEntity(blockEntity.getBlockPos(), state));
            }
        }

        data.getListOrEmpty("entities").compoundStream().forEach(entityTag -> {
            ListTag posTag = entityTag.getListOrEmpty("pos");
            Vec3 pos = new Vec3(posTag.getDoubleOr(0, 0.0), posTag.getDoubleOr(1, 0.0), posTag.getDoubleOr(2, 0.0));
            entityTag.getCompound("nbt").ifPresent(nbt -> addTemplateEntity(nbt, pos));
        });

        AABB exact = blockBounds == null ? null : AABB.of(blockBounds);
        AABB bounds = exact != null ? exact : entityBounds != null ? entityBounds : new AABB(0.0, 0.0, 0.0, sizeX, sizeY, sizeZ);
        if (exact != null && entityBounds != null) {
            bounds = bounds.minmax(entityBounds);
        }
        return new ShowoffScene(layers, blockEntities, entities, bounds, exact);
    }

    private @Nullable ShowoffScene buildEntity(Identifier typeId, CompoundTag nbt) {
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId);
        if (type.isEmpty()) {
            return null;
        }
        Entity root = EntityType.loadEntityRecursive(type.get(), nbt, level, EntitySpawnReason.COMMAND, entity -> entity);
        if (root == null) {
            return null;
        }
        root.snapTo(0.0, 0.0, 0.0, 0.0F, 0.0F);
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
        Options options = minecraft.options;
        ModelManager models = minecraft.getModelManager();
        boolean cutoutLeaves = options.cutoutLeaves().get();
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(options.ambientOcclusion().get(), true, minecraft.getBlockColors());
        FluidRenderer fluidRenderer = new FluidRenderer(models.getFluidStateModelSet());
        BlockQuadOutput output = (x, y, z, quad, instance) -> recorder(quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance);
        BlockQuadOutput opaqueOutput = (x, y, z, quad, instance) -> recorder(ChunkSectionLayer.SOLID).putBlockBakedQuad(x, y, z, quad, instance);

        for (Long2ObjectMap.Entry<BlockState> entry : blocks.blocks()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            BlockState state = entry.getValue();
            try {
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    float originX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getX()));
                    float originY = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getY()));
                    float originZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getZ()));
                    FluidRenderer.Output fluidOutput = layer -> {
                        VertexRecorder recorder = recorder(layer);
                        recorder.offset(originX, originY, originZ);
                        return recorder;
                    };
                    var custom = models.getFluidStateModelSet().get(fluid).customRenderer();
                    if (custom == null || !custom.renderFluid(fluidRenderer, fluid, blocks, pos, fluidOutput, state)) {
                        fluidRenderer.tesselate(blocks, pos, fluidOutput, state, fluid);
                    }
                    layers.values().forEach(recorder -> recorder.offset(0.0F, 0.0F, 0.0F));
                }
                if (state.getRenderShape() == RenderShape.MODEL) {
                    blockRenderer.tesselateBlock(ModelBlockRenderer.forceOpaque(cutoutLeaves, state) ? opaqueOutput : output,
                            pos.getX(), pos.getY(), pos.getZ(), blocks, pos, state, models.getBlockStateModelSet().get(state), state.getSeed(pos));
                }
            } catch (RuntimeException e) {
                ModpackAssistant.LOGGER.debug("Skipping {} at {} in the showoff view", state, pos, e);
                layers.values().forEach(recorder -> recorder.offset(0.0F, 0.0F, 0.0F));
            }
        }
    }

    private VertexRecorder recorder(ChunkSectionLayer layer) {
        return layers.computeIfAbsent(layer, ignored -> new VertexRecorder());
    }

    private BlockState displayed(BlockState state, @Nullable CompoundTag nbt, HolderLookup<Block> blockLookup) {
        if (state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.STRUCTURE_BLOCK)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (!state.is(Blocks.JIGSAW) || nbt == null) {
            return state;
        }
        try {
            BlockState replacement = BlockStateParser.parseForBlock(blockLookup, nbt.getStringOr("final_state", "minecraft:air"), true).blockState();
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

    private static <E extends BlockEntity, S extends BlockEntityRenderState> @Nullable S extract(BlockEntityRenderDispatcher dispatcher, E blockEntity) {
        BlockEntityRenderer<E, S> renderer = dispatcher.getRenderer(blockEntity);
        if (renderer == null) {
            return null;
        }
        try {
            S state = renderer.createRenderState();
            renderer.extractRenderState(blockEntity, state, 1.0F, Vec3.ZERO, null);
            state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
            return state;
        } catch (RuntimeException e) {
            ModpackAssistant.LOGGER.debug("Skipping the block entity renderer of {} in the showoff view", blockEntity.getType(), e);
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
            EntityType.create(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level, EntitySpawnReason.STRUCTURE)
                    .ifPresent(entity -> {
                        entity.snapTo(pos.x, pos.y, pos.z, entity.getYRot(), entity.getXRot());
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
            EntityRenderState state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 1.0F);
            state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
            state.outlineColor = EntityRenderState.NO_OUTLINE;
            state.shadowPieces.clear();
            state.leashStates = null;
            state.nameTag = null;
            state.scoreText = null;
            entities.add(state);
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
