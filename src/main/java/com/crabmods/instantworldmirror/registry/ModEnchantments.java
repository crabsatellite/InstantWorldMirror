package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;

public final class ModEnchantments {
    public static final ResourceKey<Enchantment> PERMANENCE = ResourceKey.create(
            Registries.ENCHANTMENT,
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "permanence")
    );

    private ModEnchantments() {
    }

    public static boolean hasPermanence(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        var enchantmentRegistry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        return stack.getEnchantmentLevel(enchantmentRegistry.getOrThrow(PERMANENCE)) > 0;
    }

    public static void applyPermanence(Level level, ItemStack stack) {
        if (stack.isEmpty() || hasPermanence(level, stack)) {
            return;
        }

        var enchantmentRegistry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        stack.enchant(enchantmentRegistry.getOrThrow(PERMANENCE), 1);
    }

    public static boolean isPermanence(Holder<Enchantment> enchantment) {
        return enchantment.is(PERMANENCE);
    }
}
