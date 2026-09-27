package com.breakinblocks.modpackassistant.net;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = ModpackAssistant.MOD_ID)
public final class MANetworking {
    private MANetworking() {
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModpackAssistant.MOD_ID).optional();
        registrar.playToClient(SetClipboardPayload.TYPE, SetClipboardPayload.STREAM_CODEC, SetClipboardPayload::handle);
        registrar.playToClient(ShowoffOpenPayload.TYPE, ShowoffOpenPayload.STREAM_CODEC, ShowoffOpenPayload::handle);
        registrar.playToClient(ShowoffViewPayload.TYPE, ShowoffViewPayload.STREAM_CODEC, ShowoffViewPayload::handle);
        registrar.playToClient(ShowoffBackgroundPayload.TYPE, ShowoffBackgroundPayload.STREAM_CODEC, ShowoffBackgroundPayload::handle);
        registrar.playToClient(ShowoffScreenshotPayload.TYPE, ShowoffScreenshotPayload.STREAM_CODEC, ShowoffScreenshotPayload::handle);
        registrar.playToClient(ShowoffClosePayload.TYPE, ShowoffClosePayload.STREAM_CODEC, ShowoffClosePayload::handle);
    }

    public static boolean sendClipboard(ServerPlayer player, String text) {
        if (text.length() > SetClipboardPayload.MAX_TEXT_LENGTH) {
            return false;
        }
        if (!hasChannel(player, SetClipboardPayload.TYPE)) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, new SetClipboardPayload(text));
        return true;
    }

    public static boolean canShowoff(ServerPlayer player) {
        return hasChannel(player, ShowoffOpenPayload.TYPE);
    }

    public static boolean sendShowoff(ServerPlayer player, CustomPacketPayload payload) {
        if (!hasChannel(player, payload.type())) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, payload);
        return true;
    }

    private static boolean hasChannel(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return !(player instanceof FakePlayer) && player.connection.hasChannel(type);
    }
}
