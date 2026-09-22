package com.breakinblocks.modpackassistant.commands.analysis;

import com.breakinblocks.modpackassistant.analysis.LootContexts;
import com.breakinblocks.modpackassistant.analysis.LootSimulator;
import com.breakinblocks.modpackassistant.analysis.LootSafety;
import com.breakinblocks.modpackassistant.commands.CommandResults;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.config.MAConfig;
import com.breakinblocks.modpackassistant.jobs.Run;
import com.breakinblocks.modpackassistant.jobs.RunScheduler;
import com.breakinblocks.modpackassistant.report.ReportWriter;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootTable;

import java.text.DecimalFormat;
import java.util.List;

public final class SimulateLootCommand {
    private static final int CHAT_LINES = 10;
    private static final DecimalFormat PERCENT = new DecimalFormat("0.00");

    private SimulateLootCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("simulateLoot")
                .requires(MAPermissions.GAMEMASTER)
                .then(Commands.argument("iterations", IntegerArgumentType.integer(1))
                        .then(Commands.argument("loot_table", IdentifierArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        context.getSource().getServer().reloadableRegistries().lookup()
                                                .lookupOrThrow(Registries.LOOT_TABLE).listElementIds().map(ResourceKey::identifier), builder))
                                .executes(context -> simulate(context, 0.0F))
                                .then(Commands.argument("luck", FloatArgumentType.floatArg())
                                        .executes(context -> simulate(context, FloatArgumentType.getFloat(context, "luck"))))));
    }

    private static int simulate(CommandContext<CommandSourceStack> context, float luck) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = CommandResults.player(source);
        ServerLevel level = source.getLevel();
        int iterations = IntegerArgumentType.getInteger(context, "iterations");
        Identifier tableId = IdentifierArgument.getId(context, "loot_table");

        if (iterations > MAConfig.maxLootIterations()) {
            return CommandResults.fail(source, Messages.TOO_MANY_ITERATIONS.get(iterations, MAConfig.maxLootIterations(), "max_loot_iterations"));
        }
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, tableId);
        if (level.getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).get(key).isEmpty()) {
            return CommandResults.fail(source, Messages.UNKNOWN_LOOT_TABLE.get(tableId));
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        LootContexts.Built built = LootContexts.build(level, player.blockPosition(), player, luck, table.getParamSet());
        if (!built.ok()) {
            return CommandResults.fail(source, Messages.LOOT_MISSING_PARAMS.get(tableId, String.join(", ", built.missing()), String.valueOf(table.getParamSet())));
        }

        LootSimulator simulator = new LootSimulator(table, built.params(), source.registryAccess(), iterations);
        ReportWriter.Context reportContext = new ReportWriter.Context(source, "/mpa simulateLoot " + iterations + " " + tableId + " " + luck)
                .note("loot_table", tableId)
                .note("iterations", iterations)
                .note("luck", luck)
                .note("param_set", String.valueOf(table.getParamSet()))
                .note("unevaluated_rules", "global loot modifiers; unsupported functions, predicates and providers are rejected before rolling")
                .note("assumptions", "independent random source; live world conditions are not advanced between rolls");

        Run run = new Run(source, "loot simulation", level.dimension());
        LootSafety safety = new LootSafety(level, key, luck);
        run.repeat(() -> {
            if (!safety.step()) return false;
            run.repeat(() -> {
                safety.verifyCurrent();
                simulator.rollBatch(safety.rollsPerBatch());
                return simulator.rolled() >= iterations;
            });
            return true;
        });
        run.onComplete(finished -> {
            finished.message(Messages.LOOT_HEADER.get(tableId, simulator.rolled(), PERCENT.format(simulator.emptyPercent())).withStyle(ChatFormatting.GREEN));
            List<LootSimulator.Stat> ranked = simulator.ranked();
            for (int i = 0; i < Math.min(CHAT_LINES, ranked.size()); i++) {
                LootSimulator.Stat stat = ranked.get(i);
                finished.message(Messages.LOOT_LINE.get(PERCENT.format(stat.dropChance(simulator.rolled())), stat.item + stat.components, stat.total));
            }
            ReportWriter.deliver(finished, ReportWriter.Family.LOOT, tableId.toString(), "csv", simulator.csv(reportContext));
        });

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.LOOT_START.get(tableId, iterations, luck));
        return run.total();
    }
}
