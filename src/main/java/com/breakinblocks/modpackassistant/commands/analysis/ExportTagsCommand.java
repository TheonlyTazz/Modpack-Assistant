package com.breakinblocks.modpackassistant.commands.analysis;

import com.breakinblocks.modpackassistant.analysis.TagExporter;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.commands.args.RegistryKindArgument;
import com.breakinblocks.modpackassistant.commands.args.RegistryKindArgument.RegistryKind;
import com.breakinblocks.modpackassistant.commands.args.ReportFormatArgument;
import com.breakinblocks.modpackassistant.commands.args.ReportFormatArgument.ReportFormat;
import com.breakinblocks.modpackassistant.jobs.Run;
import com.breakinblocks.modpackassistant.jobs.RunScheduler;
import com.breakinblocks.modpackassistant.report.ReportWriter;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;

public final class ExportTagsCommand {
    private static final int BATCH = 500;

    private ExportTagsCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("exportTags")
                .requires(MAPermissions.GAMEMASTER)
                .then(Commands.argument("registry", RegistryKindArgument.registryKind()).suggests(RegistryKindArgument::suggest)
                        .executes(context -> export(context.getSource(), RegistryKindArgument.get(context, "registry"), ReportFormat.JSON))
                        .then(Commands.argument("format", ReportFormatArgument.reportFormat()).suggests(ReportFormatArgument::suggest)
                                .executes(context -> export(context.getSource(), RegistryKindArgument.get(context, "registry"), ReportFormatArgument.get(context, "format")))));
    }

    private static int export(CommandSourceStack source, RegistryKind kind, ReportFormat format) {
        return export(source, kind, format, kind.registry());
    }

    private static <T> int export(CommandSourceStack source, RegistryKind kind, ReportFormat format, Registry<T> registry) {
        TagExporter<T> exporter = new TagExporter<>(registry);
        ReportWriter.Context context = new ReportWriter.Context(source, "/mpa exportTags " + kind.getSerializedName() + " " + format.getSerializedName())
                .note("registry", exporter.registryName())
                .note("object_count", exporter.size());

        Run run = new Run(source, "tag export", source.getLevel().dimension());
        for (int from = 0; from < exporter.size(); from += BATCH) {
            int start = from;
            run.job(() -> exporter.process(start, start + BATCH));
        }
        run.onComplete(finished -> {
            context.note("distinct_tags", exporter.distinctTagCount()).note("untagged", exporter.untagged());
            String content = format == ReportFormat.JSON ? exporter.json(context) : exporter.csv(context);
            finished.message(Messages.TAGS_DONE.get(exporter.size(), kind.getSerializedName(), exporter.distinctTagCount(), exporter.untagged()));
            ReportWriter.deliver(finished, ReportWriter.Family.TAGS, "tags-" + kind.getSerializedName(), format.getSerializedName(), content);
        });

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.TAGS_START.get(exporter.size(), kind.getSerializedName(), format.getSerializedName()));
        return run.total();
    }
}
