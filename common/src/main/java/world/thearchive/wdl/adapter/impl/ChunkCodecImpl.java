// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkBiomeContainer;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;

public final class ChunkCodecImpl implements ChunkCodec {
    private static final ShortList[] NO_POST_PROCESSING = new ShortList[0];

    // A 1.15.2 world is 0..256: block sections 0..15, with a light-only pad one section below and above.
    private static final int MIN_BLOCK_SECTION = 0;
    private static final int MAX_BLOCK_SECTION = 15;
    private static final int BIOME_QUART_LAYERS = 64;

    /**
     * Snapshot a live client chunk on the main thread (block-state section copies, per-chunk biomes, cloned
     * heightmaps), detached so none of them crosses live to the async IO worker.
     *
     * <p>A chunk qualifies only when it reports {@code isLightCorrect}; otherwise the snapshot carries no light and
     * reports {@code lightCorrect=false}, and vanilla relights that chunk on load.
     */
    @Override
    public ChunkSnapshotSource capture(LevelChunk chunk) {
        Level level = chunk.getLevel();
        ChunkPos pos = chunk.getPos();

        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        if (lightEngine.hasLightWork()) {
            lightEngine.runUpdates(Integer.MAX_VALUE, true, true);
        }
        boolean lightCorrect = chunk.isLightCorrect();

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = 0;
        List<ChunkSnapshotSource.SectionData> sectionData = new ArrayList<>(sections.length + 2);
        for (int sectionY = -1; sectionY < 17; sectionY++) {
            int index = sectionY;
            boolean inChunk = index >= 0 && index < sections.length;
            DataLayer blockLight = null;
            DataLayer skyLight = null;
            if (lightCorrect) {
                blockLight = copyNonEmpty(lightEngine.getLayerListener(LightLayer.BLOCK)
                        .getDataLayerData(SectionPos.of(pos, sectionY)));
                skyLight = copyNonEmpty(lightEngine.getLayerListener(LightLayer.SKY)
                        .getDataLayerData(SectionPos.of(pos, sectionY)));
            }
            // Below 1.18 an untransmitted section slot is null, so guard before copying.
            LevelChunkSection live = inChunk ? sections[index] : null;
            if (live != null || blockLight != null || skyLight != null) {
                sectionData.add(new ChunkSnapshotSource.SectionData(sectionY,
                        live != null ? copySection(sectionY, live) : null, blockLight, skyLight));
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

        int[] biomes = clampedBiomes(chunk);

        return new CapturedChunkSnapshot(pos, minSectionY, level.getGameTime(),
                chunk.getInhabitedTime(), chunk.getStatus(), lightCorrect,
                heightmaps, sectionData, blockEntities, biomes);
    }

    @Override
    public CompoundTag encode(ChunkSnapshotSource snapshot, boolean synthesizeBlending) {
        ChunkPos pos = snapshot.chunkPos();
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getWorldVersion());
        CompoundTag levelTag = new CompoundTag();
        tag.put("Level", levelTag);
        levelTag.putInt("xPos", pos.x);
        levelTag.putInt("zPos", pos.z);
        levelTag.putLong("LastUpdate", snapshot.gameTime());
        levelTag.putLong("InhabitedTime", snapshot.inhabitedTime());
        levelTag.putString("Status", snapshot.status().getName());

        ListTag sectionsTag = new ListTag();
        for (ChunkSnapshotSource.SectionData section : snapshot.sections()) {
            if (section.y() < MIN_BLOCK_SECTION - 1 || section.y() > MAX_BLOCK_SECTION + 1) {
                continue;
            }
            CompoundTag sectionTag = new CompoundTag();
            LevelChunkSection chunkSection = section.chunkSection();
            if (chunkSection != null && section.y() >= MIN_BLOCK_SECTION && section.y() <= MAX_BLOCK_SECTION) {
                chunkSection.getStates().write(sectionTag, "Palette", "BlockStates");
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
        levelTag.put("Sections", sectionsTag);
        levelTag.putIntArray("Biomes", snapshot.biomes());

        if (snapshot.lightCorrect()) {
            levelTag.putBoolean("isLightOn", true);
        }

        ListTag blockEntitiesTag = new ListTag();
        blockEntitiesTag.addAll(snapshot.blockEntities());
        levelTag.put("TileEntities", blockEntitiesTag);

        levelTag.put("TileTicks", new ListTag());
        levelTag.put("LiquidTicks", new ListTag());
        levelTag.put("PostProcessing", ChunkSerializer.packOffsets(NO_POST_PROCESSING));

        CompoundTag heightmapsTag = new CompoundTag();
        clientHeightmaps(snapshot)
                .forEach((type, data) -> heightmapsTag.put(type.getSerializationKey(), new LongArrayTag(data)));
        levelTag.put("Heightmaps", heightmapsTag);

        levelTag.put("Structures", emptyStructureData());
        return tag;
    }

    private static LevelChunkSection copySection(int sectionY, LevelChunkSection section) {
        LevelChunkSection copy = new LevelChunkSection(sectionY);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    copy.setBlockState(x, y, z, section.getBlockState(x, y, z), false);
                }
            }
        }
        return copy;
    }

    private static int[] clampedBiomes(LevelChunk chunk) {
        Registry<Biome> biomeRegistry = Registry.BIOME;
        ChunkBiomeContainer container = chunk.getBiomes();
        int[] biomes = new int[16 * BIOME_QUART_LAYERS];
        for (int quartY = 0; quartY < BIOME_QUART_LAYERS; quartY++) {
            for (int quartZ = 0; quartZ < 4; quartZ++) {
                for (int quartX = 0; quartX < 4; quartX++) {
                    biomes[quartY << 4 | quartZ << 2 | quartX] = biomeRegistry
                            .getId(container.getNoiseBiome(quartX, quartY, quartZ));
                }
            }
        }
        return biomes;
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
        structures.put("Starts", new CompoundTag());
        structures.put("References", new CompoundTag());
        return structures;
    }

    private static final class CapturedChunkSnapshot implements ChunkSnapshotSource {
        private final ChunkPos chunkPos;
        private final int minSectionY;
        private final long gameTime;
        private final long inhabitedTime;
        private final ChunkStatus status;
        private final boolean lightCorrect;
        private final Map<Heightmap.Types, long[]> heightmaps;
        private final List<ChunkSnapshotSource.SectionData> sections;
        private final List<CompoundTag> blockEntities;
        private final int[] biomes;

        CapturedChunkSnapshot(ChunkPos chunkPos, int minSectionY, long gameTime, long inhabitedTime,
                ChunkStatus status, boolean lightCorrect, Map<Heightmap.Types, long[]> heightmaps,
                List<ChunkSnapshotSource.SectionData> sections, List<CompoundTag> blockEntities, int[] biomes) {
            this.chunkPos = chunkPos;
            this.minSectionY = minSectionY;
            this.gameTime = gameTime;
            this.inhabitedTime = inhabitedTime;
            this.status = status;
            this.lightCorrect = lightCorrect;
            this.heightmaps = heightmaps;
            this.sections = sections;
            this.blockEntities = blockEntities;
            this.biomes = biomes;
        }

        @Override
        public ChunkPos chunkPos() {
            return chunkPos;
        }

        @Override
        public int minSectionY() {
            return minSectionY;
        }

        @Override
        public long gameTime() {
            return gameTime;
        }

        @Override
        public long inhabitedTime() {
            return inhabitedTime;
        }

        @Override
        public ChunkStatus status() {
            return status;
        }

        @Override
        public boolean lightCorrect() {
            return lightCorrect;
        }

        @Override
        public Map<Heightmap.Types, long[]> heightmaps() {
            return heightmaps;
        }

        @Override
        public List<ChunkSnapshotSource.SectionData> sections() {
            return sections;
        }

        @Override
        public List<CompoundTag> blockEntities() {
            return blockEntities;
        }

        @Override
        public int[] biomes() {
            return biomes;
        }
    }
}
