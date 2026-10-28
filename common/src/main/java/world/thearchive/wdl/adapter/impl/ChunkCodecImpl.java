// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;

public final class ChunkCodecImpl implements ChunkCodec {
    private static final ShortList[] NO_POST_PROCESSING = new ShortList[0];

    /**
     * Vanilla full-height overworld: min block -64, height 384; the range {@code BlendingDataFix} marks as old
     * generation.
     */
    private static final int OVERWORLD_MIN_Y = -64;
    private static final int OVERWORLD_HEIGHT = 384;

    /**
     * Snapshot a live client chunk on the main thread (block-state/biome section copies, cloned heightmaps), detached
     * so none of them crosses live to the async IO worker.
     *
     * <p>A chunk qualifies only when it reports {@code isLightCorrect}; otherwise the snapshot carries no light and
     * reports {@code lightCorrect=false}, and vanilla relights that chunk on load.
     */
    @Override
    public ChunkSnapshotSource capture(LevelChunk chunk, RegistryAccess registries) {
        Level level = chunk.getLevel();
        ChunkPos pos = chunk.getPos();

        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        if (lightEngine.hasLightWork()) {
            lightEngine.runUpdates(Integer.MAX_VALUE, true, true);
        }
        boolean lightCorrect = chunk.isLightCorrect();

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSection();
        List<ChunkSnapshotSource.SectionData> sectionData = new ArrayList<>(sections.length + 2);
        for (int sectionY = lightEngine.getMinLightSection(); sectionY < lightEngine.getMaxLightSection(); sectionY++) {
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            boolean inChunk = index >= 0 && index < sections.length;
            DataLayer blockLight = null;
            DataLayer skyLight = null;
            if (lightCorrect) {
                blockLight = copyNonEmpty(lightEngine.getLayerListener(LightLayer.BLOCK)
                        .getDataLayerData(SectionPos.of(pos, sectionY)));
                skyLight = copyNonEmpty(lightEngine.getLayerListener(LightLayer.SKY)
                        .getDataLayerData(SectionPos.of(pos, sectionY)));
            }
            if (inChunk || blockLight != null || skyLight != null) {
                sectionData.add(new ChunkSnapshotSource.SectionData(sectionY,
                        inChunk ? copySection(sectionY, sections[index]) : null, blockLight, skyLight));
            }
        }

        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
        }

        List<CompoundTag> blockEntities = new ArrayList<>();
        for (BlockPos blockEntityPos : chunk.getBlockEntitiesPos()) {
            CompoundTag tag = chunk.getBlockEntityNbtForSaving(blockEntityPos);
            if (tag != null) {
                blockEntities.add(tag);
            }
        }

        return new CapturedChunkSnapshot(pos, minSectionY, level.getGameTime(),
                chunk.getInhabitedTime(), chunk.getStatus(), lightCorrect,
                heightmaps, sectionData, blockEntities);
    }

    @Override
    public CompoundTag encode(ChunkSnapshotSource snapshot, RegistryAccess registries, boolean synthesizeBlending) {
        Registry<Biome> biomeRegistry = registries.registryOrThrow(Registries.BIOME);
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
        // Built per encode, not as a static field: a static initializer here would touch the block registry at class
        // load, before the headless test harness bootstraps the registries.
        Codec<PalettedContainer<BlockState>> blockStateCodec = PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState());

        ChunkPos pos = snapshot.chunkPos();
        CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
        tag.putInt("xPos", pos.x);
        tag.putInt("yPos", snapshot.minSectionY());
        tag.putInt("zPos", pos.z);
        tag.putLong("LastUpdate", snapshot.gameTime());
        tag.putLong("InhabitedTime", snapshot.inhabitedTime());
        tag.putString("Status", BuiltInRegistries.CHUNK_STATUS.getKey(snapshot.status()).toString());

        if (synthesizeBlending) {
            // The blending_data MC's BlendingDataFix would inject for the overworld: the section bounds marking this
            // chunk as old generation so a freshly generated neighbor blends against it instead of forming a boundary
            // wall.
            CompoundTag blending = new CompoundTag();
            blending.putInt("min_section", SectionPos.blockToSectionCoord(OVERWORLD_MIN_Y));
            blending.putInt("max_section", SectionPos.blockToSectionCoord(OVERWORLD_MIN_Y + OVERWORLD_HEIGHT));
            tag.put("blending_data", blending);
        }

        ListTag sectionsTag = new ListTag();
        for (ChunkSnapshotSource.SectionData section : snapshot.sections()) {
            CompoundTag sectionTag = new CompoundTag();
            LevelChunkSection chunkSection = section.chunkSection();
            if (chunkSection != null) {
                sectionTag.put("block_states",
                        blockStateCodec.encodeStart(NbtOps.INSTANCE, chunkSection.getStates()).getOrThrow(false,
                                s -> {}));
                sectionTag.put("biomes",
                        biomeCodec.encodeStart(NbtOps.INSTANCE, chunkSection.getBiomes()).getOrThrow(false, s -> {}));
            }
            DataLayer blockLight = section.blockLight();
            if (blockLight != null && !blockLight.isEmpty()) {
                sectionTag.putByteArray("BlockLight", blockLight.getData());
            }
            DataLayer skyLight = section.skyLight();
            if (skyLight != null && !skyLight.isEmpty()) {
                sectionTag.putByteArray("SkyLight", skyLight.getData());
            }
            if (!sectionTag.isEmpty()) {
                sectionTag.putByte("Y", (byte) section.y());
                sectionsTag.add(sectionTag);
            }
        }
        tag.put("sections", sectionsTag);

        if (snapshot.lightCorrect()) {
            tag.putBoolean("isLightOn", true);
        }

        ListTag blockEntitiesTag = new ListTag();
        blockEntitiesTag.addAll(snapshot.blockEntities());
        tag.put("block_entities", blockEntitiesTag);

        tag.put("block_ticks", new ListTag());
        tag.put("fluid_ticks", new ListTag());
        tag.put("PostProcessing", ChunkSerializer.packOffsets(NO_POST_PROCESSING));

        CompoundTag heightmapsTag = new CompoundTag();
        clientHeightmaps(snapshot)
                .forEach((type, data) -> heightmapsTag.put(type.getSerializationKey(), new LongArrayTag(data)));
        tag.put("Heightmaps", heightmapsTag);

        tag.put("structures", emptyStructureData());
        return tag;
    }

    /**
     * Post-capture edits mutate the block states, so those are copied outright.
     */
    @SuppressWarnings("unchecked")
    private static LevelChunkSection copySection(int sectionY, LevelChunkSection section) {
        PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
        return new LevelChunkSection(sectionY, section.getStates().copy(), biomes.copy());
    }

    private static @Nullable DataLayer copyNonEmpty(@Nullable DataLayer layer) {
        return layer != null && !layer.isEmpty() ? layer.copy() : null;
    }

    /**
     * For a FULL chunk this drops {@code OCEAN_FLOOR}, which the {@code LevelChunk} ctor pre-creates but the server
     * never transmits (it filters the chunk packet by {@code sendToClient()}), leaving it present-but-zeroed on the
     * client. Omitting it makes vanilla {@code read()} re-prime it on load rather than trust bad data.
     */
    private static Map<Heightmap.Types, long[]> clientHeightmaps(ChunkSnapshotSource snapshot) {
        Set<Heightmap.Types> persistable = snapshot.status().heightmapsAfter();
        Map<Heightmap.Types, long[]> kept = new EnumMap<>(Heightmap.Types.class);
        snapshot.heightmaps().forEach((type, data) -> {
            if (type.sendToClient() && persistable.contains(type)) {
                kept.put(type, data);
            }
        });
        return kept;
    }

    private static CompoundTag emptyStructureData() {
        CompoundTag structures = new CompoundTag();
        structures.put("starts", new CompoundTag());
        structures.put("References", new CompoundTag());
        return structures;
    }

    private record CapturedChunkSnapshot(
            ChunkPos chunkPos,
            int minSectionY,
            long gameTime,
            long inhabitedTime,
            ChunkStatus status,
            boolean lightCorrect,
            Map<Heightmap.Types, long[]> heightmaps,
            List<ChunkSnapshotSource.SectionData> sections,
            List<CompoundTag> blockEntities) implements ChunkSnapshotSource {}
}
