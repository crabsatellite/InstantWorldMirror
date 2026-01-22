package com.crabmods.instantworldmirror.item;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.world.MirrorSession;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import com.crabmods.instantworldmirror.world.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * Dimension Mirror - Used to open a portal to the Mirror World
 * 
 * In Overworld: Creates a session and entry portal (anyone can use)
 * In Mirror World: Creates a return portal (only owner can use)
 */
public class DimensionMirrorItem extends Item {

    public DimensionMirrorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockState state = level.getBlockState(pos);

        if (player == null) {
            return InteractionResult.PASS;
        }

        // Check cooldown (skip in creative mode)
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResult.PASS;
        }

        // Check if block is solid (non-transparent)
        if (!state.isSolidRender(level, pos)) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.invalid_block"),
                    true
            );
            return InteractionResult.PASS;
        }

        // Check if player is in any mirror world dimension using the proper check
        boolean isInMirrorWorld = ModDimensions.isMirrorWorld(level.dimension());

        if (!level.isClientSide) {
            ServerLevel serverLevel = (ServerLevel) level;
            ServerPlayer serverPlayer = (ServerPlayer) player;

            InteractionResult result;
            if (isInMirrorWorld) {
                // In Mirror World: Create return portal
                result = createReturnPortal(serverLevel, serverPlayer, pos);
            } else {
                // In any other dimension (Overworld, Nether, End, mod dimensions): Create entry portal with session
                result = createEntryPortal(serverLevel, serverPlayer, pos);
            }
            
            // Apply cooldown on success (creative mode has instant cooldown reset)
            if (result == InteractionResult.SUCCESS) {
                applyCooldown(player, stack);
            }
            
            return result;
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    
    /**
     * Calculate and apply cooldown based on Efficiency enchantment level
     * Base cooldown: 5 minutes (6000 ticks)
     * Each efficiency level reduces cooldown by 20%
     * Efficiency 5 = 30 seconds (600 ticks)
     * Creative mode = instant (0 ticks)
     */
    private void applyCooldown(Player player, ItemStack stack) {
        // Creative mode - no cooldown (or instant reset)
        if (player.isCreative()) {
            return;
        }
        
        int baseCooldownTicks = MirrorConfig.getMirrorCooldownTicks();
        int efficiencyLevel = getEfficiencyLevel(player.level(), stack);
        
        // Each efficiency level reduces cooldown by 20%
        // Level 0: 100% (5 min), Level 1: 80% (4 min), Level 2: 60% (3 min)
        // Level 3: 40% (2 min), Level 4: 20% (1 min), Level 5: 10% (30 sec, minimum)
        double reduction = Math.min(0.9, efficiencyLevel * 0.2); // Cap at 90% reduction
        int finalCooldownTicks = (int) (baseCooldownTicks * (1.0 - reduction));
        
        // Minimum cooldown is 30 seconds (600 ticks)
        finalCooldownTicks = Math.max(600, finalCooldownTicks);
        
        player.getCooldowns().addCooldown(this, finalCooldownTicks);
        
        InstantWorldMirror.LOGGER.debug("Applied cooldown {} ticks to {} (efficiency level: {})",
                finalCooldownTicks, player.getName().getString(), efficiencyLevel);
    }
    
    /**
     * Get the Efficiency enchantment level on the item
     */
    private int getEfficiencyLevel(Level level, ItemStack stack) {
        var registryAccess = level.registryAccess();
        var enchantmentRegistry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        var efficiencyHolder = enchantmentRegistry.getOrThrow(Enchantments.EFFICIENCY);
        return stack.getEnchantmentLevel(efficiencyHolder);
    }

    /**
     * Create an entry portal in the overworld
     */
    private InteractionResult createEntryPortal(ServerLevel level, ServerPlayer player, BlockPos pos) {
        // Check if player already has an active session
        if (MirrorWorldManager.hasActiveSession(player.getUUID())) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.already_has_session"),
                    true
            );
            return InteractionResult.FAIL;
        }

        // Create a new session for this player
        Optional<MirrorSession> sessionOpt = MirrorWorldManager.createSession(player, pos);
        if (sessionOpt.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.session_create_failed"),
                    true
            );
            return InteractionResult.FAIL;
        }

        MirrorSession session = sessionOpt.get();

        // Play portal spawn sound
        level.playSound(null, pos, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.5F, 1.2F);

        // Spawn particle effects
        spawnPortalParticles(level, pos);

        // Spawn portal entity bound to the session
        BlockPos spawnPos = pos.above();
        MirrorPortalEntity portal = new MirrorPortalEntity(
                level,
                spawnPos.getX() + 0.5,
                spawnPos.getY() + 0.5,
                spawnPos.getZ() + 0.5,
                player.getUUID(),
                session,
                pos
        );

        level.addFreshEntity(portal);

        player.displayClientMessage(
                Component.translatable("message.instantworldmirror.portal_created"),
                true
        );

        InstantWorldMirror.LOGGER.info("Player {} created entry portal with session {}",
                player.getName().getString(), session.getSessionId());

        return InteractionResult.SUCCESS;
    }

    /**
     * Create a return portal in the mirror world
     */
    private InteractionResult createReturnPortal(ServerLevel level, ServerPlayer player, BlockPos pos) {
        // Play portal spawn sound
        level.playSound(null, pos, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.5F, 1.2F);

        // Spawn particle effects
        spawnPortalParticles(level, pos);

        // Spawn return portal (only owner can use)
        BlockPos spawnPos = pos.above();
        MirrorPortalEntity portal = new MirrorPortalEntity(
                level,
                spawnPos.getX() + 0.5,
                spawnPos.getY() + 0.5,
                spawnPos.getZ() + 0.5,
                player.getUUID(),
                true // is return portal
        );

        level.addFreshEntity(portal);

        player.displayClientMessage(
                Component.translatable("message.instantworldmirror.return_portal_created"),
                true
        );

        return InteractionResult.SUCCESS;
    }

    /**
     * Spawn portal particle effects
     */
    private void spawnPortalParticles(ServerLevel level, BlockPos pos) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5;

        for (int i = 0; i < 20; i++) {
            double offsetX = (level.random.nextDouble() - 0.5) * 2;
            double offsetY = level.random.nextDouble() * 2;
            double offsetZ = (level.random.nextDouble() - 0.5) * 2;

            level.sendParticles(
                    ParticleTypes.PORTAL,
                    x + offsetX, y + offsetY, z + offsetZ,
                    1, 0, 0, 0, 0
            );
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        // Show enchantment glow only when actually enchanted
        return stack.isEnchanted();
    }
    
    @Override
    public boolean isEnchantable(ItemStack stack) {
        // Allow enchanting this item
        return true;
    }
    
    @Override
    public int getEnchantmentValue() {
        // Enchantability value (similar to gold)
        return 22;
    }
    
    @Override
    public boolean supportsEnchantment(ItemStack stack, net.minecraft.core.Holder<Enchantment> enchantment) {
        // Only allow Efficiency enchantment
        return enchantment.is(Enchantments.EFFICIENCY);
    }
}
