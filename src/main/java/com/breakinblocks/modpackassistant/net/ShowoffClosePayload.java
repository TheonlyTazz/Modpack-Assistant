package com.breakinblocks.modpackassistant.net;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.client.showoff.ShowoffClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ShowoffClosePayload() implements CustomPacketPayload {
    public static final ShowoffClosePayload INSTANCE = new ShowoffClosePayload();
    public static final Type<ShowoffClosePayload> TYPE = new Type<>(ModpackAssistant.id("showoff_close"));
    public static final StreamCodec<ByteBuf, ShowoffClosePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShowoffClosePayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist().isClient()) {
            context.enqueueWork(() -> ShowoffClient.close());
        }
    }
}
