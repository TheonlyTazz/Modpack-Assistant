package com.breakinblocks.modpackassistant.client.showoff;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.util.Util;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class PlayerShowoff {
    static final int PARTS = 6;
    static final String[] PART_NAMES = {"Head", "Body", "Left Arm", "Right Arm", "Left Leg", "Right Leg"};
    private final ClientMannequin mannequin;
    private PlayerSkin selectedSkin;
    private final float[][] rotations = new float[PARTS][3];
    private long request;
    private int revision;
    private String status = "Enter a username or UUID";
    private boolean closed;

    PlayerShowoff() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            throw new IllegalStateException("Cannot create a player showoff without a client level");
        }
        Mannequin created = EntityType.MANNEQUIN.create(minecraft.level, EntitySpawnReason.COMMAND);
        if (!(created instanceof ClientMannequin clientMannequin)) {
            throw new IllegalStateException("Minecraft did not create a client mannequin");
        }
        mannequin = clientMannequin;
        selectedSkin = ClientMannequin.DEFAULT_SKIN;
    }

    String status() {
        return status;
    }

    int revision() {
        return revision;
    }

    void close() {
        closed = true;
        request++;
    }

    void inputChanged() {
        request++;
        selectedSkin = ClientMannequin.DEFAULT_SKIN;
        status = "Enter a username or UUID";
        revision++;
    }

    float rotation(int part, int axis) {
        return rotations[Objects.checkIndex(part, PARTS)][Objects.checkIndex(axis, 3)];
    }

    void rotation(int part, int axis, float value) {
        if (!Float.isFinite(value) || value < -180.0F || value > 180.0F) {
            throw new IllegalArgumentException("Limb angles must be finite and between -180 and 180 degrees");
        }
        rotations[Objects.checkIndex(part, PARTS)][Objects.checkIndex(axis, 3)] = value;
        revision++;
    }

    void reset() {
        for (float[] part : rotations) {
            Arrays.fill(part, 0.0F);
        }
        revision++;
    }

    ItemStack equipment(EquipmentSlot slot) {
        Objects.requireNonNull(slot, "Equipment slot");
        ensureOpen();
        return mannequin.getItemBySlot(slot).copy();
    }

    void equip(EquipmentSlot slot, ItemStack stack) {
        Objects.requireNonNull(slot, "Equipment slot");
        Objects.requireNonNull(stack, "Equipment stack");
        ensureOpen();
        mannequin.setItemSlot(slot, stack.copy());
        revision++;
    }

    void lookup(String input) {
        if (closed) {
            throw new IllegalStateException("The player showoff session is closed");
        }
        String value = input.trim();
        long serial = ++request;
        if (value.isEmpty()) {
            status = "Enter a username or UUID";
            return;
        }
        boolean isUuid = value.matches("[0-9a-fA-F]{32}")
                || value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        if (!isUuid && !value.matches("[A-Za-z0-9_]{1,16}")) {
            status = "Invalid username or UUID";
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ProfileResolver resolver = minecraft.services().profileResolver();
        selectedSkin = ClientMannequin.DEFAULT_SKIN;
        status = "Loading skin...";
        revision++;
        CompletableFuture<GameProfile> profileFuture = CompletableFuture.supplyAsync(() -> {
            return (isUuid ? resolver.fetchById(parseUuid(value)) : resolver.fetchByName(value))
                    .orElseThrow(() -> new IllegalArgumentException("Player profile not found: " + value));
        }, Util.backgroundExecutor());
        profileFuture.thenComposeAsync(profile -> {
            if (closed || serial != request) {
                throw new IllegalStateException("Player skin request was superseded");
            }
            selectedSkin = DefaultPlayerSkin.get(profile);
            revision++;
            return minecraft.getSkinManager().get(profile);
        }, minecraft).whenCompleteAsync((skin, error) -> {
            if (closed || serial != request) {
                return;
            }
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                status = "Skin lookup failed: " + cause.getMessage();
                ModpackAssistant.LOGGER.warn("Could not resolve player showoff skin for {}", value, cause);
            } else if (skin.isEmpty()) {
                status = "Skin download failed";
                ModpackAssistant.LOGGER.warn("SkinManager could not load the player showoff skin for {}", value);
            } else {
                selectedSkin = skin.orElseThrow();
                status = "Skin loaded: " + value;
            }
            revision++;
        }, minecraft);
    }

    void render(PoseStack poseStack, SubmitNodeStorage storage, CameraRenderState camera) {
        ensureOpen();
        ShowoffAvatarRenderer renderer = ShowoffClient.avatarRenderer(selectedSkin.model());
        ShowoffAvatarRenderer.State state = new ShowoffAvatarRenderer.State(rotations);
        renderer.extractRenderState(mannequin, state, 0.0F);
        state.skin = selectedSkin;
        state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
        state.nameTag = null;
        renderer.submit(state, poseStack, storage, camera);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("The player showoff session is closed");
        }
    }

    private static UUID parseUuid(String value) {
        String compact = value.replace("-", "");
        return UUID.fromString(compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
                + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20));
    }
}
