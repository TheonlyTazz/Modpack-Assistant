package com.breakinblocks.modpackassistant.net;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.client.showoff.ShowoffClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ShowoffBackgroundPayload(int argb) implements CustomPacketPayload {
    public static final Type<ShowoffBackgroundPayload> TYPE = new Type<>(ModpackAssistant.id("showoff_background"));

    public static final StreamCodec<ByteBuf, ShowoffBackgroundPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, ShowoffBackgroundPayload::argb,
            ShowoffBackgroundPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShowoffBackgroundPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist().isClient()) {
            context.enqueueWork(() -> ShowoffClient.background(payload.argb()));
        }
    }
}
