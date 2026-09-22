package com.breakinblocks.modpackassistant.grab;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class StructureGrab {
    private static final int FULL_BLOCK = 0;
    private static final int OTHER_BLOCK = 1;
    private static final int BLOCK_ENTITY = 2;

    private static final Comparator<Entry> VANILLA_ORDER = Comparator.comparingInt(Entry::order)
            .thenComparingInt(Entry::y)
            .thenComparingInt(Entry::x)
            .thenComparingInt(Entry::z);

    private record Entry(int x, int y, int z, BlockState state, @Nullable CompoundTag nbt, int order) {
    }

    private final BoundingBox box;
    private final boolean includeEntities;
    private final boolean ignoreAir;
    private final List<Entry> blocks = new ArrayList<>();
    private final List<CompoundTag> entities = new ArrayList<>();
    private final Set<BlockState> distinctStates = new ObjectOpenHashSet<>();
    private int blockEntities;

    public StructureGrab(BoundingBox box, boolean includeEntities, boolean ignoreAir) {
        this.box = box;
        this.includeEntities = includeEntities;
        this.ignoreAir = ignoreAir;
    }

    public BoundingBox box() {
        return box;
    }

    public int blockCount() {
        return blocks.size();
    }

    public int blockEntityCount() {
        return blockEntities;
    }

    public int entityCount() {
        return entities.size();
    }

    public int paletteSize() {
        return distinctStates.size();
    }

    public void captureChunk(ServerLevel level, LevelChunk chunk) {
        int minX = Math.max(box.minX(), chunk.getPos().getMinBlockX());
        int maxX = Math.min(box.maxX(), chunk.getPos().getMaxBlockX());
        int minZ = Math.max(box.minZ(), chunk.getPos().getMinBlockZ());
        int maxZ = Math.min(box.maxZ(), chunk.getPos().getMaxBlockZ());
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (skipped(state)) {
                        continue;
                    }
                    CompoundTag nbt = blockEntityTag(level, chunk, pos);
                    if (nbt != null) {
                        blockEntities++;
                    }
                    distinctStates.add(state);
                    blocks.add(new Entry(x - box.minX(), y - box.minY(), z - box.minZ(), state, nbt, order(state, nbt)));
                }
            }
        }
    }

    public void captureEntities(ServerLevel level) {
        if (!includeEntities) {
            return;
        }
        BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
        BlockPos max = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
        AABB bounds = AABB.encapsulatingFullBlocks(min, max);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, bounds, found -> !(found instanceof Player))) {
            CompoundTag saved = new CompoundTag();
            entity.save(saved);
            Vec3 relative = new Vec3(entity.getX() - min.getX(), entity.getY() - min.getY(), entity.getZ() - min.getZ());
            BlockPos blockPos = entity instanceof Painting painting ? painting.getPos().subtract(min) : BlockPos.containing(relative);
            CompoundTag tag = new CompoundTag();
            tag.put("pos", doubleList(relative.x, relative.y, relative.z));
            tag.put("blockPos", intList(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
            tag.put("nbt", saved.copy());
            entities.add(tag);
        }
    }

    public CompoundTag toStructureTag() {
        blocks.sort(VANILLA_ORDER);
        List<BlockState> palette = new ArrayList<>();
        Object2IntMap<BlockState> ids = new Object2IntOpenHashMap<>();
        ids.defaultReturnValue(-1);

        ListTag blockList = new ListTag();
        for (Entry entry : blocks) {
            int id = ids.getInt(entry.state());
            if (id < 0) {
                id = palette.size();
                palette.add(entry.state());
                ids.put(entry.state(), id);
            }
            CompoundTag blockTag = new CompoundTag();
            blockTag.put("pos", intList(entry.x(), entry.y(), entry.z()));
            blockTag.putInt("state", id);
            if (entry.nbt() != null) {
                blockTag.put("nbt", entry.nbt());
            }
            blockList.add(blockTag);
        }

        ListTag paletteList = new ListTag();
        for (BlockState state : palette) {
            paletteList.add(NbtUtils.writeBlockState(state));
        }

        ListTag entityList = new ListTag();
        entities.forEach(entityList::add);

        CompoundTag tag = new CompoundTag();
        tag.put("palette", paletteList);
        tag.put("blocks", blockList);
        tag.put("entities", entityList);
        tag.put("size", intList(box.getXSpan(), box.getYSpan(), box.getZSpan()));
        return NbtUtils.addCurrentDataVersion(tag);
    }

    private boolean skipped(BlockState state) {
        if (state.is(Blocks.STRUCTURE_VOID)) {
            return true;
        }
        return ignoreAir && state.isAir();
    }

    @Nullable
    private static CompoundTag blockEntityTag(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        BlockEntity blockEntity = chunk.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithId(level.registryAccess());
    }

    private static int order(BlockState state, @Nullable CompoundTag nbt) {
        if (nbt != null) {
            return BLOCK_ENTITY;
        }
        boolean full = !state.getBlock().hasDynamicShape()
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return full ? FULL_BLOCK : OTHER_BLOCK;
    }

    private static ListTag intList(int... values) {
        ListTag list = new ListTag();
        for (int value : values) {
            list.add(IntTag.valueOf(value));
        }
        return list;
    }

    private static ListTag doubleList(double... values) {
        ListTag list = new ListTag();
        for (double value : values) {
            list.add(DoubleTag.valueOf(value));
        }
        return list;
    }
}
