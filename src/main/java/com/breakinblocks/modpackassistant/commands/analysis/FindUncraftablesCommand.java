package com.breakinblocks.modpackassistant.commands.analysis;

import com.breakinblocks.modpackassistant.analysis.ObtainabilityIndex;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.jobs.Run;
import com.breakinblocks.modpackassistant.jobs.RunScheduler;
import com.breakinblocks.modpackassistant.report.ReportWriter;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

public final class FindUncraftablesCommand {
    private FindUncraftablesCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("findUncraftables")
                .requires(MAPermissions.GAMEMASTER)
                .executes(context -> find(context.getSource(), null))
                .then(Commands.argument("namespace", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                BuiltInRegistries.ITEM.keySet().stream().map(Identifier::getNamespace).distinct().sorted(), builder))
                        .executes(context -> find(context.getSource(), StringArgumentType.getString(context, "namespace"))));
    }

    private static int find(CommandSourceStack source, @Nullable String namespace) {
        ObtainabilityIndex index = new ObtainabilityIndex(source.getLevel());
        ReportWriter.Context context = new ReportWriter.Context(source, "/mpa findUncraftables" + (namespace == null ? "" : " " + namespace))
                .note("namespace_filter", namespace == null ? "all" : namespace);

        Run run = new Run(source, "uncraftable item report", source.getLevel().dimension());
        run.repeat(() -> index.step(namespace, 128));
        run.onComplete(finished -> {
            finished.message(Messages.UNCRAFT_DONE.get(index.uncraftableCount(), index.modCount(), index.creativeOnlyCount()));
            ReportWriter.deliver(finished, ReportWriter.Family.REGISTRY, "uncraftables", "log", index.log(context));
            ReportWriter.deliver(finished, ReportWriter.Family.REGISTRY, "uncraftables", "csv", index.csv(context));
        });

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.UNCRAFT_START.get(index.lootTableCount()));
        return run.total();
    }
}
