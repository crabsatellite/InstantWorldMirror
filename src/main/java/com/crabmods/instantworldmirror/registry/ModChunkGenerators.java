package com.crabmods.instantworldmirror.registry;

import com.crabmods.instantworldmirror.InstantWorldMirror;
import com.crabmods.instantworldmirror.world.gen.MirrorChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Chunk Generator Registry Class
 */
public class ModChunkGenerators {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS = 
            DeferredRegister.create(Registries.CHUNK_GENERATOR, InstantWorldMirror.MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<MirrorChunkGenerator>> MIRROR_CHUNK_GENERATOR =
            CHUNK_GENERATORS.register("mirror_chunk_generator", () -> MirrorChunkGenerator.CODEC);
}
