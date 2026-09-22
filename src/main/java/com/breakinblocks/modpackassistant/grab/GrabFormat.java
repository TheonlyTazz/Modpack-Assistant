package com.breakinblocks.modpackassistant.grab;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum GrabFormat implements StringRepresentable {
    BOTH("both"),
    NBT("nbt"),
    SNBT("snbt");

    public static final String NBT_EXTENSION = ".nbt";
    public static final String SNBT_EXTENSION = ".snbt";

    public static final Codec<GrabFormat> CODEC = StringRepresentable.fromEnum(GrabFormat::values);

    private final String name;

    GrabFormat(String name) {
        this.name = name;
    }

    public boolean writesNbt() {
        return this != SNBT;
    }

    public boolean writesSnbt() {
        return this != NBT;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
