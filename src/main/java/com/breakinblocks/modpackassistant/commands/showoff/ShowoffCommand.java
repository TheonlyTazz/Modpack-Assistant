package com.breakinblocks.modpackassistant.commands.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.commands.CommandResults;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.config.MAConfig;
import com.breakinblocks.modpackassistant.grab.GrabFiles;
import com.breakinblocks.modpackassistant.net.MANetworking;
import com.breakinblocks.modpackassistant.net.ShowoffBackgroundPayload;
import com.breakinblocks.modpackassistant.net.ShowoffClosePayload;
import com.breakinblocks.modpackassistant.net.ShowoffOpenPayload;
import com.breakinblocks.modpackassistant.net.ShowoffScreenshotPayload;
import com.breakinblocks.modpackassistant.net.ShowoffViewPayload;
import com.breakinblocks.modpackassistant.showoff.ShowoffBackground;
import com.breakinblocks.modpackassistant.showoff.ShowoffFiles;
import com.breakinblocks.modpackassistant.showoff.ShowoffSubject;
import com.breakinblocks.modpackassistant.showoff.ShowoffTemplateFiles;
import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ColorArgument;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ShowoffCommand {
    private ShowoffCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build(CommandBuildContext buildContext) {
        return Commands.literal("showoff")
                .requires(MAPermissions.GAMEMASTER)
                .then(Commands.literal("structure")
                        .then(Commands.argument("template", ResourceLocationArgument.id())
                                .suggests(ShowoffCommand::suggestTemplates)
                                .executes(context -> structure(context, ResourceLocationArgument.getId(context, "template")))))
                .then(Commands.literal("file")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(ShowoffCommand::suggestFiles)
                                .executes(context -> file(context, StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("entity")
                        .then(Commands.argument("entity", ResourceArgument.resource(buildContext, Registries.ENTITY_TYPE))
                                .suggests(SuggestionProviders.SUMMONABLE_ENTITIES)
                                .executes(context -> entity(context, new CompoundTag()))
                                .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                                        .executes(context -> entity(context, CompoundTagArgument.getCompoundTag(context, "nbt"))))))
                .then(Commands.literal("angle")
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                                .then(Commands.argument("pitch", FloatArgumentType.floatArg(ShowoffView.MIN_PITCH, ShowoffView.MAX_PITCH))
                                        .executes(ShowoffCommand::angle))))
                .then(Commands.literal("zoom")
                        .then(Commands.argument("zoom", FloatArgumentType.floatArg(ShowoffView.MIN_ZOOM, ShowoffView.MAX_ZOOM))
                                .executes(ShowoffCommand::zoom)))
                .then(Commands.literal("pan")
                        .then(Commands.argument("x", FloatArgumentType.floatArg(-ShowoffView.MAX_PAN, ShowoffView.MAX_PAN))
                                .then(Commands.argument("y", FloatArgumentType.floatArg(-ShowoffView.MAX_PAN, ShowoffView.MAX_PAN))
                                        .executes(ShowoffCommand::pan))))
                .then(Commands.literal("reset")
                        .executes(ShowoffCommand::reset))
                .then(Commands.literal("background")
                        .then(Commands.literal("transparent")
                                .executes(context -> background(context, ShowoffBackground.TRANSPARENT)))
                        .then(Commands.literal("hex")
                                .then(Commands.argument("rgb", StringArgumentType.word())
                                        .executes(context -> hexBackground(context, StringArgumentType.getString(context, "rgb")))))
                        .then(Commands.argument("color", ColorArgument.color())
                                .executes(context -> background(context, ShowoffBackground.of(ColorArgument.getColor(context, "color"))))))
                .then(Commands.literal("screenshot")
                        .executes(context -> screenshot(context, "", 0, 0))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> screenshot(context, StringArgumentType.getString(context, "name"), 0, 0))
                                .then(Commands.argument("width", IntegerArgumentType.integer(ShowoffFiles.MIN_CAPTURE_SIZE, ShowoffFiles.MAX_CAPTURE_SIZE))
                                        .then(Commands.argument("height", IntegerArgumentType.integer(ShowoffFiles.MIN_CAPTURE_SIZE, ShowoffFiles.MAX_CAPTURE_SIZE))
                                                .executes(context -> screenshot(context, StringArgumentType.getString(context, "name"),
                                                        IntegerArgumentType.getInteger(context, "width"),
                                                        IntegerArgumentType.getInteger(context, "height")))))))
                .then(Commands.literal("close")
                        .executes(ShowoffCommand::close));
    }

    private static CompletableFuture<Suggestions> suggestTemplates(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(context.getSource().getServer().getStructureManager().listTemplates(), builder);
    }

    private static CompletableFuture<Suggestions> suggestFiles(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(ShowoffTemplateFiles.list().stream().map(StringArgumentType::escapeIfRequired), builder);
    }

    private static int structure(CommandContext<CommandSourceStack> context, ResourceLocation id) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = CommandResults.player(source);
        if (!MANetworking.canShowoff(player)) {
            return CommandResults.fail(source, Messages.SHOWOFF_NO_CLIENT.get(player.getName()));
        }
        Optional<StructureTemplate> template = source.getServer().getStructureManager().get(id);
        if (template.isEmpty()) {
            return CommandResults.fail(source, Messages.SHOWOFF_UNKNOWN_TEMPLATE.get(id.toString()));
        }
        return sendTemplate(source, player, ShowoffSubject.STRUCTURE, id, id.toString(), template.get().save(new CompoundTag()),
                Messages.SHOWOFF_OPEN_STRUCTURE);
    }

    private static int file(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = CommandResults.player(source);
        if (!MANetworking.canShowoff(player)) {
            return CommandResults.fail(source, Messages.SHOWOFF_NO_CLIENT.get(player.getName()));
        }
        Path file = ShowoffTemplateFiles.resolve(name);
        if (file == null) {
            return CommandResults.fail(source, Messages.SHOWOFF_UNKNOWN_FILE.get(name, GrabFiles.relative(ShowoffTemplateFiles.directory())));
        }
        String label = ShowoffTemplateFiles.relative(file);
        CompoundTag data;
        try {
            data = ShowoffTemplateFiles.read(file, source.getServer());
        } catch (IOException | CommandSyntaxException | RuntimeException e) {
            ModpackAssistant.LOGGER.warn("Could not read structure file {} for the showoff view", file, e);
            return CommandResults.fail(source, Messages.SHOWOFF_BAD_FILE.get(label, String.valueOf(e.getMessage())));
        }
        return sendTemplate(source, player, ShowoffSubject.FILE, ShowoffTemplateFiles.id(file), label, data, Messages.SHOWOFF_OPEN_FILE);
    }

    private static int sendTemplate(CommandSourceStack source, ServerPlayer player, ShowoffSubject subject, ResourceLocation id, String label,
                                    CompoundTag data, Messages.Msg opened) {
        int blocks = data.getList("blocks", Tag.TAG_COMPOUND).size();
        int entities = data.getList("entities", Tag.TAG_COMPOUND).size();
        if (blocks == 0 && entities == 0) {
            return CommandResults.fail(source, Messages.SHOWOFF_EMPTY_TEMPLATE.get(label));
        }
        int limit = MAConfig.maxShowoffBlocks();
        if (blocks > limit) {
            return CommandResults.fail(source, Messages.SHOWOFF_TOO_LARGE.get(label, blocks, limit, "max_showoff_blocks"));
        }

        ListTag size = data.getList("size", Tag.TAG_INT);
        MANetworking.sendShowoff(player, new ShowoffOpenPayload(subject, id, data));
        return CommandResults.success(source, opened.get(label,
                size.getInt(0), size.getInt(1), size.getInt(2), blocks, entities), blocks);
    }

    private static int entity(CommandContext<CommandSourceStack> context, CompoundTag nbt) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = CommandResults.player(source);
        Holder.Reference<EntityType<?>> type = ResourceArgument.getResource(context, "entity", Registries.ENTITY_TYPE);
        ResourceLocation id = type.key().location();
        if (!type.value().canSummon()) {
            return CommandResults.fail(source, Messages.SHOWOFF_NOT_SUMMONABLE.get(id.toString()));
        }
        if (!MANetworking.canShowoff(player)) {
            return CommandResults.fail(source, Messages.SHOWOFF_NO_CLIENT.get(player.getName()));
        }
        MANetworking.sendShowoff(player, new ShowoffOpenPayload(ShowoffSubject.ENTITY, id, nbt.copy()));
        return CommandResults.success(source, Messages.SHOWOFF_OPEN_ENTITY.get(id.toString()));
    }

    private static int angle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ShowoffView view = ShowoffView.DEFAULT.withAngle(FloatArgumentType.getFloat(context, "yaw"), FloatArgumentType.getFloat(context, "pitch"));
        return control(context, new ShowoffViewPayload(ShowoffView.ANGLE, view),
                Messages.SHOWOFF_ANGLE.get(format(view.yaw()), format(view.pitch())));
    }

    private static int zoom(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ShowoffView view = ShowoffView.DEFAULT.withZoom(FloatArgumentType.getFloat(context, "zoom"));
        return control(context, new ShowoffViewPayload(ShowoffView.ZOOM, view), Messages.SHOWOFF_ZOOM.get(format(view.zoom())));
    }

    private static int pan(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ShowoffView view = ShowoffView.DEFAULT.withPan(FloatArgumentType.getFloat(context, "x"), FloatArgumentType.getFloat(context, "y"));
        return control(context, new ShowoffViewPayload(ShowoffView.PAN, view),
                Messages.SHOWOFF_PAN.get(format(view.panX()), format(view.panY())));
    }

    private static int reset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ShowoffView view = ShowoffView.DEFAULT;
        return control(context, new ShowoffViewPayload(ShowoffView.ALL, view),
                Messages.SHOWOFF_RESET.get(format(view.yaw()), format(view.pitch())));
    }

    private static int hexBackground(CommandContext<CommandSourceStack> context, String rgb) throws CommandSyntaxException {
        Integer parsed = ShowoffBackground.parseHex(rgb);
        if (parsed == null) {
            return CommandResults.fail(context.getSource(), Messages.SHOWOFF_BAD_HEX.get(rgb));
        }
        return background(context, ShowoffBackground.opaque(parsed));
    }

    private static int background(CommandContext<CommandSourceStack> context, int argb) throws CommandSyntaxException {
        return control(context, new ShowoffBackgroundPayload(argb), Messages.SHOWOFF_BACKGROUND.get(ShowoffBackground.describe(argb)));
    }

    private static int screenshot(CommandContext<CommandSourceStack> context, String name, int width, int height) throws CommandSyntaxException {
        String file = name.isEmpty() ? "" : ShowoffFiles.sanitize(name);
        return control(context, new ShowoffScreenshotPayload(file, width, height), file.isEmpty()
                ? Messages.SHOWOFF_SCREENSHOT_AUTO.get()
                : Messages.SHOWOFF_SCREENSHOT_NAMED.get(file + ShowoffFiles.EXTENSION));
    }

    private static int close(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return control(context, ShowoffClosePayload.INSTANCE, Messages.SHOWOFF_CLOSED.get());
    }

    private static int control(CommandContext<CommandSourceStack> context, CustomPacketPayload payload, Component message)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = CommandResults.player(source);
        if (!MANetworking.sendShowoff(player, payload)) {
            return CommandResults.fail(source, Messages.SHOWOFF_NO_CLIENT.get(player.getName()));
        }
        return CommandResults.success(source, message);
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
