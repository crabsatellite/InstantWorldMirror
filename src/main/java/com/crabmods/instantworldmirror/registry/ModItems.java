package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.item.HeavenMirrorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item Registry Class
 */
public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(InstantWorldMirror.MODID);

    // Dimensional Mirror
    public static final DeferredItem<DimensionMirrorItem> DIMENSION_MIRROR = ITEMS.register(
            "dimension_mirror",
            () -> new DimensionMirrorItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
            )
    );

    public static final DeferredItem<HeavenMirrorItem> HEAVEN_MIRROR = ITEMS.register(
            "heaven_mirror",
            () -> new HeavenMirrorItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
            )
    );
}
