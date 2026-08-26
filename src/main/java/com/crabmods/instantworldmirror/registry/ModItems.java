package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.item.DimensionMirrorItem;
import com.crabmods.instantworldmirror.item.FirstDreamMirrorItem;
import com.crabmods.instantworldmirror.item.HeavenMirrorItem;
import com.crabmods.instantworldmirror.item.StrandedMirrorItem;
import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Item Registry Class
 */
public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, InstantWorldMirror.MODID);

    // World Reflection Mirror
    public static final RegistryObject<DimensionMirrorItem> DIMENSION_MIRROR = ITEMS.register(
            "dimension_mirror",
            () -> new DimensionMirrorItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
            )
    );

    public static final RegistryObject<HeavenMirrorItem> HEAVEN_MIRROR = ITEMS.register(
            "heaven_mirror",
            () -> new HeavenMirrorItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
            )
    );

    public static final RegistryObject<FirstDreamMirrorItem> FIRST_DREAM_MIRROR = ITEMS.register(
            "first_dream_mirror",
            () -> new FirstDreamMirrorItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
            )
    );

    public static final RegistryObject<StrandedMirrorItem> STRANDED_MIRROR = ITEMS.register(
            "stranded_mirror",
            () -> new StrandedMirrorItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
            )
    );

    public static Item mirrorItem(MirrorKind kind) {
        return switch (kind) {
            case HEAVEN -> HEAVEN_MIRROR.get();
            case FIRST_DREAM -> FIRST_DREAM_MIRROR.get();
            case STRANDED -> STRANDED_MIRROR.get();
            case DIMENSION -> DIMENSION_MIRROR.get();
        };
    }
}
