package com.breakinblocks.modpackassistant.client.showoff;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

final class ShowoffTarget implements AutoCloseable {
    private final TextureTarget target;

    ShowoffTarget(int width, int height) {
        target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        target.setFilterMode(GL11.GL_NEAREST);
    }

    int width() {
        return target.width;
    }

    int height() {
        return target.height;
    }

    int textureId() {
        return target.getColorTextureId();
    }

    void draw(Runnable drawing) {
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        try {
            drawing.run();
        } finally {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
    }

    NativeImage read() {
        NativeImage image = new NativeImage(target.width, target.height, false);
        RenderSystem.bindTexture(target.getColorTextureId());
        image.downloadTexture(0, false);
        image.flipY();
        return image;
    }

    @Override
    public void close() {
        target.destroyBuffers();
    }
}
