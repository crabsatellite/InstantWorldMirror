package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Creative Mode Tab Registry Class
 */
public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = 
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, InstantWorldMirror.MODID);

    // Mod creative tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MIRROR_TAB = CREATIVE_MODE_TABS.register(
            "mirror_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + InstantWorldMirror.MODID))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> ModItems.DIMENSION_MIRROR.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.DIMENSION_MIRROR.get());
                        output.accept(ModItems.HEAVEN_MIRROR.get());
                    })
                    .build()
    );
}
