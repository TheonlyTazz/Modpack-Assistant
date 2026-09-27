package com.breakinblocks.modpackassistant.commands.admin;

import com.breakinblocks.modpackassistant.commands.CommandResults;
import com.breakinblocks.modpackassistant.commands.MAPermissions;
import com.breakinblocks.modpackassistant.util.Messages;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

public final class OpswordCommand {
    public static final int LEVEL = 255;
    private static final List<ResourceKey<Enchantment>> ENCHANTMENTS = List.of(
            Enchantments.SHARPNESS,
            Enchantments.UNBREAKING,
            Enchantments.BANE_OF_ARTHROPODS,
            Enchantments.SMITE,
            Enchantments.SWEEPING_EDGE
    );

    private OpswordCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("opsword")
                .requires(MAPermissions.GAMEMASTER)
                .executes(context -> give(context.getSource()));
    }

    private static int give(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = CommandResults.player(source);
        Registry<Enchantment> registry = source.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

        ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Messages.OPSWORD_NAME.get());
        EnchantmentHelper.updateEnchantments(sword, mutable -> {
            for (ResourceKey<Enchantment> key : ENCHANTMENTS) {
                mutable.set(registry.getOrThrow(key), LEVEL);
            }
        });

        if (!player.getInventory().add(sword)) {
            ItemEntity drop = player.drop(sword, false);
            if (drop != null) {
                drop.setNoPickUpDelay();
                drop.setTarget(player.getUUID());
            }
        }
        return CommandResults.success(source, Messages.OPSWORD_GIVEN.get());
    }
}
