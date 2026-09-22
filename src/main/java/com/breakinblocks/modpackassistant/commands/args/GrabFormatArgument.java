package com.breakinblocks.modpackassistant.commands.args;

import com.breakinblocks.modpackassistant.grab.GrabFormat;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;

import java.util.concurrent.CompletableFuture;

public final class GrabFormatArgument {
    private GrabFormatArgument() {
    }

    public static StringArgumentType grabFormat() {
        return StringArgumentType.word();
    }

    public static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return MAArguments.suggest(GrabFormat.values(), builder);
    }

    public static GrabFormat get(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        return MAArguments.get(context, name, GrabFormat.values());
    }
}
