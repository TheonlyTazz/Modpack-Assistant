package com.breakinblocks.modpackassistant.commands.analysis;

import com.breakinblocks.modpackassistant.analysis.UnificationAuditor;
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
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.Nullable;

public final class AuditUnificationCommand {
    private AuditUnificationCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("auditUnification")
                .requires(MAPermissions.GAMEMASTER)
                .executes(context -> audit(context.getSource(), null))
                .then(Commands.argument("namespace", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                BuiltInRegistries.ITEM.listTagIds().map(TagKey::location).map(Identifier::getNamespace).distinct(), builder))
                        .executes(context -> audit(context.getSource(), StringArgumentType.getString(context, "namespace"))));
    }

    private static int audit(CommandSourceStack source, @Nullable String namespace) {
        UnificationAuditor auditor = new UnificationAuditor(namespace);
        ReportWriter.Context context = new ReportWriter.Context(source, "/mpa auditUnification" + (namespace == null ? "" : " " + namespace))
                .note("namespace_filter", namespace == null ? "c, forge" : namespace);

        Run run = new Run(source, "unification audit", source.getLevel().dimension());
        run.repeat(auditor.auditJob(BuiltInRegistries.ITEM, 32));
        run.repeat(auditor.auditJob(BuiltInRegistries.BLOCK, 32));
        run.onComplete(finished -> {
            finished.message(Messages.UNIFY_DONE.get(auditor.unresolvedCount(), auditor.emptyCount(), auditor.resolvedCount()));
            ReportWriter.deliver(finished, ReportWriter.Family.TAGS, "unification", "log", auditor.log(context));
            ReportWriter.deliver(finished, ReportWriter.Family.TAGS, "unification", "csv", auditor.csv(context));
        });

        if (!RunScheduler.tryStart(run)) {
            return 0;
        }
        run.message(Messages.UNIFY_START.get(namespace == null ? "" : " in " + namespace));
        return run.total();
    }
}
