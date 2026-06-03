package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.item.FirstDreamMirrorItem;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
            ForgeRegistries.ENCHANTMENTS,
            InstantWorldMirror.MODID
    );

    private static final EnchantmentCategory MIRROR_CATEGORY = EnchantmentCategory.create(
            InstantWorldMirror.MODID + "_mirror",
            item -> item instanceof DimensionMirrorItem
    );
    private static final EnchantmentCategory FIRST_DREAM_MIRROR_CATEGORY = EnchantmentCategory.create(
            InstantWorldMirror.MODID + "_first_dream_mirror",
            item -> item instanceof FirstDreamMirrorItem
    );

    public static final RegistryObject<Enchantment> PERMANENCE = ENCHANTMENTS.register(
            "permanence",
            PermanentMirrorEnchantment::new
    );
    public static final RegistryObject<Enchantment> RENEWAL = ENCHANTMENTS.register(
            "renewal",
            RenewalMirrorEnchantment::new
    );

    private ModEnchantments() {
    }

    public static boolean hasPermanence(Level level, ItemStack stack) {
        return canApplyPermanenceTo(stack) && stack.getEnchantmentLevel(PERMANENCE.get()) > 0;
    }

    public static void applyPermanence(Level level, ItemStack stack) {
        if (canApplyPermanenceTo(stack) && !hasPermanence(level, stack)) {
            stack.enchant(PERMANENCE.get(), 1);
        }
    }

    public static boolean isPermanence(Enchantment enchantment) {
        return enchantment == PERMANENCE.get();
    }

    public static boolean hasRenewal(Level level, ItemStack stack) {
        return canApplyRenewalTo(stack) && stack.getEnchantmentLevel(RENEWAL.get()) > 0;
    }

    public static void applyRenewal(Level level, ItemStack stack) {
        if (canApplyRenewalTo(stack) && !hasRenewal(level, stack)) {
            stack.enchant(RENEWAL.get(), 1);
        }
    }

    public static boolean isRenewal(Enchantment enchantment) {
        return enchantment == RENEWAL.get();
    }

    private static boolean canApplyPermanenceTo(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof DimensionMirrorItem;
    }

    private static boolean canApplyRenewalTo(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof FirstDreamMirrorItem;
    }

    private static class PermanentMirrorEnchantment extends Enchantment {
        private PermanentMirrorEnchantment() {
            super(Rarity.RARE, MIRROR_CATEGORY, new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND});
        }

        @Override
        public int getMaxLevel() {
            return 1;
        }

        @Override
        public int getMinCost(int level) {
            return 25;
        }

        @Override
        public int getMaxCost(int level) {
            return 50;
        }

        @Override
        public boolean canEnchant(ItemStack stack) {
            return stack.getItem() instanceof DimensionMirrorItem;
        }
    }

    private static class RenewalMirrorEnchantment extends Enchantment {
        private RenewalMirrorEnchantment() {
            super(Rarity.VERY_RARE, FIRST_DREAM_MIRROR_CATEGORY,
                    new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND});
        }

        @Override
        public int getMaxLevel() {
            return 1;
        }

        @Override
        public int getMinCost(int level) {
            return 45;
        }

        @Override
        public int getMaxCost(int level) {
            return 75;
        }

        @Override
        public boolean isTreasureOnly() {
            return true;
        }

        @Override
        public boolean canEnchant(ItemStack stack) {
            return stack.getItem() instanceof FirstDreamMirrorItem;
        }
    }
}
