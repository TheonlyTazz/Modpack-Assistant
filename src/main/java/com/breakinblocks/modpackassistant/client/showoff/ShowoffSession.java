package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.showoff.ShowoffSubject;
import com.breakinblocks.modpackassistant.showoff.ShowoffView;
import com.breakinblocks.modpackassistant.util.Messages;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class ShowoffSession {
    private final ShowoffSubject subject;
    private final ResourceLocation id;
    private final ShowoffScene scene;
    private ShowoffView view;
    private int background;
    private int previewWidth;
    private int previewHeight;

    ShowoffSession(ShowoffSubject subject, ResourceLocation id, ShowoffScene scene, ShowoffView view, int background) {
        this.subject = subject;
        this.id = id;
        this.scene = scene;
        this.view = view;
        this.background = background;
    }

    ResourceLocation id() {
        return id;
    }

    ShowoffScene scene() {
        return scene;
    }

    Component title() {
        return switch (subject) {
            case STRUCTURE -> Messages.SHOWOFF_TITLE_STRUCTURE.get(id.toString());
            case FILE -> Messages.SHOWOFF_TITLE_FILE.get(id.getPath());
            case ENTITY -> Messages.SHOWOFF_TITLE_ENTITY.get(id.toString());
        };
    }

    ShowoffView view() {
        return view;
    }

    void view(ShowoffView view) {
        this.view = view;
        ShowoffClient.rememberAngle(view);
    }

    int background() {
        return background;
    }

    void background(int background) {
        this.background = background;
    }

    int previewWidth() {
        return previewWidth;
    }

    int previewHeight() {
        return previewHeight;
    }

    void previewSize(int width, int height) {
        this.previewWidth = width;
        this.previewHeight = height;
    }
}
