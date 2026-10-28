// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

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
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

final class CaptureReadback {
    private CaptureReadback() {}

    static SimpleRegionStorage regionStorage(Path regionDirectory, DataFixTypes type) {
        return new SimpleRegionStorage(new RegionStorageInfo("wdl", Level.OVERWORLD, "chunk"), regionDirectory,
                DataFixers.getDataFixer(), false, type);
    }

    static Optional<CompoundTag> readChunk(Path saveRoot, ChunkPos pos) {
        return readTag(saveRoot.resolve("region"), pos, DataFixTypes.CHUNK);
    }

    static Optional<CompoundTag> readEntityChunk(Path saveRoot, ChunkPos pos) {
        return readTag(saveRoot.resolve("entities"), pos, DataFixTypes.ENTITY_CHUNK);
    }

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

    static List<String> paletteBlockNames(CompoundTag chunkTag) {
        List<String> names = new ArrayList<>();
        chunkTag.getListOrEmpty("sections").compoundStream()
                .forEach(section -> section.getCompound("block_states")
                        .ifPresent(blockStates -> blockStates.getList("palette").ifPresent(palette -> palette
                                .compoundStream().forEach(entry -> entry.getString("Name").ifPresent(names::add)))));
        return names;
    }

    static Optional<byte[]> sectionLightLayer(CompoundTag chunkTag, int sectionY, String layerKey) {
        return chunkTag.getListOrEmpty("sections").compoundStream()
                .filter(section -> section.getByteOr("Y", Byte.MIN_VALUE) == sectionY)
                .findFirst()
                .flatMap(section -> section.getByteArray(layerKey));
    }

    static List<CompoundTag> entities(CompoundTag entityChunkTag) {
        return entityChunkTag.getListOrEmpty("Entities").compoundStream().toList();
    }

    static Optional<CompoundTag> blockEntityAt(CompoundTag chunkTag, BlockPos pos) {
        return chunkTag.getListOrEmpty("block_entities").compoundStream()
                .filter(blockEntity -> blockEntity.getIntOr("x", Integer.MIN_VALUE) == pos.getX()
                        && blockEntity.getIntOr("y", Integer.MIN_VALUE) == pos.getY()
                        && blockEntity.getIntOr("z", Integer.MIN_VALUE) == pos.getZ())
                .findFirst();
    }

    static Optional<UUID> entityUuid(CompoundTag entityTag) {
        return UUIDUtil.CODEC.parse(NbtOps.INSTANCE, entityTag.get("UUID")).result();
    }

    static List<String> itemIds(CompoundTag containerTag) {
        return containerTag.getListOrEmpty("Items").compoundStream()
                .map(item -> item.getString("id").orElse("?"))
                .toList();
    }

    static CompoundTag levelData(Path saveRoot) {
        try {
            CompoundTag root = NbtIo.readCompressed(saveRoot.resolve("level.dat"), NbtAccounter.unlimitedHeap());
            return root.getCompoundOrEmpty("Data");
        } catch (IOException e) {
            throw new RuntimeException("failed reading level.dat under " + saveRoot, e);
        }
    }

    static List<Path> mapDataFiles(Path saveRoot) {
        Path dataDirectory = saveRoot.resolve("data");
        if (!Files.isDirectory(dataDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dataDirectory)) {
            return files.filter(file -> {
                String name = file.getFileName().toString();
                return name.startsWith("map_") && name.endsWith(".dat");
            }).sorted().toList();
        } catch (IOException e) {
            throw new RuntimeException("failed listing data/ under " + saveRoot, e);
        }
    }

    static List<Integer> mapDataIds(Path saveRoot) {
        List<Integer> ids = new ArrayList<>();
        for (Path file : mapDataFiles(saveRoot)) {
            String name = file.getFileName().toString();
            ids.add(Integer.parseInt(name.substring("map_".length(), name.length() - ".dat".length())));
        }
        return ids;
    }

    static CompoundTag readDataInner(Path dataFile) {
        try {
            return NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data");
        } catch (IOException e) {
            throw new RuntimeException("failed reading data file " + dataFile, e);
        }
    }

    static boolean isWellFormedMapImage(CompoundTag mapDataInner) {
        return mapDataInner.getByteArray("colors").map(colors -> colors.length == 16384).orElse(false);
    }

    static long distinctNonZeroColors(CompoundTag mapDataInner) {
        return mapDataInner.getByteArray("colors").map(colors -> {
            Set<Byte> distinct = new HashSet<>();
            for (byte color : colors) {
                if (color != 0) {
                    distinct.add(color);
                }
            }
            return (long) distinct.size();
        }).orElse(0L);
    }

    static OptionalInt idCountsMax(Path saveRoot) {
        Path file = saveRoot.resolve("data").resolve("idcounts.dat");
        if (!Files.isRegularFile(file)) {
            return OptionalInt.empty();
        }
        int map = readDataInner(file).getIntOr("map", Integer.MIN_VALUE);
        return map == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(map);
    }

    static JsonObject advancementsJson(Path saveRoot, UUID uuid) {
        return readJson(saveRoot.resolve("advancements").resolve(uuid + ".json"));
    }

    static JsonObject statsJson(Path saveRoot, UUID uuid) {
        return readJson(saveRoot.resolve("stats").resolve(uuid + ".json"));
    }

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
