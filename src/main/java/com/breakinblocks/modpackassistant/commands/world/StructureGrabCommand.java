package com.breakinblocks.modpackassistant.commands.world;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.commands.CommandResults;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.commands.args.GrabFormatArgument;
import com.breakinblocks.modpackassistant.config.MAConfig;
import com.breakinblocks.modpackassistant.grab.GrabFiles;
import com.breakinblocks.modpackassistant.grab.GrabFormat;
import com.breakinblocks.modpackassistant.grab.GrabSelections;
import com.breakinblocks.modpackassistant.grab.StructureGrab;
import com.breakinblocks.modpackassistant.jobs.ChunkAccessor;
import com.breakinblocks.modpackassistant.jobs.RegionGeometry;
import com.breakinblocks.modpackassistant.jobs.Run;
import com.breakinblocks.modpackassistant.jobs.RunScheduler;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class StructureGrabCommand {
    private StructureGrabCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("structureGrab")
                .requires(MAPermissions.GAMEMASTER)
                .then(Commands.literal("pos1")
                        .executes(context -> corner(context, true, here(context)))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> corner(context, true, BlockPosArgument.getBlockPos(context, "pos")))))
                .then(Commands.literal("pos2")
                        .executes(context -> corner(context, false, here(context)))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> corner(context, false, BlockPosArgument.getBlockPos(context, "pos")))))
                .then(Commands.literal("clear")
                        .executes(StructureGrabCommand::clearSelection))
                .then(withNameAndFormat(Commands.literal("grab"), StructureGrabCommand::grabSelection))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(withNameAndFormat(Commands.argument("to", BlockPosArgument.blockPos()),
                                (context, name, format) -> grab(context.getSource(),
                                        BlockPosArgument.getBlockPos(context, "from"),
                                        BlockPosArgument.getBlockPos(context, "to"),
                                        name, format))));
    }

    private interface Executor {
        int run(CommandContext<CommandSourceStack> context, @Nullable String name, GrabFormat format) throws CommandSyntaxException;
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T withNameAndFormat(T node, Executor executor) {
        return node.executes(context -> executor.run(context, null, defaultFormat()))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> executor.run(context, StringArgumentType.getString(context, "name"), defaultFormat()))
                        .then(Commands.argument("format", GrabFormatArgument.grabFormat())
                                .suggests(GrabFormatArgument::suggest)
                                .executes(context -> executor.run(context, StringArgumentType.getString(context, "name"),
                                        GrabFormatArgument.get(context, "format")))));
    }

    private static GrabFormat defaultFormat() {
        return MAConfig.grabFormat();
    }

    private static BlockPos here(CommandContext<CommandSourceStack> context) {
        return BlockPos.containing(context.getSource().getPosition());
    }

    private static int corner(CommandContext<CommandSourceStack> context, boolean first, BlockPos pos) {
        CommandSourceStack source = context.getSource();
        GrabSelections.Selection selection = first
                ? GrabSelections.setFirst(source, source.getLevel().dimension(), pos)
                : GrabSelections.setSecond(source, source.getLevel().dimension(), pos);
        CommandResults.success(source, (first ? Messages.GRAB_POS1 : Messages.GRAB_POS2).get(pos.getX(), pos.getY(), pos.getZ()));
        if (selection.complete()) {
            BoundingBox box = BoundingBox.fromCorners(selection.first(), selection.second());
            source.sendSuccess(() -> Messages.GRAB_REGION.get(box.getXSpan(), box.getYSpan(), box.getZSpan(), volume(box)), false);
        }
        return 1;
    }

    private static int clearSelection(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!GrabSelections.clear(source)) {
            return CommandResults.fail(source, Messages.GRAB_NO_SELECTION.get());
        }
        return CommandResults.success(source, Messages.GRAB_CLEARED.get());
    }

    private static int grabSelection(CommandContext<CommandSourceStack> context, @Nullable String name, GrabFormat format) {
        CommandSourceStack source = context.getSource();
        GrabSelections.Selection selection = GrabSelections.get(source);
        if (selection == null || !selection.complete()) {
            return CommandResults.fail(source, Messages.GRAB_NO_SELECTION.get());
        }
        if (!selection.dimension().equals(source.getLevel().dimension())) {
            return CommandResults.fail(source, Messages.GRAB_WRONG_DIMENSION.get(selection.dimension().location().toString()));
        }
        return grab(source, selection.first(), selection.second(), name, format);
    }

    private static int grab(CommandSourceStack source, BlockPos from, BlockPos to, @Nullable String name, GrabFormat format) {
        ServerLevel level = source.getLevel();
        int floor = RegionGeometry.minY(level);
        int ceiling = RegionGeometry.maxY(level);
        int lowY = Math.max(Math.min(from.getY(), to.getY()), floor);
        int highY = Math.min(Math.max(from.getY(), to.getY()), ceiling);
        if (lowY > highY) {
            return CommandResults.fail(source, Messages.GRAB_OUT_OF_BOUNDS.get(floor, ceiling));
        }
        boolean clamped = lowY != Math.min(from.getY(), to.getY()) || highY != Math.max(from.getY(), to.getY());

        BoundingBox box = BoundingBox.fromCorners(
                new BlockPos(from.getX(), lowY, from.getZ()),
                new BlockPos(to.getX(), highY, to.getZ()));
        long volume = volume(box);
        int limit = MAConfig.maxGrabBlocks();
        if (volume > limit) {
            return CommandResults.fail(source, Messages.GRAB_TOO_LARGE.get(volume, limit, "max_grab_blocks"));
        }

        boolean entities = MAConfig.grabEntities();
        StructureGrab capture = new StructureGrab(box, entities, MAConfig.grabIgnoreAir());
        List<ChunkPos> chunks = footprint(box);
        String fileName = GrabFiles.sanitize(name == null ? GrabFiles.defaultName() : name);

        Run run = new Run(source, "structure grab", level.dimension());
        for (ChunkPos chunk : chunks) {
            run.job(() -> ChunkAccessor.withChunk(level, chunk, loaded -> {
                capture.captureChunk(level, loaded);
                return true;
            }));
        }
        if (entities) {
            run.job(() -> capture.captureEntities(level));
        }
        run.onComplete(finished -> deliver(finished, capture, fileName, format));

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.GRAB_START.get(box.getXSpan(), box.getYSpan(), box.getZSpan(), volume, chunks.size(), format.getSerializedName()));
        if (clamped) {
            run.message(Messages.GRAB_CLAMPED.get(lowY, highY).withStyle(ChatFormatting.YELLOW));
        }
        return run.total();
    }

    private interface Writer {
        void write(Path path, CompoundTag structure) throws IOException;
    }

    private static void deliver(Run run, StructureGrab capture, String name, GrabFormat format) {
        if (capture.blockCount() == 0) {
            run.message(Messages.GRAB_EMPTY.get().withStyle(ChatFormatting.RED));
            return;
        }
        CompoundTag structure = capture.toStructureTag();
        Path directory = GrabFiles.directory();
        String base;
        try {
            base = GrabFiles.prepare(directory, name, format);
        } catch (IOException e) {
            ModpackAssistant.LOGGER.error("Failed to create the structure grab directory {}", directory, e);
            run.message(Messages.GRAB_FAILED.get(GrabFiles.relative(directory), String.valueOf(e.getMessage())));
            return;
        }

        BoundingBox box = capture.box();
        run.message(Messages.GRAB_DONE.get(box.getXSpan(), box.getYSpan(), box.getZSpan(),
                capture.blockCount(), capture.paletteSize(), capture.blockEntityCount(), capture.entityCount())
                .withStyle(ChatFormatting.GREEN));

        if (format.writesNbt()) {
            write(run, directory.resolve(base + GrabFormat.NBT_EXTENSION), structure, GrabFiles::writeNbt);
        }
        if (format.writesSnbt()) {
            write(run, directory.resolve(base + GrabFormat.SNBT_EXTENSION), structure, GrabFiles::writeSnbt);
        }
    }

    private static void write(Run run, Path path, CompoundTag structure, Writer writer) {
        try {
            writer.write(path, structure);
            run.message(GrabFiles.pathMessage(path));
        } catch (IOException e) {
            ModpackAssistant.LOGGER.error("Failed to write structure grab {}", path, e);
            run.message(Messages.GRAB_FAILED.get(GrabFiles.relative(path), String.valueOf(e.getMessage())));
        }
    }

    private static List<ChunkPos> footprint(BoundingBox box) {
        int firstX = SectionPos.blockToSectionCoord(box.minX());
        int lastX = SectionPos.blockToSectionCoord(box.maxX());
        int firstZ = SectionPos.blockToSectionCoord(box.minZ());
        int lastZ = SectionPos.blockToSectionCoord(box.maxZ());
        List<ChunkPos> chunks = new ArrayList<>((lastX - firstX + 1) * (lastZ - firstZ + 1));
        for (int x = firstX; x <= lastX; x++) {
            for (int z = firstZ; z <= lastZ; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }

    private static long volume(BoundingBox box) {
        return (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
    }
}
