package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.grab.GrabFiles;
import com.breakinblocks.modpackassistant.showoff.ShowoffBackground;
import com.breakinblocks.modpackassistant.showoff.ShowoffFiles;
import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class ShowoffCapture {
    private static final Projection PROJECTION = new Projection();
    private static final int MAX_WAIT_FRAMES = 60;
    private static final List<Request> PENDING = new ArrayList<>();
    private static @Nullable ProjectionMatrixBuffer projectionBuffer;

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
        GpuDevice device = RenderSystem.getDevice();
        int width = request.width();
        int height = request.height();
        int maxSize = device.getMaxTextureSize();
        if (width > maxSize || height > maxSize) {
            chat(Messages.SHOWOFF_CAPTURE_TOO_LARGE.get(width, height, maxSize).withStyle(ChatFormatting.RED));
            return;
        }

        GpuTexture color = device.createTexture(() -> "Showoff capture", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8, width, height, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        TextureFormat depthFormat = Minecraft.getInstance().getMainRenderTarget().getDepthTexture().getFormat();
        GpuTexture depth = device.createTexture(() -> "Showoff capture depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, depthFormat, width, height, 1, 1);
        GpuTextureView depthView = device.createTextureView(depth);
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.clearColorAndDepthTextures(color, 0, depth, 1.0);

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        buffers.endBatch();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.outputColorTextureOverride = colorView;
        RenderSystem.outputDepthTextureOverride = depthView;
        try {
            ShowoffDraw.draw(request.scene(), request.view(), width, height, buffers, PROJECTION, projectionBuffer());
            buffers.endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            RenderSystem.restoreProjectionMatrix();
        }

        GpuBuffer readback = device.createBuffer(() -> "Showoff capture readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                (long) width * height * color.getFormat().pixelSize());
        encoder.copyTextureToBuffer(color, readback, 0L, () -> {
            NativeImage image;
            try (GpuBuffer.MappedView mapped = encoder.mapBuffer(readback, true, false)) {
                image = toImage(mapped.data(), width, height, request.background());
            } finally {
                readback.close();
                colorView.close();
                color.close();
                depthView.close();
                depth.close();
            }
            Util.ioPool().execute(() -> write(image, request.file()));
        }, 0);
    }

    private static NativeImage toImage(ByteBuffer data, int width, int height, int background) {
        boolean transparent = ShowoffBackground.isTransparent(background);
        int backRed = background >> 16 & 0xFF;
        int backGreen = background >> 8 & 0xFF;
        int backBlue = background & 0xFF;
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int abgr = data.getInt((x + y * width) * 4);
                int alpha = abgr >>> 24;
                int blue = abgr >> 16 & 0xFF;
                int green = abgr >> 8 & 0xFF;
                int red = abgr & 0xFF;
                int pixel;
                if (transparent) {
                    if (alpha == 0) {
                        pixel = 0;
                    } else {
                        pixel = alpha << 24 | unpremultiply(blue, alpha) << 16 | unpremultiply(green, alpha) << 8 | unpremultiply(red, alpha);
                    }
                } else {
                    int remaining = 255 - alpha;
                    pixel = 0xFF000000
                            | over(blue, backBlue, remaining) << 16
                            | over(green, backGreen, remaining) << 8
                            | over(red, backRed, remaining);
                }
                image.setPixelABGR(x, height - y - 1, pixel);
            }
        }
        return image;
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
                .withClickEvent(new ClickEvent.OpenFile(file.toAbsolutePath().toFile()))
                .withHoverEvent(new HoverEvent.ShowText(Messages.SHOWOFF_CLICK_OPEN.get())));
    }

    static void chat(Component message) {
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(message);
    }

    private static Path unique(Path directory, String base) {
        Path file = directory.resolve(base + ShowoffFiles.EXTENSION);
        for (int suffix = 2; Files.exists(file); suffix++) {
            file = directory.resolve(base + "_" + suffix + ShowoffFiles.EXTENSION);
        }
        return file;
    }

    private static ProjectionMatrixBuffer projectionBuffer() {
        if (projectionBuffer == null) {
            projectionBuffer = new ProjectionMatrixBuffer("Showoff capture");
        }
        return projectionBuffer;
    }

    private record Request(ShowoffScene scene, ShowoffView view, int background, int width, int height, Path file, int waited) {
        Request waitedOneFrame() {
            return new Request(scene, view, background, width, height, file, waited + 1);
        }
    }
}
