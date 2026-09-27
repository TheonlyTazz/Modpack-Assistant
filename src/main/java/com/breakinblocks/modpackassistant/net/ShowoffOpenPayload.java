package com.breakinblocks.modpackassistant.net;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.client.showoff.ShowoffClient;
import com.breakinblocks.modpackassistant.showoff.ShowoffSubject;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ShowoffOpenPayload(ShowoffSubject subject, Identifier id, CompoundTag data) implements CustomPacketPayload {
    public static final Type<ShowoffOpenPayload> TYPE = new Type<>(ModpackAssistant.id("showoff_open"));

    public static final StreamCodec<ByteBuf, ShowoffOpenPayload> STREAM_CODEC = StreamCodec.composite(
            ShowoffSubject.STREAM_CODEC, ShowoffOpenPayload::subject,
            Identifier.STREAM_CODEC, ShowoffOpenPayload::id,
            ByteBufCodecs.TRUSTED_COMPOUND_TAG, ShowoffOpenPayload::data,
            ShowoffOpenPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShowoffOpenPayload payload, IPayloadContext context) {
        if (FMLEnvironment.getDist().isClient()) {
            context.enqueueWork(() -> ShowoffClient.open(payload.subject(), payload.id(), payload.data()));
        }
    }
}
