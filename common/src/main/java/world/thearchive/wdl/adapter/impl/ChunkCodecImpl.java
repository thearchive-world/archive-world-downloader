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

/**
 * 1.20.4 chunk codec: replicates the minimal client-safe slice of vanilla
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

    /** Vanilla full-height overworld: min block -64, height 384; the range BlendingDataFix marks as old generation. */
    private static final int OVERWORLD_MIN_Y = -64;
    private static final int OVERWORLD_HEIGHT = 384;

    /**
     * Snapshot a live client chunk on the main thread (block-state/biome section copies, cloned heightmaps,
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
            lightEngine.runLightUpdates();
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
                        inChunk ? copySection(sections[index]) : null, blockLight, skyLight));
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
            // wall. Heights are omitted; MC fills them lazily on the first neighbor-generation call. Written directly
            // as the tag BlendingData.CODEC produces, since below 1.21.2 there is no BlendingData.Packed carrier.
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

        // A captured chunk is a FULL LevelChunk, never a ProtoChunk, so the entities/CarvingMasks branch is dropped.
        // Client chunks carry no scheduled ticks (those are server state), so both tick lists save empty.
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
     * A detached copy of a live section. Below 1.21.2 LevelChunkSection has no copy() and its returned biome view has
     * no copy() either, but a live client section always backs its biomes with a mutable PalettedContainer, so the copy
     * goes through that concrete type. Post-capture edits mutate the block states, so those are copied outright.
     */
    @SuppressWarnings("unchecked")
    private static LevelChunkSection copySection(LevelChunkSection section) {
        PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
        return new LevelChunkSection(section.getStates().copy(), biomes.copy());
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
            List<ChunkSnapshotSource.SectionData> sections,
            List<CompoundTag> blockEntities) implements ChunkSnapshotSource {}
}
