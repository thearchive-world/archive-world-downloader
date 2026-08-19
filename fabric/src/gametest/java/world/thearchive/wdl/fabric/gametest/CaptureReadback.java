// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/**
 * Reads a produced WDL save back off disk, the on-disk side of the capture-verification suite. The suite drives a real
 * capture to a real save, then asserts targeted fields here: chunk palettes, block-entity contents, entity-region tags,
 * level.dat, and the data/ map files. It walks the same vanilla Anvil format WDL writes, so it never re-implements the
 * codec, only inspects its output.
 */
final class CaptureReadback {
    private CaptureReadback() {}

    /** Open a region storage rooted at {@code regionDirectory} to read chunk or entity tags back (caller closes). */
    static SimpleRegionStorage regionStorage(Path regionDirectory, DataFixTypes type) {
        return new SimpleRegionStorage(new RegionStorageInfo("wdl", Level.OVERWORLD, "chunk"), regionDirectory,
                DataFixers.getDataFixer(), false, type);
    }

    /** The stored chunk tag at {@code pos} under {@code <save>/region}, or empty if no chunk was written there. */
    static Optional<CompoundTag> readChunk(Path saveRoot, ChunkPos pos) {
        return readTag(saveRoot.resolve("region"), pos, DataFixTypes.CHUNK);
    }

    /** The stored entity-chunk tag at {@code pos} under {@code <save>/entities}, or empty if none was written. */
    static Optional<CompoundTag> readEntityChunk(Path saveRoot, ChunkPos pos) {
        return readTag(saveRoot.resolve("entities"), pos, DataFixTypes.ENTITY_CHUNK);
    }

    /**
     * The stored entity-chunk tag at {@code pos} under a non-overworld dimension's folder ({@code DIM-1} for the
     * nether, {@code DIM1} for the end), or empty if none was written. The position space is shared between dimensions,
     * so which folder a chunk is read from is the whole of what distinguishes them on disk.
     */
    static Optional<CompoundTag> readEntityChunkIn(Path saveRoot, String dimensionDirectory, ChunkPos pos) {
        return readTag(saveRoot.resolve(dimensionDirectory).resolve("entities"), pos, DataFixTypes.ENTITY_CHUNK);
    }

    private static Optional<CompoundTag> readTag(Path regionDirectory, ChunkPos pos, DataFixTypes type) {
        try (SimpleRegionStorage storage = regionStorage(regionDirectory, type)) {
            return storage.read(pos).join();
        } catch (IOException e) {
            throw new RuntimeException("failed reading " + regionDirectory + " at " + pos, e);
        }
    }

    /** Every block id present in any section palette of a stored chunk tag (the terrain assertion target). */
    static List<String> paletteBlockNames(CompoundTag chunkTag) {
        List<String> names = new ArrayList<>();
        chunkTag.getList("sections", Tag.TAG_COMPOUND).stream().map(t -> (CompoundTag) t)
                .forEach(section -> section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)
                        .stream().map(t -> (CompoundTag) t).forEach(entry -> {
                            if (entry.contains("Name", Tag.TAG_STRING)) {
                                names.add(entry.getString("Name"));
                            }
                        }));
        return names;
    }

    /** The stored light nibbles of {@code layerKey} ("BlockLight"/"SkyLight") at section Y, or empty. */
    static Optional<byte[]> sectionLightLayer(CompoundTag chunkTag, int sectionY, String layerKey) {
        return chunkTag.getList("sections", Tag.TAG_COMPOUND).stream().map(t -> (CompoundTag) t)
                .filter(section -> (section.contains("Y") ? section.getByte("Y") : Byte.MIN_VALUE) == sectionY)
                .findFirst()
                .map(section -> section.getByteArray(layerKey))
                .filter(layer -> layer.length > 0);
    }

    /** The entity tags stored in an entity-chunk tag (the packet-derived entity assertion target). */
    static List<CompoundTag> entities(CompoundTag entityChunkTag) {
        return entityChunkTag.getList("Entities", Tag.TAG_COMPOUND).stream().map(t -> (CompoundTag) t)
                .collect(Collectors.toList());
    }

    /** The block-entity tag stored at {@code pos} in a chunk tag's {@code block_entities}, or empty if none. */
    static Optional<CompoundTag> blockEntityAt(CompoundTag chunkTag, BlockPos pos) {
        return chunkTag.getList("block_entities", Tag.TAG_COMPOUND).stream().map(t -> (CompoundTag) t)
                .filter(blockEntity -> (blockEntity.contains("x") ? blockEntity.getInt("x") : Integer.MIN_VALUE) == pos
                        .getX()
                        && (blockEntity.contains("y") ? blockEntity.getInt("y") : Integer.MIN_VALUE) == pos.getY()
                        && (blockEntity.contains("z") ? blockEntity.getInt("z") : Integer.MIN_VALUE) == pos.getZ())
                .findFirst();
    }

    /** The {@code UUID} of a stored entity tag (the {@code UUIDUtil.CODEC} four-int array), or empty if absent. */
    static Optional<UUID> entityUuid(CompoundTag entityTag) {
        return UUIDUtil.CODEC.parse(NbtOps.INSTANCE, entityTag.get("UUID")).result();
    }

    /** The item ids in a container tag's {@code Items} list (a block entity's or an entity's), in stored order. */
    static List<String> itemIds(CompoundTag containerTag) {
        return containerTag.getList("Items", Tag.TAG_COMPOUND).stream().map(t -> (CompoundTag) t)
                .map(item -> (item.contains("id") ? item.getString("id") : "?"))
                .collect(Collectors.toList());
    }

    /** The {@code Data} compound of {@code <save>/level.dat} (player, game rules, world settings live under it). */
    static CompoundTag levelData(Path saveRoot) {
        try {
            CompoundTag root = NbtIo.readCompressed(saveRoot.resolve("level.dat"), NbtAccounter.unlimitedHeap());
            return root.getCompound("Data");
        } catch (IOException e) {
            throw new RuntimeException("failed reading level.dat under " + saveRoot, e);
        }
    }

    /** The captured map-image files ({@code <save>/data/map_*.dat}) present, sorted; empty if none were written. */
    static List<Path> mapDataFiles(Path saveRoot) {
        Path dataDirectory = saveRoot.resolve("data");
        if (!Files.isDirectory(dataDirectory)) {
            return ImmutableList.of();
        }
        try (Stream<Path> files = Files.list(dataDirectory)) {
            return files.filter(file -> {
                String name = file.getFileName().toString();
                return name.startsWith("map_") && name.endsWith(".dat");
            }).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("failed listing data/ under " + saveRoot, e);
        }
    }

    /** The archive ids of the {@code data/map_*.dat} files present, parsed from their {@code map_<id>.dat} names. */
    static List<Integer> mapDataIds(Path saveRoot) {
        List<Integer> ids = new ArrayList<>();
        for (Path file : mapDataFiles(saveRoot)) {
            String name = file.getFileName().toString();
            ids.add(Integer.parseInt(name.substring("map_".length(), name.length() - ".dat".length())));
        }
        return ids;
    }

    /** The inner {@code data} compound of a {@code data/<key>.dat} envelope (the {@code {data, DataVersion}} wrap). */
    static CompoundTag readDataInner(Path dataFile) {
        try {
            return NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap()).getCompound("data");
        } catch (IOException e) {
            throw new RuntimeException("failed reading data file " + dataFile, e);
        }
    }

    /** Whether a map-image inner tag carries a full 128x128 color array (the well-formedness gate). */
    static boolean isWellFormedMapImage(CompoundTag mapDataInner) {
        return mapDataInner.getByteArray("colors").length == 16384;
    }

    /** Distinct non-zero color bytes in a map-image inner tag, the proof its color data round-tripped (not shape). */
    static long distinctNonZeroColors(CompoundTag mapDataInner) {
        byte[] colors = mapDataInner.getByteArray("colors");
        Set<Byte> distinct = new HashSet<>();
        for (byte color : colors) {
            if (color != 0) {
                distinct.add(color);
            }
        }
        return (long) distinct.size();
    }

    /** The {@code map} high-water id in {@code <save>/data/idcounts.dat}, or empty if no idcounts file was written. */
    static OptionalInt idCountsMax(Path saveRoot) {
        Path file = saveRoot.resolve("data").resolve("idcounts.dat");
        if (!Files.isRegularFile(file)) {
            return OptionalInt.empty();
        }
        CompoundTag inner = readDataInner(file);
        int map = inner.contains("map") ? inner.getInt("map") : Integer.MIN_VALUE;
        return map == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(map);
    }

    /** The parsed {@code <save>/advancements/<uuid>.json}; fails the run if absent. */
    static JsonObject advancementsJson(Path saveRoot, UUID uuid) {
        return readJson(saveRoot.resolve("advancements").resolve(uuid + ".json"));
    }

    /** The parsed {@code <save>/stats/<uuid>.json}; fails the run if absent. */
    static JsonObject statsJson(Path saveRoot, UUID uuid) {
        return readJson(saveRoot.resolve("stats").resolve(uuid + ".json"));
    }

    /** Whether a progress file exists under {@code <save>/<directory>/<uuid>.json} (for the config-off assertion). */
    static boolean progressFileExists(Path saveRoot, String directory, UUID uuid) {
        return Files.isRegularFile(saveRoot.resolve(directory).resolve(uuid + ".json"));
    }

    private static JsonObject readJson(Path file) {
        try {
            return JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new RuntimeException("failed reading JSON " + file, e);
        }
    }
}
