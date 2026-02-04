package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.world.gen.MirrorChunkGenerator;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Chunk Generator Registry Class
 */
public class ModChunkGenerators {
    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS = 
            DeferredRegister.create(Registries.CHUNK_GENERATOR, InstantWorldMirror.MODID);

    public static final RegistryObject<Codec<MirrorChunkGenerator>> MIRROR_CHUNK_GENERATOR =
            CHUNK_GENERATORS.register("mirror_chunk_generator", () -> MirrorChunkGenerator.CODEC);
}
