package com.breakinblocks.modpackassistant.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.grab.GrabFiles;
import com.breakinblocks.modpackassistant.grab.GrabFormat;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class ShowoffTemplateFiles {
    private static final int MAX_DEPTH = 4;
    private static final int DEFAULT_DATA_VERSION = 500;

    private ShowoffTemplateFiles() {
    }

    public static Path directory() {
        return GrabFiles.directory();
    }

    public static List<String> list() {
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(directory, MAX_DEPTH)) {
            files.filter(Files::isRegularFile)
                    .map(file -> directory.relativize(file).toString().replace('\\', '/'))
                    .filter(ShowoffTemplateFiles::isTemplate)
                    .map(ShowoffTemplateFiles::stripExtension)
                    .forEach(names::add);
        } catch (IOException e) {
            ModpackAssistant.LOGGER.warn("Could not list structure files in {}", directory, e);
        }
        return List.copyOf(names);
    }

    public static @Nullable Path resolve(String name) {
        Path directory = directory().toAbsolutePath().normalize();
        String cleaned = name.replace('\\', '/');
        List<String> candidates = isTemplate(cleaned)
                ? List.of(cleaned)
                : List.of(cleaned + GrabFormat.NBT_EXTENSION, cleaned + GrabFormat.SNBT_EXTENSION);
        for (String candidate : candidates) {
            try {
                Path file = directory.resolve(candidate).normalize();
                if (file.startsWith(directory) && !file.equals(directory) && Files.isRegularFile(file)) {
                    return file;
                }
            } catch (InvalidPathException ignored) {
                return null;
            }
        }
        return null;
    }

    public static CompoundTag read(Path file, MinecraftServer server) throws IOException, CommandSyntaxException {
        CompoundTag raw = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(GrabFormat.SNBT_EXTENSION)
                ? NbtUtils.snbtToStructure(Files.readString(file, StandardCharsets.UTF_8))
                : NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        int version = NbtUtils.getDataVersion(raw, DEFAULT_DATA_VERSION);
        StructureTemplate template = new StructureTemplate();
        template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK),
                DataFixTypes.STRUCTURE.updateToCurrentVersion(server.getFixerUpper(), raw, version));
        return template.save(new CompoundTag());
    }

    public static String relative(Path file) {
        return directory().toAbsolutePath().normalize().relativize(file).toString().replace('\\', '/');
    }

    public static ResourceLocation id(Path file) {
        return ModpackAssistant.id(GrabFiles.sanitize(stripExtension(relative(file))));
    }

    private static boolean isTemplate(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(GrabFormat.NBT_EXTENSION) || lower.endsWith(GrabFormat.SNBT_EXTENSION);
    }

    private static String stripExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(GrabFormat.SNBT_EXTENSION)) {
            return name.substring(0, name.length() - GrabFormat.SNBT_EXTENSION.length());
        }
        if (lower.endsWith(GrabFormat.NBT_EXTENSION)) {
            return name.substring(0, name.length() - GrabFormat.NBT_EXTENSION.length());
        }
        return name;
    }
}
