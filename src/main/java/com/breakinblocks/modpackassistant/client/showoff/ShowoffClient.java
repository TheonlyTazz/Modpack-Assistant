package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.showoff.ShowoffBackground;
import com.breakinblocks.modpackassistant.showoff.ShowoffSubject;
import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = ModpackAssistant.MOD_ID, value = Dist.CLIENT)
public final class ShowoffClient {
    private static @Nullable ShowoffSession session;
    private static int background = ShowoffBackground.DEFAULT;
    private static ShowoffView angle = ShowoffView.DEFAULT;

    private ShowoffClient() {
    }

    @SubscribeEvent
    public static void registerPictureRenderers(RegisterPictureInPictureRenderersEvent event) {
        event.register(ShowoffRenderState.class, ShowoffPictureRenderer::new);
    }

    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) {
        ShowoffMeasure.process();
        ShowoffCapture.process();
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        session = null;
        ShowoffMeasure.clear();
        ShowoffCapture.clear();
    }

    public static void open(ShowoffSubject subject, Identifier id, CompoundTag data) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        ShowoffScene scene;
        try {
            scene = subject == ShowoffSubject.ENTITY ? SceneBuilder.entity(id, data, level) : SceneBuilder.structure(data, level);
        } catch (RuntimeException e) {
            ModpackAssistant.LOGGER.error("Failed to build the showoff view of {}", id, e);
            ShowoffCapture.chat(Messages.SHOWOFF_BUILD_FAILED.get(id.toString(), String.valueOf(e.getMessage())).withStyle(ChatFormatting.RED));
            return;
        }
        if (scene == null) {
            ShowoffCapture.chat(Messages.SHOWOFF_ENTITY_FAILED.get(id.toString()).withStyle(ChatFormatting.RED));
            return;
        }
        ShowoffMeasure.request(scene);
        ShowoffSession opened = new ShowoffSession(subject, id, scene, angle, background);
        session = opened;
        minecraft.setScreen(new ShowoffScreen(opened));
    }

    /**
     * Captures a showoff subject on the client render thread and writes the complete PNG to {@code output}.
     * The returned future completes after the file has been written. Rendering and I/O failures complete it
     * exceptionally; this method never opens the showoff screen.
     * Requires a loaded client world and an active render loop. May be called from another thread;
     * do not block the client thread waiting for the result. The caller must create the output directory.
     * The PNG retains the requested dimensions and existing showoff background/alpha semantics.
     * Invalid arguments throw immediately; scene, GPU, and file failures complete the future exceptionally.
     */
    public static CompletableFuture<Path> capture(ShowoffSubject subject, Identifier id, CompoundTag data,
                                                   ShowoffView view, int background, int width, int height,
                                                   Path output) {
        if (subject == null || id == null || data == null || view == null || output == null) {
            throw new NullPointerException("capture arguments must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Capture dimensions must be positive");
        }
        if ((long) width * height > Integer.MAX_VALUE / 4L) {
            throw new IllegalArgumentException("Capture is too large for a readback buffer");
        }
        if (!Float.isFinite(view.yaw()) || !Float.isFinite(view.pitch()) || !Float.isFinite(view.zoom())
                || !Float.isFinite(view.panX()) || !Float.isFinite(view.panY())
                || view.pitch() < ShowoffView.MIN_PITCH || view.pitch() > ShowoffView.MAX_PITCH
                || view.zoom() < ShowoffView.MIN_ZOOM || view.zoom() > ShowoffView.MAX_ZOOM
                || Math.abs(view.panX()) > ShowoffView.MAX_PAN || Math.abs(view.panY()) > ShowoffView.MAX_PAN) {
            throw new IllegalArgumentException("Showoff view contains invalid values");
        }
        CompoundTag copy = data.copy();
        CompletableFuture<Path> result = new CompletableFuture<>();
        try {
            Minecraft.getInstance().execute(() -> {
                try {
                    ClientLevel level = Minecraft.getInstance().level;
                    if (level == null) {
                        throw new IllegalStateException("Cannot capture a showoff view without a client level");
                    }
                    int maximum = RenderSystem.getDevice().getMaxTextureSize();
                    if (width > maximum || height > maximum) {
                        throw new IllegalArgumentException("Capture dimensions exceed GPU limit " + maximum);
                    }
                    ShowoffScene scene = subject == ShowoffSubject.ENTITY
                            ? SceneBuilder.strictEntity(id, copy, level)
                            : SceneBuilder.strictStructure(copy, level);
                    if (scene == null) {
                        throw new IllegalArgumentException("Could not build showoff scene for " + id);
                    }
                    ShowoffMeasure.request(scene, true);
                    ShowoffCapture.requestHeadless(scene, view, background, width, height, output, result);
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    public static void view(int mask, ShowoffView view) {
        if (session == null) {
            notOpen();
            return;
        }
        session.view(session.view().merge(view, mask));
    }

    public static void background(int argb) {
        background = argb;
        if (session != null) {
            session.background(argb);
        }
    }

    static void rememberAngle(ShowoffView view) {
        angle = ShowoffView.DEFAULT.withAngle(view.yaw(), view.pitch());
    }

    public static void screenshot(String name, int width, int height) {
        if (session == null) {
            notOpen();
            return;
        }
        ShowoffCapture.request(session, name, width, height);
    }

    public static void close() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ShowoffScreen) {
            minecraft.setScreen(null);
        }
        session = null;
    }

    static void closed(ShowoffSession closed) {
        if (session == closed) {
            session = null;
        }
    }

    private static void notOpen() {
        ShowoffCapture.chat(Messages.SHOWOFF_NOT_OPEN.get().withStyle(ChatFormatting.RED));
    }
}
