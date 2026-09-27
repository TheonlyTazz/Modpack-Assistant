package com.breakinblocks.modpackassistant.showoff;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

public enum ShowoffSubject implements StringRepresentable {
    STRUCTURE("structure"),
    FILE("file"),
    ENTITY("entity");

    public static final StreamCodec<ByteBuf, ShowoffSubject> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(id -> values()[Math.floorMod(id, values().length)], ShowoffSubject::ordinal);

    private final String name;

    ShowoffSubject(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
