package com.crabmods.instantworldmirror.item;

import com.crabmods.instantworldmirror.world.MirrorKind;
import net.minecraft.world.item.Item;

/**
 * First Dream Mirror - creates a sandbox mirror from pristine generated terrain.
 */
public class FirstDreamMirrorItem extends DimensionMirrorItem {
    public FirstDreamMirrorItem(Item.Properties properties) {
        super(properties, MirrorKind.FIRST_DREAM);
    }
}
