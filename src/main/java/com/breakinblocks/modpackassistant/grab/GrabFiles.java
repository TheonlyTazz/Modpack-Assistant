package com.breakinblocks.modpackassistant.grab;

import com.breakinblocks.modpackassistant.config.MAConfig;
import com.breakinblocks.modpackassistant.util.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class GrabFiles {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String FALLBACK_NAME = "grab";
    private static final int MAX_NAME_LENGTH = 64;

    private GrabFiles() {
    }

    public static Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path directory() {
        Path game = gameDirectory();
        try {
            Path configured = Path.of(MAConfig.structureDirectory());
            Path resolved = (configured.isAbsolute() ? configured : game.resolve(configured)).normalize();
            return resolved.startsWith(game) || configured.isAbsolute() ? resolved : game.resolve(FALLBACK_NAME);
        } catch (InvalidPathException e) {
            return game.resolve(FALLBACK_NAME);
        }
    }

    public static String defaultName() {
        return FALLBACK_NAME + "-" + STAMP.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    public static String sanitize(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("^[._]+|[._]+$", "");
        if (cleaned.isEmpty()) {
            return FALLBACK_NAME;
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    public static String prepare(Path directory, String name, GrabFormat format) throws IOException {
        Files.createDirectories(directory);
        String base = name;
        for (int suffix = 1; taken(directory, base, format); suffix++) {
            base = name + "_" + suffix;
        }
        return base;
    }

    public static void writeNbt(Path path, CompoundTag structure) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        NbtIo.writeCompressed(structure, temporary);
        Files.move(temporary, path);
    }

    public static void writeSnbt(Path path, CompoundTag structure) throws IOException {
        Files.writeString(path, NbtUtils.structureToSnbt(structure) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static boolean taken(Path directory, String base, GrabFormat format) {
        if (format.writesNbt() && Files.exists(directory.resolve(base + GrabFormat.NBT_EXTENSION))) {
            return true;
        }
        return format.writesSnbt() && Files.exists(directory.resolve(base + GrabFormat.SNBT_EXTENSION));
    }

    public static String relative(Path path) {
        Path game = gameDirectory();
        Path shown = path.startsWith(game) ? game.relativize(path) : path;
        return shown.toString().replace('\\', '/');
    }

    public static MutableComponent pathMessage(Path path) {
        String relative = relative(path);
        MutableComponent link = Component.literal(relative).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(relative))
                .withHoverEvent(new HoverEvent.ShowText(Messages.REPORT_CLICK.get())));
        return Messages.GRAB_WRITTEN.get(link);
    }
}
