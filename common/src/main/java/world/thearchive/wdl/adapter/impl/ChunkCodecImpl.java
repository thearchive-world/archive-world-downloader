// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.world.chunk.storage.class_1205;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;

/**
 * 1.13.2 chunk codec: replicates the client-safe slice of vanilla {@code class_1205} (the anvil chunk loader) chunk
 * write to NBT. Vanilla's write reads from a {@code ServerLevel} a multiplayer client never has, so {@link #encode}
 * rebuilds the tag field by field from the captured snapshot. At this band light is section-resident (each
 * {@link LevelChunkSection} carries its own block and sky {@link DataLayer}), there being no light engine yet, so the
 * capture reads light straight off the sections rather than from an engine.
 *
 * <p>Two steps (see {@link ChunkCodec}): {@link #capture(LevelChunk)} captures a live client chunk into a
 * {@link ChunkSnapshotSource}, and {@link #encode(ChunkSnapshotSource, boolean)} encodes that snapshot to NBT (pure, so
 * the headless round-trip guards it).
 */
public final class ChunkCodecImpl implements ChunkCodec {
    /** Client chunks have no post-processing (a worldgen artifact). */
    private static final ShortList[] NO_POST_PROCESSING = new ShortList[0];

    // The 1.13.2 chunk NBT version. Vanilla class_1205 stamps this literal (there is no SharedConstants version
    // accessor at this band), and it is what a 1.13.2 client reads back as the chunk DataVersion.
    private static final int CHUNK_DATA_VERSION = 1631;

    private static final int SECTION_LAYER_BYTES = 2048;

    // The 1.13.2 block-section column is 0..15. A client only ever holds sections in that range, so a section index
    // outside it cannot occur; the encode bound is a defensive guard on the pure surface.
    private static final int MIN_BLOCK_SECTION = 0;
    private static final int MAX_BLOCK_SECTION = 15;

    /**
     * Snapshot a live client chunk on the main thread (block-state section copies, per-chunk biomes, cloned heightmaps,
     * block-entity NBT), everything detached so only immutable data crosses to the async IO worker. The server-only
     * structure call is dropped here and reproduced as empty maps in {@link #encode(ChunkSnapshotSource, boolean)}.
     *
     * <p>Light is read off each non-empty section's own layers, the way vanilla's save reads them: the block layer is
     * always present, and the sky layer only in a dimension that has sky light. A copy is taken so nothing live crosses
     * to the writer thread.
     *
     * <p>Below 1.15 a block entity's items serialize through vanilla {@code ItemStack.save}, which puts the live
     * stack's own {@code tag} compound into its output, so each block-entity tag is detached before the snapshot
     * carries it: the snapshot is encoded on the writer thread, and the client keeps nothing it could reach.
     */
    @Override
    public ChunkSnapshotSource capture(LevelChunk chunk) {
        Level level = chunk.getLevel();
        ChunkPos pos = chunk.getPos();
        boolean hasSkyLight = level.getDimension().isHasSkyLight();

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = 0;
        List<ChunkSnapshotSource.SectionData> sectionData = new ArrayList<>(sections.length);
        for (int sectionY = 0; sectionY < sections.length; sectionY++) {
            // A null slot is an all-air, untransmitted section; vanilla saves only the non-null ones.
            LevelChunkSection live = sections[sectionY];
            if (live == null) {
                continue;
            }
            DataLayer blockLight = copyLayer(live.method_3946());
            DataLayer skyLight = hasSkyLight ? copyLayer(live.method_3947()) : null;
            sectionData.add(new ChunkSnapshotSource.SectionData(sectionY,
                    copySection(sectionY, hasSkyLight, live), blockLight, skyLight));
        }

        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Heightmap.Types type : chunk.method_17063()) {
            heightmaps.put(type, chunk.method_17079(type).getRawData().clone());
        }

        List<CompoundTag> blockEntities = new ArrayList<>();
        for (BlockPos blockEntityPos : chunk.getBlockEntitiesPos()) {
            CompoundTag tag = blockEntityNbtForSaving(chunk, blockEntityPos);
            if (tag != null) {
                blockEntities.add(tag.copy());
            }
        }

        int[] biomes = columnBiomes(chunk);

        return new CapturedChunkSnapshot(pos, minSectionY, level.getGameTime(),
                chunk.getInhabitedTime(), chunk.getStatus(), true,
                heightmaps, sectionData, blockEntities, biomes);
    }

    @Override
    public CompoundTag encode(ChunkSnapshotSource snapshot, boolean synthesizeBlending) {
        ChunkPos pos = snapshot.chunkPos();
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", CHUNK_DATA_VERSION);
        // The 1.13.2 chunk NBT nests everything under a Level compound; only DataVersion sits at the root.
        CompoundTag levelTag = new CompoundTag();
        tag.put("Level", levelTag);
        levelTag.putInt("xPos", pos.x);
        levelTag.putInt("zPos", pos.z);
        levelTag.putLong("LastUpdate", snapshot.gameTime());
        levelTag.putLong("InhabitedTime", snapshot.inhabitedTime());
        levelTag.putString("Status", snapshot.status().method_17052());

        ListTag sectionsTag = new ListTag();
        for (ChunkSnapshotSource.SectionData section : snapshot.sections()) {
            LevelChunkSection chunkSection = section.chunkSection();
            if (chunkSection == null || section.y() < MIN_BLOCK_SECTION || section.y() > MAX_BLOCK_SECTION) {
                continue;
            }
            CompoundTag sectionTag = new CompoundTag();
            sectionTag.putByte("Y", (byte) section.y());
            chunkSection.getStates().write(sectionTag, "Palette", "BlockStates");
            // Every non-empty section at this band carries both light arrays: the loader reads them straight into a
            // DataLayer, which throws unless the byte array is exactly 2048 long, so an omitted or empty layer would
            // crash the chunk on load. Sky light is zero-filled in a dimension without sky light, matching vanilla.
            sectionTag.putByteArray("BlockLight", layerBytes(section.blockLight()));
            sectionTag.putByteArray("SkyLight", layerBytes(section.skyLight()));
            sectionsTag.add(sectionTag);
        }
        levelTag.put("Sections", sectionsTag);
        levelTag.putIntArray("Biomes", snapshot.biomes());

        ListTag blockEntitiesTag = new ListTag();
        blockEntitiesTag.addAll(snapshot.blockEntities());
        levelTag.put("TileEntities", blockEntitiesTag);

        // A captured chunk is a FULL LevelChunk, never a ProtoChunk, so the entities/CarvingMasks branch is dropped.
        // Client chunks carry no scheduled ticks (those are server state), so both tick lists save empty.
        levelTag.put("TileTicks", new ListTag());
        levelTag.put("LiquidTicks", new ListTag());
        levelTag.put("PostProcessing", class_1205.method_17180(NO_POST_PROCESSING));

        CompoundTag heightmapsTag = new CompoundTag();
        snapshot.heightmaps().forEach((type, data) -> {
            if (type.method_17251() == Heightmap.Usage.LIVE_WORLD) {
                heightmapsTag.put(type.getSerializationKey(), new LongArrayTag(data));
            }
        });
        levelTag.put("Heightmaps", heightmapsTag);

        levelTag.put("Structures", emptyStructureData());
        return tag;
    }

    /**
     * A detached copy of a live section. At this band {@link LevelChunkSection} has no copy(), so the copy reconstructs
     * a fresh section (its constructor takes the section's bottom block y and whether the dimension has sky light) and
     * replays the block states into it. Biomes live per-chunk here, not on the section, and light is carried
     * separately, so only the block states are replayed.
     */
    private static LevelChunkSection copySection(int sectionY, boolean hasSkyLight, LevelChunkSection section) {
        LevelChunkSection copy = new LevelChunkSection(sectionY << 4, hasSkyLight);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    copy.setBlockState(x, y, z, section.getBlockState(x, y, z));
                }
            }
        }
        return copy;
    }

    /**
     * The per-chunk biome ids for the 1.13.2 column: the flat 16x16 per-column grid a 1.13.2 save expects, one id per
     * column indexed z*16+x, the {@code Biome[]} the client chunk holds mapped through the biome registry. Matches
     * vanilla {@code class_1205}, which stores {@code Registry.BIOME.getId} of each column under the int array
     * {@code "Biomes"} key a 1.13.2 client reads back.
     */
    private static int[] columnBiomes(LevelChunk chunk) {
        Biome[] biomes = chunk.getBiomes();
        int[] ids = new int[biomes.length];
        for (int i = 0; i < biomes.length; i++) {
            ids[i] = Registry.BIOME.getId(biomes[i]);
        }
        return ids;
    }

    /** The block-entity NBT in saved form, live or pending, or null when there is none, matching vanilla's save. */
    private static @Nullable CompoundTag blockEntityNbtForSaving(LevelChunk chunk, BlockPos pos) {
        BlockEntity blockEntity = chunk.getBlockEntity(pos);
        if (blockEntity != null) {
            CompoundTag tag = new CompoundTag();
            blockEntity.save(tag);
            tag.putBoolean("keepPacked", false);
            return tag;
        }
        CompoundTag pending = chunk.getBlockEntityNbt(pos);
        if (pending == null) {
            return null;
        }
        CompoundTag tag = pending.copy();
        tag.putBoolean("keepPacked", true);
        return tag;
    }

    /** A detached copy of a stored light layer. */
    private static DataLayer copyLayer(DataLayer layer) {
        return new DataLayer(layer.getData().clone());
    }

    /** The layer's 2048-byte array, or a zero-filled one when the layer is absent (an unlit column). */
    private static byte[] layerBytes(@Nullable DataLayer layer) {
        return layer != null ? layer.getData() : new byte[SECTION_LAYER_BYTES];
    }

    /**
     * The structure tag for a client chunk: {@code {Starts:{}, References:{}}}, exactly what vanilla emits for empty
     * start/reference maps (client chunks always carry empty ones). Built directly so we never dereference a server.
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
