package com.breakinblocks.modpackassistant.commands;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.commands.admin.DevEnvCommand;
import com.breakinblocks.modpackassistant.commands.admin.EnchantCommand;
import com.breakinblocks.modpackassistant.commands.admin.FeedCommand;
import com.breakinblocks.modpackassistant.commands.admin.GodCommand;
import com.breakinblocks.modpackassistant.commands.admin.HealCommand;
import com.breakinblocks.modpackassistant.commands.admin.NightVisionCommand;
import com.breakinblocks.modpackassistant.commands.admin.OpswordCommand;
import com.breakinblocks.modpackassistant.commands.admin.RepairCommand;
import com.breakinblocks.modpackassistant.commands.admin.ToggleDownfallCommand;
import com.breakinblocks.modpackassistant.commands.admin.TpdCommand;
import com.breakinblocks.modpackassistant.commands.items.CopyCommand;
import com.breakinblocks.modpackassistant.commands.items.PrintCommand;
import com.breakinblocks.modpackassistant.commands.analysis.AuditUnificationCommand;
import com.breakinblocks.modpackassistant.commands.analysis.ExportTagsCommand;
import com.breakinblocks.modpackassistant.commands.analysis.FindConflictsCommand;
import com.breakinblocks.modpackassistant.commands.analysis.FindUncraftablesCommand;
import com.breakinblocks.modpackassistant.commands.analysis.LocateBlockCommand;
import com.breakinblocks.modpackassistant.commands.analysis.MapBiomesCommand;
import com.breakinblocks.modpackassistant.commands.analysis.ScanOresCommand;
import com.breakinblocks.modpackassistant.commands.analysis.SimulateLootCommand;
import com.breakinblocks.modpackassistant.commands.analysis.SimulateSpawnsCommand;
import com.breakinblocks.modpackassistant.commands.showoff.ShowoffCommand;
import com.breakinblocks.modpackassistant.commands.world.CancelCommand;
import com.breakinblocks.modpackassistant.commands.world.ClearCommand;
import com.breakinblocks.modpackassistant.commands.world.DrainCommand;
import com.breakinblocks.modpackassistant.commands.world.KillCommand;
import com.breakinblocks.modpackassistant.commands.world.MineAreaCommand;
import com.breakinblocks.modpackassistant.commands.world.StructureGrabCommand;
import com.breakinblocks.modpackassistant.commands.world.TestStructureLootCommand;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

@EventBusSubscriber(modid = ModpackAssistant.MOD_ID)
public final class MACommands {
    public static final String ROOT = "modpackassistant";
    public static final String ALIAS = "mpa";

    private static final List<Function<CommandBuildContext, LiteralArgumentBuilder<CommandSourceStack>>> SUBCOMMANDS = List.of(
            context -> DevEnvCommand.build(),
            context -> OpswordCommand.build(),
            EnchantCommand::build,
            context -> RepairCommand.build(),
            context -> HealCommand.build(),
            context -> FeedCommand.build(),
            context -> GodCommand.build(),
            context -> NightVisionCommand.build(),
            context -> TpdCommand.build(),
            context -> PrintCommand.build(),
            context -> PrintCommand.buildHand(),
            context -> CopyCommand.build(),
            context -> ScanOresCommand.build("scanOres"),
            context -> ScanOresCommand.build("oredist"),
            LocateBlockCommand::build,
            ClearCommand::build,
            context -> DrainCommand.build(),
            KillCommand::build,
            context -> MineAreaCommand.build(),
            context -> StructureGrabCommand.build(),
            TestStructureLootCommand::build,
            ShowoffCommand::build,
            context -> SimulateLootCommand.build(),
            SimulateSpawnsCommand::build,
            context -> FindConflictsCommand.build(),
            context -> FindUncraftablesCommand.build(),
            context -> AuditUnificationCommand.build(),
            context -> ExportTagsCommand.build(),
            context -> MapBiomesCommand.build(),
            context -> CancelCommand.build()
    );

    private MACommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        CommandBuildContext context = event.getBuildContext();

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);
        for (var subcommand : SUBCOMMANDS) {
            attach(root, subcommand.apply(context));
        }
        LiteralCommandNode<CommandSourceStack> rootNode = dispatcher.register(root);
        dispatcher.register(Commands.literal(ALIAS).redirect(rootNode));
        dispatcher.register(ToggleDownfallCommand.build());
    }

    private static void attach(LiteralArgumentBuilder<CommandSourceStack> root, LiteralArgumentBuilder<CommandSourceStack> subcommand) {
        LiteralCommandNode<CommandSourceStack> node = subcommand.build();
        root.then(node);
        String lower = node.getLiteral().toLowerCase(Locale.ROOT);
        if (!lower.equals(node.getLiteral())) {
            root.then(Commands.literal(lower).requires(node.getRequirement()).executes(node.getCommand()).redirect(node));
        }
    }
}
