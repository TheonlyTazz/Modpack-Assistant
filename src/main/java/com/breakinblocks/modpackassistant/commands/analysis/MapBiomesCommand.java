package com.breakinblocks.modpackassistant.commands.analysis;

import com.breakinblocks.modpackassistant.analysis.BiomeSampler;
import com.breakinblocks.modpackassistant.commands.CommandResults;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.config.MAConfig;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.text.DecimalFormat;
import java.util.List;
import java.util.OptionalInt;

public final class MapBiomesCommand {
    public static final int MIN_RADIUS = 16;
    public static final int MIN_INTERVAL = 4;
    private static final int DEFAULT_INTERVAL = 16;
    private static final int CHAT_LINES = 10;
    private static final DecimalFormat PERCENT = new DecimalFormat("0.00");

    private MapBiomesCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("mapBiomes")
                .requires(MAPermissions.GAMEMASTER)
                .then(Commands.argument("radius", IntegerArgumentType.integer(MIN_RADIUS))
                        .executes(context -> map(context, DEFAULT_INTERVAL, OptionalInt.empty()))
                        .then(Commands.argument("interval", IntegerArgumentType.integer(MIN_INTERVAL))
                                .executes(context -> map(context, IntegerArgumentType.getInteger(context, "interval"), OptionalInt.empty()))
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .executes(context -> map(context, IntegerArgumentType.getInteger(context, "interval"), OptionalInt.of(IntegerArgumentType.getInteger(context, "y")))))));
    }

    private static int map(CommandContext<CommandSourceStack> context, int interval, OptionalInt yArg) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = CommandResults.player(source);
        ServerLevel level = source.getLevel();
        int radius = IntegerArgumentType.getInteger(context, "radius");
        if (radius > MAConfig.maxBiomeSampleRadius()) {
            return CommandResults.fail(source, Messages.TOO_MANY_ITERATIONS.get(radius, MAConfig.maxBiomeSampleRadius(), "max_biome_sample_radius"));
        }
        int y = yArg.orElse(player.blockPosition().getY());
        BiomeSampler sampler = new BiomeSampler(level, player.blockPosition(), radius, interval, y);
        long expected = sampler.expectedSamples();
        int budget = MAConfig.maxBiomeSamples();
        if (expected > budget) {
            int suggested = (int) Math.ceil(2.0D * radius / (Math.sqrt(budget) - 1));
            return CommandResults.fail(source, Messages.BIOMES_TOO_MANY.get(expected, budget, Math.max(MIN_INTERVAL, suggested)));
        }

        ReportWriter.Context reportContext = new ReportWriter.Context(source, "/mpa mapBiomes " + radius + " " + interval + " " + y)
                .note("center", player.blockPosition().toShortString())
                .note("radius", radius)
                .note("interval", interval)
                .note("sample_y", y)
                .note("expected_samples", expected);

        Run run = new Run(source, "biome map", level.dimension());
        for (int z : sampler.rows()) {
            run.job(() -> sampler.sampleRow(z));
        }
        run.onComplete(finished -> {
            finished.message(Messages.BIOMES_HEADER.get(radius, sampler.samples(), sampler.distinctBiomes()).withStyle(ChatFormatting.GREEN));
            List<BiomeSampler.Entry> ranked = sampler.ranked();
            for (int i = 0; i < Math.min(CHAT_LINES, ranked.size()); i++) {
                BiomeSampler.Entry entry = ranked.get(i);
                finished.message(Messages.PERCENT_LINE.get(PERCENT.format(entry.percent()), entry.biome()));
            }
            ReportWriter.deliver(finished, ReportWriter.Family.BIOMES, "biomes", "csv", sampler.csv(reportContext));
        });

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.BIOMES_START.get(expected, radius, y, interval));
        return run.total();
    }
}
