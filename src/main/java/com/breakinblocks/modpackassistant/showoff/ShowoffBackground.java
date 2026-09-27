package com.breakinblocks.modpackassistant.showoff;

import net.minecraft.ChatFormatting;
import org.jetbrains.annotations.Nullable;

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

    public static @Nullable Integer parseHex(String text) {
        String digits = text.startsWith("#") ? text.substring(1) : text;
        if (!digits.matches("[0-9a-fA-F]{3}|[0-9a-fA-F]{6}")) {
            return null;
        }
        if (digits.length() == 3) {
            digits = "" + digits.charAt(0) + digits.charAt(0) + digits.charAt(1) + digits.charAt(1) + digits.charAt(2) + digits.charAt(2);
        }
        return Integer.parseInt(digits, 16);
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
