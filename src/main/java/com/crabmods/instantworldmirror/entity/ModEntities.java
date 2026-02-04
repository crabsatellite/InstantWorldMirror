package com.crabmods.instantworldmirror.entity;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Entity Registry Class
 */
public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = 
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, InstantWorldMirror.MODID);

    // Mirror Portal Entity
    public static final RegistryObject<EntityType<MirrorPortalEntity>> MIRROR_PORTAL =
            ENTITY_TYPES.register("mirror_portal",
                    () -> EntityType.Builder.<MirrorPortalEntity>of(MirrorPortalEntity::new, MobCategory.MISC)
                            .sized(0.8F, 1.5F) // Mirror size
                            .clientTrackingRange(10)
                            .updateInterval(1)
                            .fireImmune()
                            .build(InstantWorldMirror.MODID + ":mirror_portal")
            );
}
