package com.breakinblocks.modpackassistant.client.showoff;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.util.ArrayList;
import java.util.List;

/** Owns resources allocated for one asynchronous showoff readback. */
final class ShowoffGpuResources implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();

    void color(GpuTexture value) {
        resources.add(value);
    }

    void colorView(GpuTextureView value) {
        resources.add(value);
    }

    void depth(GpuTexture value) {
        resources.add(value);
    }

    void depthView(GpuTextureView value) {
        resources.add(value);
    }

    void readback(GpuBuffer value) {
        resources.add(value);
    }

    void closeAfterFailure(RuntimeException failure) {
        try {
            close();
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception cause) {
                if (failure == null) {
                    failure = new IllegalStateException("Failed to close showoff GPU resources", cause);
                } else {
                    failure.addSuppressed(cause);
                }
            }
        }
        resources.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
