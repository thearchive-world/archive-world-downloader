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
import net.minecraft.core.RegistryAccess;
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

/**
 * 1.16.5 chunk codec: replicates the minimal client-safe slice of vanilla
 * {@code ChunkSerializer.write(ServerLevel, ChunkAccess)} to NBT. Below the 1.21.2 cut the chunk write is a static
 * method that reads from a {@code ServerLevel} a multiplayer client never has, so {@link #encode} rebuilds the tag
 * field by field from the captured snapshot rather than calling vanilla.
 *
 * <p>Two steps (see {@link ChunkCodec}): {@link #capture(LevelChunk, RegistryAccess)} captures a live client chunk into
 * a {@link ChunkSnapshotSource}, and {@link #encode(ChunkSnapshotSource, RegistryAccess, boolean)} encodes that
 * snapshot to NBT (pure, so the headless round-trip guards it).
 */
public final class ChunkCodecImpl implements ChunkCodec {
    /** Client chunks have no post-processing (a worldgen artifact). */
    private static final ShortList[] NO_POST_PROCESSING = new ShortList[0];

    // A 1.16.5 world is 0..256: block sections 0..15, with a light-only pad one section below and above. A taller
    // source (a 26.x server reached through a downgrade proxy) sends sections beyond that range; a section carrying
    // block data outside 0..15 would crash vanilla's unchecked sections[index] on load, so its block data is dropped
    // and the biome column is re-sliced to 0..256. Entities above or below are unaffected (a separate save axis).
    private static final int MIN_BLOCK_SECTION = 0;
    private static final int MAX_BLOCK_SECTION = 15;
    private static final int BIOME_QUART_LAYERS = 64;

    /**
     * Snapshot a live client chunk on the main thread (block-state section copies, per-chunk biomes, cloned heightmaps,
     * block-entity NBT), everything detached so only immutable data crosses to the async IO worker. The server-only
     * structure call is dropped here and reproduced as empty maps in
     * {@link #encode(ChunkSnapshotSource, RegistryAccess, boolean)}.
     *
     * <p>Light is read from the client light engine the way the server's own save path reads its engine: the padded
     * section range, keeping only non-empty layers. A chunk qualifies only when it reports its initial server light
     * applied ({@code isLightCorrect}); otherwise the snapshot carries no light and reports {@code lightCorrect=false},
     * and vanilla relights that chunk on load. Pending light work is drained first, effectively once per tick (later
     * calls find none): a block edit updates the section palette immediately while its relight waits for the render
     * pass, so an undrained read could freeze pre-edit light under {@code isLightOn=true}.
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
            // Below 1.18 an all-air or untransmitted section slot is null, so guard before copying.
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

        int[] biomes = clampedBiomes(chunk, registries);

        return new CapturedChunkSnapshot(pos, minSectionY, level.getGameTime(),
                chunk.getInhabitedTime(), chunk.getStatus(), lightCorrect,
                heightmaps, sectionData, blockEntities, biomes);
    }

    @Override
    public CompoundTag encode(ChunkSnapshotSource snapshot, RegistryAccess registries, boolean synthesizeBlending) {
        ChunkPos pos = snapshot.chunkPos();
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getWorldVersion());
        // Below 1.18 the chunk NBT nests everything under a Level compound; only DataVersion sits at the root.
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
                continue; // outside the 1.16.5 section column (block range plus the one-section light pad); dropped
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

        // A captured chunk is a FULL LevelChunk, never a ProtoChunk, so the entities/CarvingMasks branch is dropped.
        // Client chunks carry no scheduled ticks (those are server state), so both tick lists save empty.
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

    /**
     * A detached copy of a live section. Below 1.18 LevelChunkSection has no copy(), so the copy reconstructs a fresh
     * section and replays the block states into it; below 1.20 the constructor also takes the section y-index. Biomes
     * live per-chunk here, not on the section.
     */
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

    /**
     * The per-chunk biome ids for the 1.16.5 column, always the 0..256 grid a 1.16.5 save expects. A taller source's
     * container spans a wider height, so each cell is read through the container's world-Y-aware accessor and re-sliced
     * to the 1024-cell shape rather than writing the container's own longer array.
     */
    private static int[] clampedBiomes(LevelChunk chunk, RegistryAccess registries) {
        Registry<Biome> biomeRegistry = registries.registryOrThrow(Registry.BIOME_REGISTRY);
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
     * The structure tag for a client chunk: {@code {Starts:{}, References:{}}}, exactly what vanilla
     * {@code packStructureData} emits for empty start/reference maps (client chunks always carry empty ones). Built
     * directly so we never call {@code StructurePieceSerializationContext.fromLevel(serverLevel)}, which dereferences
     * {@code level.getServer()} and NPEs on a client.
     */
    private static CompoundTag emptyStructureData() {
        CompoundTag structures = new CompoundTag();
        structures.put("Starts", new CompoundTag());
        structures.put("References", new CompoundTag());
        return structures;
    }

    /** Immutable snapshot of a captured live chunk (only {@link CompoundTag}s and copies cross threads). */
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
