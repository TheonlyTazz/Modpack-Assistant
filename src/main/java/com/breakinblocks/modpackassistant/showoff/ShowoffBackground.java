package com.breakinblocks.modpackassistant.showoff;

import net.minecraft.ChatFormatting;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ShowoffBackground {
    public static final int TRANSPARENT = 0;
    public static final int DEFAULT = 0xFF000000;
    public static final List<ChatFormatting> SWATCHES = Arrays.stream(ChatFormatting.values())
            .filter(ChatFormatting::isColor)
            .toList();

    private ShowoffBackground() {
    }

    public static int of(ChatFormatting color) {
        return opaque(Objects.requireNonNull(color.getColor()));
    }

    public static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }

    public static boolean isTransparent(int argb) {
        return (argb >>> 24) == 0;
    }

    public static String describe(int argb) {
        if (isTransparent(argb)) {
            return "transparent";
        }
        for (ChatFormatting swatch : SWATCHES) {
            if (of(swatch) == argb) {
                return swatch.getName();
            }
        }
        return String.format("#%06X", argb & 0xFFFFFF);
    }
}
