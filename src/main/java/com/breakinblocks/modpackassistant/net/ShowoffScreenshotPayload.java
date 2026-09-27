package com.breakinblocks.modpackassistant.net;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.client.showoff.ShowoffClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ShowoffScreenshotPayload(String name, int width, int height) implements CustomPacketPayload {
    public static final Type<ShowoffScreenshotPayload> TYPE = new Type<>(ModpackAssistant.id("showoff_screenshot"));

    public static final StreamCodec<ByteBuf, ShowoffScreenshotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(256), ShowoffScreenshotPayload::name,
            ByteBufCodecs.VAR_INT, ShowoffScreenshotPayload::width,
            ByteBufCodecs.VAR_INT, ShowoffScreenshotPayload::height,
            ShowoffScreenshotPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShowoffScreenshotPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            context.enqueueWork(() -> ShowoffClient.screenshot(payload.name(), payload.width(), payload.height()));
        }
    }
}
