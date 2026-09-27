package com.breakinblocks.modpackassistant.net;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.client.showoff.ShowoffClient;
import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ShowoffViewPayload(int mask, ShowoffView view) implements CustomPacketPayload {
    public static final Type<ShowoffViewPayload> TYPE = new Type<>(ModpackAssistant.id("showoff_view"));

    public static final StreamCodec<ByteBuf, ShowoffViewPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShowoffViewPayload::mask,
            ShowoffView.STREAM_CODEC, ShowoffViewPayload::view,
            ShowoffViewPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShowoffViewPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist().isClient()) {
            context.enqueueWork(() -> ShowoffClient.view(payload.mask(), payload.view()));
        }
    }
}
