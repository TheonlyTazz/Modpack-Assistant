package com.breakinblocks.modpackassistant.client.showoff;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

final class StructureBlockGetter implements BlockAndTintGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int FULL_LIGHT = 15;

    private final Long2ObjectMap<BlockState> states = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<BlockEntity> blockEntities = new Long2ObjectOpenHashMap<>();
    private final LevelLightEngine lightEngine;
    private final @Nullable Holder<Biome> biome;
    private final int height;

    StructureBlockGetter(LevelLightEngine lightEngine, @Nullable Holder<Biome> biome, int height) {
        this.lightEngine = lightEngine;
        this.biome = biome;
        this.height = height;
    }

    void put(BlockPos pos, BlockState state) {
        states.put(pos.asLong(), state);
    }

    void putBlockEntity(BlockPos pos, BlockEntity blockEntity) {
        blockEntities.put(pos.asLong(), blockEntity);
    }

    Iterable<Long2ObjectMap.Entry<BlockState>> blocks() {
        return states.long2ObjectEntrySet();
    }

    Iterable<BlockEntity> blockEntities() {
        return blockEntities.values();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1.0F;
        }
        return switch (direction) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver color) {
        return biome == null ? -1 : color.getColor(biome.value(), pos.getX(), pos.getZ());
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return lightEngine;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return FULL_LIGHT;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {
        return FULL_LIGHT;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return blockEntities.get(pos.asLong());
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = states.get(pos.asLong());
        return state == null ? AIR : state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return -1;
    }
}
