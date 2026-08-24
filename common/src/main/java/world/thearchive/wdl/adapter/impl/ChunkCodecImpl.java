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
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;

/**
 * 1.21.3 chunk codec: replicates the minimal client-safe slice of vanilla
 * {@code SerializableChunkData.copyOf(ServerLevel, ChunkAccess)} to {@code write()}.
 *
 * <p>Two steps (see {@link ChunkCodec}): {@link #capture(LevelChunk, RegistryAccess)} captures a live client chunk into
 * a {@link ChunkSnapshotSource}, and {@link #encode(ChunkSnapshotSource, RegistryAccess, boolean)} encodes that
 * snapshot to NBT (pure, so the headless round-trip guards it).
 */
public final class ChunkCodecImpl implements ChunkCodec {
    /** Client chunks have no scheduled ticks (those are server state). */
    private static final ChunkAccess.PackedTicks NO_TICKS = new ChunkAccess.PackedTicks(List.of(), List.of());

    /** Client chunks have no post-processing (a worldgen artifact). */
    private static final ShortList[] NO_POST_PROCESSING = new ShortList[0];

    /** Vanilla full-height overworld: min block -64, height 384; the range BlendingDataFix marks as old generation. */
    private static final int OVERWORLD_MIN_Y = -64;
    private static final int OVERWORLD_HEIGHT = 384;

    // The blending_data MC's BlendingDataFix would inject for the overworld: the section bounds marking this chunk
    // as old generation so a freshly generated neighbor blends against it instead of forming a boundary wall.
    // Heights are left empty; MC fills them lazily on the first neighbor-generation call.
    private static final BlendingData.Packed OVERWORLD_BLENDING = new BlendingData.Packed(
            SectionPos.blockToSectionCoord(OVERWORLD_MIN_Y),
            SectionPos.blockToSectionCoord(OVERWORLD_MIN_Y + OVERWORLD_HEIGHT), Optional.empty());

    /**
     * Snapshot a live client chunk on the main thread (block-state/biome section copies, cloned heightmaps,
     * block-entity NBT), everything detached so only immutable data crosses to the async IO worker. The server-only
     * structure call is dropped here and reproduced as empty maps in
     * {@link #encode(ChunkSnapshotSource, RegistryAccess, boolean)}.
     *
     * <p>Light is read from the client light engine the way the server's own save path reads its engine (the
     * {@code SerializableChunkData.copyOf} slice): the padded section range, keeping only non-empty layers. A chunk
     * qualifies only when the engine reports its initial server light applied ({@code lightOnInColumn}); otherwise the
     * snapshot carries no light and reports {@code lightCorrect=false}, and vanilla relights that chunk on load.
     * Pending light work is drained first, effectively once per tick (later calls find none): a block edit updates the
     * section palette immediately while its relight waits for the render pass, so an undrained read could freeze
     * pre-edit light under {@code isLightOn=true}.
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
        Registry<Biome> biomeRegistry = registries.lookupOrThrow(Registries.BIOME);

        // NeoForge marks the vanilla canonical SerializableChunkData constructor deprecated in favor of a
        // 20-argument attachment-aware overload that does not exist in vanilla. This captures vanilla client-chunk
        // data only, so the canonical constructor is the correct call; vanilla and Fabric do not deprecate
        // it, leaving the suppression inert there.
        @SuppressWarnings("deprecation")
        SerializableChunkData data = new SerializableChunkData(
                biomeRegistry,
                snapshot.chunkPos(),
                snapshot.minSectionY(),
                snapshot.gameTime(),                 // LastUpdate
                snapshot.inhabitedTime(),
                snapshot.status(),
                synthesizeBlending ? OVERWORLD_BLENDING : null, // blendingData: synthesized for a blended overworld
                null,                                 // belowZeroRetrogen: none
                UpgradeData.EMPTY,                    // upgradeData: none
                null,                                 // carvingMask: LEVELCHUNK type, none
                clientHeightmaps(snapshot),
                NO_TICKS,
                NO_POST_PROCESSING,
                snapshot.lightCorrect(),
                snapshot.sections(),
                List.of(),                            // entities: saved to the entities/ region, not here
                snapshot.blockEntities(),
                emptyStructureData());                // structures: empty starts/references, no server call
        return data.write();
    }

    /** A detached copy of a stored layer, or null when the engine holds none (empty layers are omitted). */
    private static @Nullable DataLayer copyNonEmpty(@Nullable DataLayer layer) {
        return layer != null && !layer.isEmpty() ? layer.copy() : null;
    }

    /**
     * Heightmaps to persist: those the client actually received ({@code sendToClient()}) and relevant to the chunk's
     * status ({@code heightmapsAfter()}). For a FULL chunk this keeps the three CLIENT-usage maps and drops
     * {@code OCEAN_FLOOR}, which the {@code LevelChunk} ctor pre-creates but the server never transmits (it filters the
     * chunk packet by {@code sendToClient()}), leaving it present-but-zeroed on the client. Omitting it makes vanilla
     * {@code read()} re-prime it on load rather than trust bad data. {@code sendToClient()} is exactly vanilla's own
     * "what the client got" predicate, so it tracks correctly even if the heightmap set changes across versions.
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

    /**
     * The structure tag for a client chunk: {@code {starts:{}, References:{}}}, exactly what vanilla
     * {@code packStructureData} emits for empty start/reference maps (client chunks always carry empty ones). Built
     * directly so we never call {@code StructurePieceSerializationContext.fromLevel(serverLevel)}, which dereferences
     * {@code level.getServer()} and NPEs on a client.
     */
    private static CompoundTag emptyStructureData() {
        CompoundTag structures = new CompoundTag();
        structures.put("starts", new CompoundTag());
        structures.put("References", new CompoundTag());
        return structures;
    }

    /** Immutable snapshot of a captured live chunk (only {@link CompoundTag}s and copies cross threads). */
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
