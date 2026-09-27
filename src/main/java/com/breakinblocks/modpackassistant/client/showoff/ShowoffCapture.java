package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.grab.GrabFiles;
import com.breakinblocks.modpackassistant.showoff.ShowoffBackground;
import com.breakinblocks.modpackassistant.showoff.ShowoffFiles;
import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class ShowoffCapture {
    private static final int MAX_WAIT_FRAMES = 60;
    private static final List<Request> PENDING = new ArrayList<>();

    private ShowoffCapture() {
    }

    static void request(ShowoffSession session, String name, int width, int height) {
        if (width <= 0 || height <= 0) {
            boolean shown = session.previewWidth() > 0 && session.previewHeight() > 0;
            width = shown ? session.previewWidth() : ShowoffFiles.DEFAULT_CAPTURE_SIZE;
            height = shown ? session.previewHeight() : ShowoffFiles.DEFAULT_CAPTURE_SIZE;
        }
        Path directory = ShowoffFiles.directory();
        Path file = name.isEmpty()
                ? unique(directory, ShowoffFiles.defaultName(session.id()))
                : directory.resolve(ShowoffFiles.sanitize(name) + ShowoffFiles.EXTENSION);
        PENDING.add(new Request(session.scene(), session.view(), session.background(), width, height, file, 0));
    }

    static void clear() {
        PENDING.clear();
    }

    static void process() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Request> requests = List.copyOf(PENDING);
        PENDING.clear();
        for (Request request : requests) {
            if (request.scene().measuring() && request.waited() < MAX_WAIT_FRAMES) {
                PENDING.add(request.waitedOneFrame());
                continue;
            }
            try {
                capture(request);
            } catch (RuntimeException e) {
                ModpackAssistant.LOGGER.error("Failed to capture showoff screenshot {}", request.file(), e);
                chat(Messages.SHOWOFF_SAVE_FAILED.get(GrabFiles.relative(request.file()), String.valueOf(e.getMessage())).withStyle(ChatFormatting.RED));
            }
        }
    }

    private static void capture(Request request) {
        int width = request.width();
        int height = request.height();
        int maxSize = RenderSystem.maxSupportedTextureSize();
        if (width > maxSize || height > maxSize) {
            chat(Messages.SHOWOFF_CAPTURE_TOO_LARGE.get(width, height, maxSize).withStyle(ChatFormatting.RED));
            return;
        }
        NativeImage image;
        try (ShowoffTarget target = new ShowoffTarget(width, height)) {
            target.draw(() -> ShowoffDraw.draw(request.scene(), request.view(), width, height));
            image = target.read();
        }
        composite(image, request.background());
        Util.ioPool().execute(() -> write(image, request.file()));
    }

    private static void composite(NativeImage image, int background) {
        boolean transparent = ShowoffBackground.isTransparent(background);
        int backRed = background >> 16 & 0xFF;
        int backGreen = background >> 8 & 0xFF;
        int backBlue = background & 0xFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int abgr = image.getPixelRGBA(x, y);
                int alpha = abgr >>> 24;
                int blue = abgr >> 16 & 0xFF;
                int green = abgr >> 8 & 0xFF;
                int red = abgr & 0xFF;
                int pixel;
                if (transparent) {
                    pixel = alpha == 0 ? 0
                            : alpha << 24 | unpremultiply(blue, alpha) << 16 | unpremultiply(green, alpha) << 8 | unpremultiply(red, alpha);
                } else {
                    int remaining = 255 - alpha;
                    pixel = 0xFF000000
                            | over(blue, backBlue, remaining) << 16
                            | over(green, backGreen, remaining) << 8
                            | over(red, backRed, remaining);
                }
                image.setPixelRGBA(x, y, pixel);
            }
        }
    }

    private static int unpremultiply(int channel, int alpha) {
        return alpha == 255 ? channel : Math.min(255, (channel * 255 + alpha / 2) / alpha);
    }

    private static int over(int channel, int back, int remaining) {
        return Math.min(255, channel + (back * remaining + 127) / 255);
    }

    private static void write(NativeImage image, Path file) {
        try (image) {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            image.writeToFile(temporary);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            ModpackAssistant.LOGGER.info("Saved showoff screenshot {}", file.toAbsolutePath());
            Minecraft.getInstance().execute(() -> chat(Messages.SHOWOFF_SAVED.get(link(file))));
        } catch (IOException e) {
            ModpackAssistant.LOGGER.error("Failed to write showoff screenshot {}", file, e);
            Minecraft.getInstance().execute(() -> chat(Messages.SHOWOFF_SAVE_FAILED.get(GrabFiles.relative(file), String.valueOf(e.getMessage()))
                    .withStyle(ChatFormatting.RED)));
        }
    }

    private static MutableComponent link(Path file) {
        return Component.literal(GrabFiles.relative(file)).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.toAbsolutePath().toString()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Messages.SHOWOFF_CLICK_OPEN.get())));
    }

    static void chat(Component message) {
        Minecraft.getInstance().gui.getChat().addMessage(message);
    }

    private static Path unique(Path directory, String base) {
        Path file = directory.resolve(base + ShowoffFiles.EXTENSION);
        for (int suffix = 2; Files.exists(file); suffix++) {
            file = directory.resolve(base + "_" + suffix + ShowoffFiles.EXTENSION);
        }
        return file;
    }

    private record Request(ShowoffScene scene, ShowoffView view, int background, int width, int height, Path file, int waited) {
        Request waitedOneFrame() {
            return new Request(scene, view, background, width, height, file, waited + 1);
        }
    }
}
