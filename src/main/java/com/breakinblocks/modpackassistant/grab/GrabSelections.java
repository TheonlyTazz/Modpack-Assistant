package com.breakinblocks.modpackassistant.grab;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = ModpackAssistant.MOD_ID)
public final class GrabSelections {
    public record Selection(ResourceKey<Level> dimension, @Nullable BlockPos first, @Nullable BlockPos second) {
        public boolean complete() {
            return first != null && second != null;
        }
    }

    private static final Map<UUID, Selection> SELECTIONS = new ConcurrentHashMap<>();

    private GrabSelections() {
    }

    public static UUID key(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getUUID() : Util.NIL_UUID;
    }

    @Nullable
    public static Selection get(CommandSourceStack source) {
        return SELECTIONS.get(key(source));
    }

    public static Selection setFirst(CommandSourceStack source, ResourceKey<Level> dimension, BlockPos pos) {
        return update(source, dimension, pos, true);
    }

    public static Selection setSecond(CommandSourceStack source, ResourceKey<Level> dimension, BlockPos pos) {
        return update(source, dimension, pos, false);
    }

    public static boolean clear(CommandSourceStack source) {
        return SELECTIONS.remove(key(source)) != null;
    }

    private static Selection update(CommandSourceStack source, ResourceKey<Level> dimension, BlockPos pos, boolean first) {
        return SELECTIONS.compute(key(source), (id, current) -> {
            Selection base = current != null && current.dimension().equals(dimension)
                    ? current
                    : new Selection(dimension, null, null);
            return first
                    ? new Selection(dimension, pos, base.second())
                    : new Selection(dimension, base.first(), pos);
        });
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SELECTIONS.remove(event.getEntity().getUUID());
    }
}
