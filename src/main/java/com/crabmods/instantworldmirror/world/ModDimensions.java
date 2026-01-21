package com.crabmods.instantworldmirror.world;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Dimension Registry Class
 */
public class ModDimensions {
    
    // Mirror World Dimension
    public static final ResourceKey<Level> MIRROR_WORLD = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "mirror_world")
    );
    
    // Mirror World Dimension Type
    public static final ResourceKey<DimensionType> MIRROR_WORLD_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, "mirror_world")
    );
}
