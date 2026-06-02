package com.crabmods.instantworldmirror.item;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.MirrorConfig;
import com.crabmods.instantworldmirror.client.renderer.MirrorItemRenderer;
import com.crabmods.instantworldmirror.entity.MirrorPortalEntity;
import com.crabmods.instantworldmirror.network.SyncCooldownPacket;
import com.crabmods.instantworldmirror.registry.ModEnchantments;
import com.crabmods.instantworldmirror.world.MirrorSession;
import com.crabmods.instantworldmirror.world.MirrorWorldManager;
import com.crabmods.instantworldmirror.world.MirrorKind;
import com.crabmods.instantworldmirror.world.ModDimensions;
import com.crabmods.instantworldmirror.world.PersistentMirrorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Dimensional Mirror - Used to open a portal to the Mirror World
 * 
 * In Overworld: Creates a session and entry portal (anyone can use)
 * In Mirror World: Creates a return portal (only owner can use)
 * 
 * Uses a custom server-side cooldown system based on real time (milliseconds)
 * Similar to Waystones mod - allows creative mode to bypass cooldown
 */
public class DimensionMirrorItem extends Item {

    private static final String COOLDOWN_NBT_KEY = InstantWorldMirror.MODID + ":mirror_cooldown_until";
    private static final String COOLDOWN_DURATION_NBT_KEY = InstantWorldMirror.MODID + ":mirror_cooldown_duration";
    private final boolean sandboxMode;
    
    // Custom server-side cooldown tracking (playerUUID -> cooldown end timestamp in milliseconds)
    private static final Map<UUID, Long> COOLDOWNS = new ConcurrentHashMap<>();
    
    // Total cooldown duration tracking (playerUUID -> total cooldown in milliseconds)
    // Used for progress bar calculation on client
    private static final Map<UUID, Long> COOLDOWN_DURATIONS = new ConcurrentHashMap<>();

    public DimensionMirrorItem(Properties properties) {
        this(properties, false);
    }

    protected DimensionMirrorItem(Properties properties, boolean sandboxMode) {
        super(properties);
        this.sandboxMode = sandboxMode;
    }
    
    /**
     * Get remaining cooldown milliseconds for a player (0 = no cooldown)
     */
    public static long getRemainingCooldownMillis(UUID playerUUID) {
        Long endTime = COOLDOWNS.get(playerUUID);
        if (endTime == null) {
            return 0;
        }
        long remaining = endTime - System.currentTimeMillis();
        if (remaining <= 0) {
            COOLDOWNS.remove(playerUUID); // Clean up expired entry
            return 0;
        }
        return remaining;
    }
    
    /**
     * Set cooldown for a player (in seconds)
     */
    public static void setCooldown(UUID playerUUID, int seconds) {
        long durationMillis = seconds * 1000L;
        long endTime = System.currentTimeMillis() + durationMillis;
        COOLDOWNS.put(playerUUID, endTime);
        COOLDOWN_DURATIONS.put(playerUUID, durationMillis);
    }
    
    /**
     * Clear cooldown for a player
     */
    public static void clearCooldown(UUID playerUUID) {
        COOLDOWNS.remove(playerUUID);
        COOLDOWN_DURATIONS.remove(playerUUID);
    }
    
    /**
     * Clear all cooldowns (called on server stop)
     */
    public static void clearAllCooldowns() {
        COOLDOWNS.clear();
        COOLDOWN_DURATIONS.clear();
    }
    
    /**
     * Save the player's dimension mirror cooldown to persistent data
     * Called on logout and server stop
     */
    public static void saveCooldown(ServerPlayer player) {
        UUID playerId = player.getUUID();
        Long cooldownUntil = COOLDOWNS.get(playerId);
        Long cooldownDuration = COOLDOWN_DURATIONS.get(playerId);
        
        if (cooldownUntil != null && cooldownUntil > System.currentTimeMillis()) {
            CompoundTag persistentData = player.getPersistentData();
            persistentData.putLong(COOLDOWN_NBT_KEY, cooldownUntil);
            if (cooldownDuration != null) {
                persistentData.putLong(COOLDOWN_DURATION_NBT_KEY, cooldownDuration);
            }
            
            InstantWorldMirror.LOGGER.debug("Saved cooldown until {} (duration: {} ms) for {}",
                    cooldownUntil, cooldownDuration, player.getName().getString());
        }
        
        // Remove from active cooldowns map
        COOLDOWNS.remove(playerId);
        COOLDOWN_DURATIONS.remove(playerId);
    }
    
    /**
     * Restore the player's dimension mirror cooldown from persistent data
     * Called on login
     */
    public static void restoreCooldown(ServerPlayer player) {
        CompoundTag persistentData = player.getPersistentData();
        UUID playerId = player.getUUID();
        
        if (persistentData.contains(COOLDOWN_NBT_KEY)) {
            long cooldownUntil = persistentData.getLong(COOLDOWN_NBT_KEY);
            long now = System.currentTimeMillis();
            
            if (cooldownUntil > now) {
                // Re-apply cooldown (just put back the end timestamp)
                COOLDOWNS.put(playerId, cooldownUntil);
                
                // Restore total cooldown duration if saved
                if (persistentData.contains(COOLDOWN_DURATION_NBT_KEY)) {
                    long cooldownDuration = persistentData.getLong(COOLDOWN_DURATION_NBT_KEY);
                    COOLDOWN_DURATIONS.put(playerId, cooldownDuration);
                }
                
                // Sync to client for HUD display
                syncCooldownToClient(player);
                
                InstantWorldMirror.LOGGER.debug("Restored cooldown for {} ({} ms remaining)",
                        player.getName().getString(), cooldownUntil - now);
            } else {
                InstantWorldMirror.LOGGER.debug("Cooldown expired while {} was offline", 
                        player.getName().getString());
            }
            
            // Clean up saved data
            persistentData.remove(COOLDOWN_NBT_KEY);
            persistentData.remove(COOLDOWN_DURATION_NBT_KEY);
        }
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

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                PersistentMirrorManager.openMirrorMenu(serverPlayer, stack);
            }
            return InteractionResult.SUCCESS;
        }

        // Client side: return PASS to let server decide
        // This ensures the interaction reaches the server for cooldown checking
        if (level.isClientSide) {
            return InteractionResult.PASS;
        }

        // Server side: handle cooldown
        ServerPlayer serverPlayer = (ServerPlayer) player;
        
        // Check if player is denied access to mirror world
        if (!MirrorWorldManager.canAccessMirrorWorld(serverPlayer)) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.access_denied"),
                    true
            );
            return InteractionResult.FAIL;
        }
        
        boolean bypassCooldown = player.isCreative();

        if (bypassCooldown) {
            // Creative mode: clear any existing cooldown and sync to client
            clearCooldown(player.getUUID());
            syncCooldownToClient(serverPlayer); // Sync cleared cooldown to client
            InstantWorldMirror.LOGGER.debug("Creative mode: cleared cooldown for {}", player.getName().getString());
        } else {
            // Survival/Adventure: check cooldown
            long remainingMillis = getRemainingCooldownMillis(player.getUUID());
            if (remainingMillis > 0) {
                int seconds = (int) (remainingMillis / 1000);
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.cooldown", seconds),
                        true
                );
                return InteractionResult.FAIL;
            }
        }

        // Check if block is solid (non-transparent)
        if (!state.isSolidRender(level, pos)) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.invalid_block"),
                    true
            );
            return InteractionResult.FAIL;
        }
        
        // Check if there's enough space above the block for the portal (2 blocks high)
        BlockPos portalPos = pos.above();
        BlockPos portalPosAbove = portalPos.above();
        BlockState portalState = level.getBlockState(portalPos);
        BlockState portalStateAbove = level.getBlockState(portalPosAbove);
        
        if (portalState.isSolid() || portalStateAbove.isSolid()) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.no_space_for_portal"),
                    true
            );
            return InteractionResult.FAIL;
        }

        // Check if player is in any mirror world dimension using the proper check
        boolean isInMirrorWorld = ModDimensions.isAnyMirrorWorld(level.dimension());

        ServerLevel serverLevel = (ServerLevel) level;

        InteractionResult result;
        if (isInMirrorWorld) {
            if (sandboxMode || MirrorWorldManager.isPlayerInSandboxSession(serverPlayer)) {
                player.displayClientMessage(
                        Component.translatable("message.instantworldmirror.heaven_return_restricted"),
                        true
                );
                return InteractionResult.FAIL;
            }

            // In Mirror World: Create return portal
            result = createReturnPortal(serverLevel, serverPlayer, pos);
        } else {
            // In any other dimension (Overworld, Nether, End, mod dimensions): Create entry portal with session
            result = createEntryPortal(serverLevel, serverPlayer, pos, hasPermanence(level, stack));
        }
        
        // Apply cooldown on success (creative mode skips cooldown)
        if (result == InteractionResult.SUCCESS && !bypassCooldown) {
            applyCooldown(serverPlayer, stack);
        }
        
        return result;
    }
    
    /**
     * Calculate and apply cooldown based on Efficiency enchantment level
     * Base cooldown: 5 minutes (300 seconds)
     * Each efficiency level reduces cooldown by 20%
     * Efficiency 5 = 30 seconds (minimum)
     */
    public static void applyCooldown(ServerPlayer player, ItemStack stack) {
        int finalCooldownSeconds = calculateCooldownSeconds(player.level(), stack);
        int efficiencyLevel = getEfficiencyLevel(player.level(), stack);

        // Set our custom cooldown (real time based)
        setCooldown(player.getUUID(), finalCooldownSeconds);

        // Sync cooldown to client for HUD display
        syncCooldownToClient(player);

        InstantWorldMirror.LOGGER.debug("Applied cooldown {} seconds to {} (efficiency level: {})",
                finalCooldownSeconds, player.getName().getString(), efficiencyLevel);
    }

    public static ItemStack findMirrorStack(Player player) {
        ItemStack useStack = player.getUseItem();
        if (isMirrorStack(useStack)) {
            return useStack;
        }

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack handStack = player.getItemInHand(hand);
            if (isMirrorStack(handStack)) {
                return handStack;
            }
        }

        return ItemStack.EMPTY;
    }

    private static boolean isMirrorStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof DimensionMirrorItem;
    }

    public static boolean hasPermanence(Level level, ItemStack stack) {
        return isMirrorStack(stack) && ModEnchantments.hasPermanence(level, stack);
    }

    public static MirrorKind getMirrorKind(ItemStack stack) {
        if (stack.getItem() instanceof DimensionMirrorItem mirrorItem) {
            return mirrorItem.getMirrorKind();
        }
        return MirrorKind.DIMENSION;
    }

    public MirrorKind getMirrorKind() {
        return MirrorKind.fromSandboxMode(sandboxMode);
    }

    static int calculateCooldownSeconds(Level level, ItemStack stack) {
        int baseCooldownSeconds = MirrorConfig.getMirrorCooldownTicks() / 20; // Convert ticks to seconds
        int efficiencyLevel = getEfficiencyLevel(level, stack);

        // Each efficiency level reduces cooldown by 20%
        // Level 0: 100% (5 min), Level 1: 80% (4 min), Level 2: 60% (3 min)
        // Level 3: 40% (2 min), Level 4: 20% (1 min), Level 5: 10% (30 sec, minimum)
        double reduction = Math.min(0.9, efficiencyLevel * 0.2); // Cap at 90% reduction
        int finalCooldownSeconds = (int) (baseCooldownSeconds * (1.0 - reduction));

        // Minimum cooldown is 30 seconds
        return Math.max(30, finalCooldownSeconds);
    }

    /**
     * Send the current cooldown state to the client for HUD display
     */
    public static void syncCooldownToClient(ServerPlayer player) {
        UUID playerId = player.getUUID();
        Long cooldownEnd = COOLDOWNS.get(playerId);
        Long totalDuration = COOLDOWN_DURATIONS.get(playerId);
        long timestamp = (cooldownEnd != null) ? cooldownEnd : 0;
        long duration = (totalDuration != null) ? totalDuration : 0;
        PacketDistributor.sendToPlayer(player, new SyncCooldownPacket(timestamp, duration));
    }
    
    /**
     * Get the Efficiency enchantment level on the item
     */
    private static int getEfficiencyLevel(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        var registryAccess = level.registryAccess();
        var enchantmentRegistry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        var efficiencyHolder = enchantmentRegistry.getOrThrow(Enchantments.EFFICIENCY);
        return stack.getEnchantmentLevel(efficiencyHolder);
    }

    /**
     * Create an entry portal in the overworld
     */
    private InteractionResult createEntryPortal(ServerLevel level, ServerPlayer player, BlockPos pos,
                                                boolean persistentAccess) {
        // Check if player already has an active session
        if (MirrorWorldManager.hasActiveSession(player.getUUID())) {
            player.displayClientMessage(
                    Component.translatable("message.instantworldmirror.already_has_session"),
                    true
            );
            return InteractionResult.FAIL;
        }

        // Create a new session for this player
        // Note: createSession already displays specific error messages (already_has_session, no_dimensions_available)
        Optional<MirrorSession> sessionOpt = MirrorWorldManager.createSession(player, pos, sandboxMode, persistentAccess);
        if (sessionOpt.isEmpty()) {
            // Message already shown in createSession
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

        // Spawn return portal using the unified factory method
        // pos is the clicked solid block, factory method handles pos.above() internally
        MirrorPortalEntity.spawnReturnPortal(level, pos, player.getUUID(), false, null);

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
    public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
        return enchantment.is(Enchantments.EFFICIENCY) || ModEnchantments.isPermanence(enchantment);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return MirrorItemRenderer.getInstance();
            }
        });
    }
    
    // ==================== Hold to Teleport to Spawn (Mirror World Only) ====================
    
    // Hold duration in ticks to teleport to spawn (20 ticks = 1 second)
    private static final int HOLD_DURATION_TICKS = 20;
    
    /**
     * Called when player right-clicks in air (not on a block).
     * In mirror world: starts the hold-to-teleport interaction.
     * In other dimensions: does nothing (useOn handles block interactions).
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                PersistentMirrorManager.openMirrorMenu(serverPlayer, stack);
            }
            return InteractionResultHolder.consume(stack);
        }
        
        // Only enable holding interaction in mirror world
        if (ModDimensions.isAnyMirrorWorld(level.dimension())) {
            // Start using the item - this enables the hold-to-finish mechanism
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(stack);
        }
        
        return InteractionResultHolder.pass(stack);
    }
    
    /**
     * Duration the item can be used (held) before finishUsingItem is called.
     * Only applies in mirror world for teleport to spawn.
     */
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        if (entity instanceof Player player && ModDimensions.isAnyMirrorWorld(player.level().dimension())) {
            return HOLD_DURATION_TICKS;
        }
        return 0; // No duration in other dimensions
    }
    
    /**
     * Animation while holding.
     */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }
    
    /**
     * Called when the hold duration completes.
     * Sends packet to server to teleport player to mirror world spawn.
     */
    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof Player player && ModDimensions.isAnyMirrorWorld(level.dimension())) {
            // Send teleport request to server from client side
            if (level.isClientSide) {
                PacketDistributor.sendToServer(new com.crabmods.instantworldmirror.network.TeleportToSpawnPacket());
            }
        }
        return stack;
    }
}
