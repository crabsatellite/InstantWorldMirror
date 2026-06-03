package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.item.FirstDreamMirrorItem;
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
    public static final ResourceKey<Enchantment> RENEWAL = ResourceKey.create(
            Registries.ENCHANTMENT,
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "renewal")
    );

    private ModEnchantments() {
    }

    public static boolean hasPermanence(Level level, ItemStack stack) {
        if (!canApplyPermanenceTo(stack)) {
            return false;
        }

        var enchantmentRegistry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        return stack.getEnchantmentLevel(enchantmentRegistry.getOrThrow(PERMANENCE)) > 0;
    }

    public static void applyPermanence(Level level, ItemStack stack) {
        if (!canApplyPermanenceTo(stack) || hasPermanence(level, stack)) {
            return;
        }

        var enchantmentRegistry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        stack.enchant(enchantmentRegistry.getOrThrow(PERMANENCE), 1);
    }

    public static boolean isPermanence(Holder<Enchantment> enchantment) {
        return enchantment.is(PERMANENCE);
    }

    public static boolean hasRenewal(Level level, ItemStack stack) {
        if (!canApplyRenewalTo(stack)) {
            return false;
        }

        var enchantmentRegistry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        return stack.getEnchantmentLevel(enchantmentRegistry.getOrThrow(RENEWAL)) > 0;
    }

    public static void applyRenewal(Level level, ItemStack stack) {
        if (!canApplyRenewalTo(stack) || hasRenewal(level, stack)) {
            return;
        }

        var enchantmentRegistry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        stack.enchant(enchantmentRegistry.getOrThrow(RENEWAL), 1);
    }

    public static boolean isRenewal(Holder<Enchantment> enchantment) {
        return enchantment.is(RENEWAL);
    }

    private static boolean canApplyPermanenceTo(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof DimensionMirrorItem;
    }

    private static boolean canApplyRenewalTo(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof FirstDreamMirrorItem;
    }
}
