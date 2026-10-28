// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;

public final class ChunkCodecImpl implements ChunkCodec {
    private static final ChunkAccess.PackedTicks NO_TICKS = new ChunkAccess.PackedTicks(List.of(), List.of());

    private static final ShortList[] NO_POST_PROCESSING = new ShortList[0];

    /**
     * Vanilla full-height overworld: min block -64, height 384; the range {@code BlendingDataFix} marks as old
     * generation.
     */
    private static final int OVERWORLD_MIN_Y = -64;
    private static final int OVERWORLD_HEIGHT = 384;

    // The blending_data MC's BlendingDataFix would inject for the overworld: the section bounds marking this chunk as
    // old generation so a freshly generated neighbor blends against it instead of forming a boundary wall.
    private static final BlendingData.Packed OVERWORLD_BLENDING = new BlendingData.Packed(
            SectionPos.blockToSectionCoord(OVERWORLD_MIN_Y),
            SectionPos.blockToSectionCoord(OVERWORLD_MIN_Y + OVERWORLD_HEIGHT), Optional.empty());

    /**
     * Snapshot a live client chunk on the main thread (block-state/biome section copies, cloned heightmaps), detached
     * so none of them crosses live to the async IO worker.
     *
     * <p>A chunk qualifies only when the engine reports its initial server light applied ({@code lightOnInColumn});
     * otherwise the snapshot carries no light and reports {@code lightCorrect=false}, and vanilla relights that chunk
     * on load. Pending light work is drained first: a block edit updates the section palette immediately while its
     * relight waits, so an undrained read could freeze pre-edit light under {@code isLightOn=true}.
     */
    @Override
    public ChunkSnapshotSource capture(LevelChunk chunk, RegistryAccess registries) {
        Level level = chunk.getLevel();
        ChunkPos pos = chunk.getPos();

        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        if (lightEngine.hasLightWork()) {
            lightEngine.runLightUpdates();
        }
        boolean lightCorrect = lightEngine.lightOnInColumn(SectionPos.getZeroNode(pos.x, pos.z));

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSectionY();
        List<SerializableChunkData.SectionData> sectionData = new ArrayList<>(sections.length + 2);
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
                sectionData.add(new SerializableChunkData.SectionData(sectionY,
                        inChunk ? sections[index].copy() : null, blockLight, skyLight));
            }
        }

        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
        }

        List<CompoundTag> blockEntities = new ArrayList<>();
        for (BlockPos blockEntityPos : chunk.getBlockEntitiesPos()) {
            CompoundTag tag = chunk.getBlockEntityNbtForSaving(blockEntityPos, registries);
            if (tag != null) {
                blockEntities.add(tag);
            }
        }

        return new CapturedChunkSnapshot(pos, minSectionY, level.getGameTime(),
                chunk.getInhabitedTime(), chunk.getPersistedStatus(), lightCorrect,
                heightmaps, sectionData, blockEntities);
    }

    @Override
    public CompoundTag encode(ChunkSnapshotSource snapshot, RegistryAccess registries, boolean synthesizeBlending) {
        PalettedContainerFactory containerFactory = PalettedContainerFactory.create(registries);

        @SuppressWarnings("deprecation")
        SerializableChunkData data = new SerializableChunkData(
                containerFactory,
                snapshot.chunkPos(),
                snapshot.minSectionY(),
                snapshot.gameTime(),
                snapshot.inhabitedTime(),
                snapshot.status(),
                synthesizeBlending ? OVERWORLD_BLENDING : null,
                null,
                UpgradeData.EMPTY,
                null,
                clientHeightmaps(snapshot),
                NO_TICKS,
                NO_POST_PROCESSING,
                snapshot.lightCorrect(),
                snapshot.sections(),
                List.of(),
                snapshot.blockEntities(),
                emptyStructureData());
        return data.write();
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
            List<SerializableChunkData.SectionData> sections,
            List<CompoundTag> blockEntities) implements ChunkSnapshotSource {}
}
