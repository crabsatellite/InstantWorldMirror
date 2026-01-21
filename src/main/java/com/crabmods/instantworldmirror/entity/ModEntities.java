package com.crabmods.instantworldmirror.entity;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entity Registry Class
 */
public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = 
            DeferredRegister.create(Registries.ENTITY_TYPE, InstantWorldMirror.MODID);

    // Mirror Portal Entity
    public static final DeferredHolder<EntityType<?>, EntityType<MirrorPortalEntity>> MIRROR_PORTAL =
            ENTITY_TYPES.register("mirror_portal",
                    () -> EntityType.Builder.<MirrorPortalEntity>of(MirrorPortalEntity::new, MobCategory.MISC)
                            .sized(0.8F, 1.5F) // Mirror size
                            .clientTrackingRange(10)
                            .updateInterval(1)
                            .fireImmune()
                            .build(InstantWorldMirror.MODID + ":mirror_portal")
            );
}
