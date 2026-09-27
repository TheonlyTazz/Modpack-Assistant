package com.breakinblocks.modpackassistant.showoff;

import com.breakinblocks.modpackassistant.config.MAConfig;
import com.breakinblocks.modpackassistant.grab.GrabFiles;
import net.minecraft.resources.Identifier;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class ShowoffFiles {
    public static final String EXTENSION = ".png";
    public static final int MIN_CAPTURE_SIZE = 16;
    public static final int MAX_CAPTURE_SIZE = 8192;
    public static final int DEFAULT_CAPTURE_SIZE = 1024;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String FALLBACK_DIRECTORY = "modpackassistant/showoff";

    private ShowoffFiles() {
    }

    public static Path directory() {
        Path game = GrabFiles.gameDirectory();
        try {
            Path configured = Path.of(MAConfig.showoffDirectory());
            Path resolved = (configured.isAbsolute() ? configured : game.resolve(configured)).normalize();
            return resolved.startsWith(game) || configured.isAbsolute() ? resolved : game.resolve(FALLBACK_DIRECTORY);
        } catch (InvalidPathException e) {
            return game.resolve(FALLBACK_DIRECTORY);
        }
    }

    public static String sanitize(String name) {
        return GrabFiles.sanitize(name);
    }

    public static String defaultName(Identifier subject) {
        String path = subject.getPath();
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        return sanitize(leaf + "-" + STAMP.format(ZonedDateTime.now(ZoneOffset.UTC)));
    }
}
