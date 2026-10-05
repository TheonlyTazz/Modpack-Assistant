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
import java.util.concurrent.CompletableFuture;

final class ShowoffCapture {
    private static final Projection PROJECTION = new Projection();
    private static final int MAX_WAIT_FRAMES = 60;
    private static final List<Request> PENDING = new ArrayList<>();
    private static final List<HeadlessRequest> HEADLESS = new ArrayList<>();
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
        RuntimeException failure = new IllegalStateException("Showoff capture cancelled because the client logged out");
        for (HeadlessRequest request : HEADLESS) {
            request.result().completeExceptionally(failure);
        }
        HEADLESS.clear();
        PENDING.clear();
    }

    static void process() {
        if (PENDING.isEmpty() && HEADLESS.isEmpty()) {
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
        if (!HEADLESS.isEmpty()) {
            List<HeadlessRequest> headless = List.copyOf(HEADLESS);
            HEADLESS.clear();
            for (HeadlessRequest request : headless) {
                if (request.scene().measurementFailure() != null) {
                    request.result().completeExceptionally(request.scene().measurementFailure());
                } else if (request.scene().measuring() && request.waited() < MAX_WAIT_FRAMES) {
                    HEADLESS.add(request.waitedOneFrame());
                } else if (request.scene().measuring()) {
                    RuntimeException timeout = new IllegalStateException("Showoff measurement timed out");
                    request.scene().measurementFailed(timeout);
                    request.result().completeExceptionally(timeout);
                } else {
                    try {
                        capture(request);
                    } catch (RuntimeException e) {
                        request.result().completeExceptionally(e);
                    }
                }
            }
        }
    }

    static void requestHeadless(ShowoffScene scene, ShowoffView view, int background,
                                 int width, int height, Path file, CompletableFuture<Path> result) {
        HEADLESS.add(new HeadlessRequest(scene, view, background, width, height, file, 0, result));
    }

    private static void capture(Request request) {
        capture(request, null);
    }

    private static void capture(HeadlessRequest request) {
        capture(new Request(request.scene(), request.view(), request.background(), request.width(), request.height(), request.file(), request.waited()), request.result());
    }

    private static void capture(Request request, @Nullable CompletableFuture<Path> result) {
        GpuDevice device = RenderSystem.getDevice();
        int width = request.width();
        int height = request.height();
        int maxSize = device.getMaxTextureSize();
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE / 4L) {
            throw new IllegalArgumentException("Capture dimensions are invalid");
        }
        if (width > maxSize || height > maxSize) {
            if (result != null) {
                throw new IllegalArgumentException("Capture dimensions exceed GPU limit " + maxSize);
            }
            chat(Messages.SHOWOFF_CAPTURE_TOO_LARGE.get(width, height, maxSize).withStyle(ChatFormatting.RED));
            return;
        }

        ShowoffGpuResources resources = new ShowoffGpuResources();
        GpuTexture color;
        GpuTextureView colorView;
        GpuTexture depth;
        GpuTextureView depthView;
        try {
            color = device.createTexture(() -> "Showoff capture", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.RGBA8, width, height, 1, 1);
            resources.color(color);
            colorView = device.createTextureView(color);
            resources.colorView(colorView);
            TextureFormat depthFormat = Minecraft.getInstance().getMainRenderTarget().getDepthTexture().getFormat();
            depth = device.createTexture(() -> "Showoff capture depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, depthFormat, width, height, 1, 1);
            resources.depth(depth);
            depthView = device.createTextureView(depth);
            resources.depthView(depthView);
        } catch (RuntimeException e) {
            resources.closeAfterFailure(e);
            throw e;
        }
        CommandEncoder encoder;
        try {
            encoder = device.createCommandEncoder();
            encoder.clearColorAndDepthTextures(color, 0, depth, 1.0);
        } catch (RuntimeException e) {
            resources.closeAfterFailure(e);
            throw e;
        }

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        try {
            buffers.endBatch();
            RenderSystem.backupProjectionMatrix();
            GpuTextureView previousColor = RenderSystem.outputColorTextureOverride;
            GpuTextureView previousDepth = RenderSystem.outputDepthTextureOverride;
            RenderSystem.outputColorTextureOverride = colorView;
            RenderSystem.outputDepthTextureOverride = depthView;
            try {
                ShowoffDraw.draw(request.scene(), request.view(), width, height, buffers, PROJECTION, projectionBuffer());
            } finally {
                try {
                    buffers.endBatch();
                } finally {
                    RenderSystem.outputColorTextureOverride = previousColor;
                    RenderSystem.outputDepthTextureOverride = previousDepth;
                    RenderSystem.restoreProjectionMatrix();
                }
            }
        } catch (RuntimeException e) {
            resources.closeAfterFailure(e);
            throw e;
        }

        GpuBuffer readback;
        try {
            readback = device.createBuffer(() -> "Showoff capture readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                    (long) width * height * color.getFormat().pixelSize());
            resources.readback(readback);
            encoder.copyTextureToBuffer(color, readback, 0L, () -> {
                NativeImage image = null;
                try (resources) {
                    try (GpuBuffer.MappedView mapped = encoder.mapBuffer(readback, true, false)) {
                        image = toImage(mapped.data(), width, height, request.background());
                    }
                } catch (RuntimeException e) {
                    if (image != null) {
                        image.close();
                    }
                    if (result != null) {
                        result.completeExceptionally(e);
                    } else {
                        ModpackAssistant.LOGGER.error("Failed to read showoff screenshot {}", request.file(), e);
                    }
                    return;
                }
                NativeImage captured = image;
                if (result == null) {
                    try {
                        Util.ioPool().execute(() -> write(captured, request.file()));
                    } catch (RuntimeException e) {
                        captured.close();
                        ModpackAssistant.LOGGER.error("Failed to schedule showoff screenshot {}", request.file(), e);
                    }
                } else {
                    writeHeadless(captured, request.file(), result);
                }
            }, 0);
        } catch (RuntimeException e) {
            resources.closeAfterFailure(e);
            throw e;
        }
    }

    private static NativeImage toImage(ByteBuffer data, int width, int height, int background) {
        boolean transparent = ShowoffBackground.isTransparent(background);
        int backRed = background >> 16 & 0xFF;
        int backGreen = background >> 8 & 0xFF;
        int backBlue = background & 0xFF;
        NativeImage image = new NativeImage(width, height, false);
        try {
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
        } catch (RuntimeException e) {
            image.close();
            throw e;
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

    private static void writeHeadless(NativeImage image, Path file, CompletableFuture<Path> result) {
        try {
            Util.ioPool().execute(() -> {
                try (image) {
                    image.writeToFile(file);
                } catch (IOException | RuntimeException e) {
                    result.completeExceptionally(e);
                    return;
                }
                result.complete(file);
            });
        } catch (RuntimeException e) {
            image.close();
            result.completeExceptionally(e);
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

    private record HeadlessRequest(ShowoffScene scene, ShowoffView view, int background, int width, int height,
                                   Path file, int waited, CompletableFuture<Path> result) {
        HeadlessRequest waitedOneFrame() {
            return new HeadlessRequest(scene, view, background, width, height, file, waited + 1, result);
        }
    }
}
