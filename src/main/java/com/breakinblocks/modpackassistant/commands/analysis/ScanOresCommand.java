package com.breakinblocks.modpackassistant.commands.analysis;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.analysis.OreScan;
import com.breakinblocks.modpackassistant.commands.CommandResults;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.config.MAConfig;
import com.breakinblocks.modpackassistant.jobs.ChunkAccessor;
import com.breakinblocks.modpackassistant.jobs.RegionCommands;
import com.breakinblocks.modpackassistant.jobs.RegionGeometry;
import com.breakinblocks.modpackassistant.jobs.Run;
import com.breakinblocks.modpackassistant.jobs.RunScheduler;
import com.breakinblocks.modpackassistant.report.ReportWriter;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

public final class ScanOresCommand {
    private static final DecimalFormat PERCENT = new DecimalFormat("0.00");
    private static final DecimalFormat GROUPED = new DecimalFormat("#,###");
    private static final int ALT_WHITE = 0xC4C4C3;
    private static final int ALT_YELLOW = 0xD5D5A0;

    private ScanOresCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        return Commands.literal(literal)
                .requires(MAPermissions.GAMEMASTER)
                .then(Commands.argument("chunk_radius", IntegerArgumentType.integer(0))
                        .executes(context -> scan(context, OptionalInt.empty(), OptionalInt.empty()))
                        .then(Commands.argument("min_y", IntegerArgumentType.integer())
                                .executes(context -> scan(context, OptionalInt.of(IntegerArgumentType.getInteger(context, "min_y")), OptionalInt.empty()))
                                .then(Commands.argument("max_y", IntegerArgumentType.integer())
                                        .executes(context -> scan(context, OptionalInt.of(IntegerArgumentType.getInteger(context, "min_y")), OptionalInt.of(IntegerArgumentType.getInteger(context, "max_y")))))));
    }

    private static int scan(CommandContext<CommandSourceStack> context, OptionalInt minArg, OptionalInt maxArg) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = CommandResults.player(source);
        ServerLevel level = source.getLevel();
        int radius = IntegerArgumentType.getInteger(context, "chunk_radius");
        RegionGeometry region = RegionCommands.region(source, player, radius, MAConfig.maxScanRadius(), "max_scan_radius");
        if (region == null) {
            return 0;
        }

        int levelMin = RegionGeometry.minY(level);
        int levelMax = RegionGeometry.maxY(level);
        int minY = minArg.orElse(levelMin);
        int maxY = maxArg.orElse(levelMax);
        if (minY > maxY) {
            int swap = minY;
            minY = maxY;
            maxY = swap;
        }
        boolean clamped = minY < levelMin || maxY > levelMax;
        minY = Math.max(minY, levelMin);
        maxY = Math.min(maxY, levelMax);

        OreScan scan = new OreScan(minY, maxY);
        ReportWriter.Context reportContext = new ReportWriter.Context(source, "/mpa scanOres " + radius + " " + minY + " " + maxY)
                .note("chunk_span", region.span() + "x" + region.span())
                .note("height_band", minY + " to " + maxY)
                .note("clamped_to_dimension", clamped);

        Run run = new Run(source, "ore scan", level.dimension());
        for (ChunkPos chunk : region.chunks()) {
            run.job(() -> ChunkAccessor.withLoadedChunk(level, chunk, loaded -> scan.scanChunk(loaded, chunk)));
        }
        int finalMin = minY;
        int finalMax = maxY;
        run.onComplete(finished -> {
            int skipped = region.chunkCount() - scan.chunksScanned();
            reportContext.note("chunks_scanned", scan.chunksScanned()).note("chunks_skipped_unloaded", skipped);
            if (skipped > 0) finished.message(Messages.SCAN_SKIPPED.get(skipped));
            printChat(finished, scan, region, finalMin, finalMax);
            writeReports(finished, scan, reportContext);
        });

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.SCAN_START.get(region.spanText(), region.chunkCount(), minY, maxY));
        run.message(Messages.SCAN_LOADED_ONLY.get());
        return run.total();
    }

    static void printChat(Run run, OreScan scan, RegionGeometry region, int minY, int maxY) {
        if (scan.total() == 0) {
            run.message(Messages.SCAN_NONE.get().withStyle(ChatFormatting.RED));
            return;
        }
        run.message(Messages.SCAN_HEADER.get(region.spanText(), minY, maxY, GROUPED.format(scan.total())).withStyle(ChatFormatting.GREEN));
        run.message(Component.empty());
        List<OreScan.Entry> ranked = scan.ranked();
        for (int i = 0; i < ranked.size(); i++) {
            run.message(line(ranked.get(i), i));
        }
    }

    static MutableComponent line(OreScan.Entry entry, int index) {
        int white = index % 2 == 0 ? Objects.requireNonNull(ChatFormatting.WHITE.getColor()) : ALT_WHITE;
        int yellow = index % 2 == 0 ? Objects.requireNonNull(ChatFormatting.YELLOW.getColor()) : ALT_YELLOW;
        Style bracket = Style.EMPTY.withColor(yellow);
        Style count = bracket.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Messages.SCAN_PERCENT.get(PERCENT.format(entry.percent()))));
        return Component.empty()
                .append(Component.literal("[").withStyle(bracket))
                .append(Component.literal(GROUPED.format(entry.count())).withStyle(count))
                .append(Component.literal("]").withStyle(bracket))
                .append(Component.literal(" " + entry.block()).withStyle(Style.EMPTY.withColor(white)));
    }

    private static void writeReports(Run run, OreScan scan, ReportWriter.Context context) {
        Path summary = ReportWriter.file(ReportWriter.Family.ORES, "ores", "csv");
        Path heights = ReportWriter.file(ReportWriter.Family.ORES, "ores-by-height", "csv");
        try {
            ReportWriter.writeString(summary, scan.summaryCsv(context));
            ReportWriter.writeString(heights, scan.perHeightCsv(context));
            run.message(ReportWriter.pathMessage(summary));
            run.message(ReportWriter.pathMessage(heights));
        } catch (IOException e) {
            ModpackAssistant.LOGGER.error("Failed to write ore scan report", e);
            run.message(ReportWriter.failureMessage(summary, e));
        }
    }
}
