package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.net.ShowoffOpenPayload;
import com.breakinblocks.modpackassistant.showoff.ShowoffBackground;
import com.breakinblocks.modpackassistant.showoff.ShowoffSubject;
import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.breakinblocks.modpackassistant.util.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.entity.player.PlayerModelType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@EventBusSubscriber(modid = ModpackAssistant.MOD_ID, value = Dist.CLIENT)
public final class ShowoffClient {
    private static @Nullable ShowoffSession session;
    private static int background = ShowoffBackground.DEFAULT;
    private static ShowoffView angle = ShowoffView.DEFAULT;
    private static final Map<PlayerModelType, ShowoffAvatarRenderer> avatarRenderers = new EnumMap<>(PlayerModelType.class);

    private ShowoffClient() {
    }

    @SubscribeEvent
    public static void createAvatarRenderers(EntityRenderersEvent.AddLayers event) {
        avatarRenderers.clear();
        avatarRenderers.put(PlayerModelType.WIDE, new ShowoffAvatarRenderer(event.getContext(), false));
        avatarRenderers.put(PlayerModelType.SLIM, new ShowoffAvatarRenderer(event.getContext(), true));
    }

    static ShowoffAvatarRenderer avatarRenderer(PlayerModelType model) {
        return Objects.requireNonNull(avatarRenderers.get(model), "Showoff avatar renderer has not been initialized: " + model);
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
        if (session != null && session.player() != null) {
            session.player().close();
        }
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
        if (scene.player() == null) {
            ShowoffMeasure.request(scene);
        }
        ShowoffSession opened = new ShowoffSession(subject, id, scene, angle, background);
        if (scene.player() != null && data.contains(ShowoffOpenPayload.PLAYER_INPUT_KEY)) {
            String playerInput = data.getString(ShowoffOpenPayload.PLAYER_INPUT_KEY).orElseThrow();
            opened.playerInput(playerInput);
            scene.player().lookup(playerInput);
        }
        session = opened;
        minecraft.setScreen(new ShowoffScreen(opened));
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
        if (closed.player() != null) {
            closed.player().close();
        }
        if (session == closed) {
            session = null;
        }
    }

    private static void notOpen() {
        ShowoffCapture.chat(Messages.SHOWOFF_NOT_OPEN.get().withStyle(ChatFormatting.RED));
    }
}
