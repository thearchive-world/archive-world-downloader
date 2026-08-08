// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
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
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.clock.ClockState;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.gamerules.GameRules;

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

    /** A dimension's 26.x storage folder, {@code <save>/dimensions/<namespace>/<path>}. */
    static Path dimensionDir(Path saveRoot, String namespace, String path) {
        return saveRoot.resolve("dimensions").resolve(namespace).resolve(path);
    }

    /** The overworld region folder, {@code <save>/dimensions/minecraft/overworld/region} at 26.x. */
    static Path overworldRegionDir(Path saveRoot) {
        return dimensionDir(saveRoot, "minecraft", "overworld").resolve("region");
    }

    /** The stored chunk tag at {@code pos} under the overworld region folder, or empty if no chunk was written. */
    static Optional<CompoundTag> readChunk(Path saveRoot, ChunkPos pos) {
        return readTag(overworldRegionDir(saveRoot), pos, DataFixTypes.CHUNK);
    }

    /** The stored entity-chunk tag at {@code pos} under the overworld entities folder, or empty if none. */
    static Optional<CompoundTag> readEntityChunk(Path saveRoot, ChunkPos pos) {
        return readTag(dimensionDir(saveRoot, "minecraft", "overworld").resolve("entities"), pos,
                DataFixTypes.ENTITY_CHUNK);
    }

    /**
     * The stored entity-chunk tag at {@code pos} under a dimension's entities folder
     * ({@code <save>/dimensions/<namespace>/<path>/entities} at 26.x), or empty if none was written. The position space
     * is shared between dimensions, so which folder a chunk is read from is the whole of what distinguishes them on
     * disk.
     */
    static Optional<CompoundTag> readEntityChunkIn(Path saveRoot, String namespace, String path, ChunkPos pos) {
        return readTag(dimensionDir(saveRoot, namespace, path).resolve("entities"), pos, DataFixTypes.ENTITY_CHUNK);
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
        chunkTag.getListOrEmpty("sections").compoundStream()
                .forEach(section -> section.getCompound("block_states")
                        .ifPresent(blockStates -> blockStates.getList("palette").ifPresent(palette -> palette
                                .compoundStream().forEach(entry -> entry.getString("Name").ifPresent(names::add)))));
        return names;
    }

    /** The stored light nibbles of {@code layerKey} ("BlockLight"/"SkyLight") at section Y, or empty. */
    static Optional<byte[]> sectionLightLayer(CompoundTag chunkTag, int sectionY, String layerKey) {
        return chunkTag.getListOrEmpty("sections").compoundStream()
                .filter(section -> section.getByteOr("Y", Byte.MIN_VALUE) == sectionY)
                .findFirst()
                .flatMap(section -> section.getByteArray(layerKey));
    }

    /** The entity tags stored in an entity-chunk tag (the packet-derived entity assertion target). */
    static List<CompoundTag> entities(CompoundTag entityChunkTag) {
        return entityChunkTag.getListOrEmpty("Entities").compoundStream().toList();
    }

    /** The block-entity tag stored at {@code pos} in a chunk tag's {@code block_entities}, or empty if none. */
    static Optional<CompoundTag> blockEntityAt(CompoundTag chunkTag, BlockPos pos) {
        return chunkTag.getListOrEmpty("block_entities").compoundStream()
                .filter(blockEntity -> blockEntity.getIntOr("x", Integer.MIN_VALUE) == pos.getX()
                        && blockEntity.getIntOr("y", Integer.MIN_VALUE) == pos.getY()
                        && blockEntity.getIntOr("z", Integer.MIN_VALUE) == pos.getZ())
                .findFirst();
    }

    /** The {@code UUID} of a stored entity tag (the {@code UUIDUtil.CODEC} four-int array), or empty if absent. */
    static Optional<UUID> entityUuid(CompoundTag entityTag) {
        return UUIDUtil.CODEC.parse(NbtOps.INSTANCE, entityTag.get("UUID")).result();
    }

    /** The item ids in a container tag's {@code Items} list (a block entity's or an entity's), in stored order. */
    static List<String> itemIds(CompoundTag containerTag) {
        return containerTag.getListOrEmpty("Items").compoundStream()
                .map(item -> item.getString("id").orElse("?"))
                .toList();
    }

    /** The {@code Data} compound of {@code <save>/level.dat} (world game type, cheats, singleplayer_uuid). */
    static CompoundTag levelData(Path saveRoot) {
        try {
            CompoundTag root = NbtIo.readCompressed(saveRoot.resolve("level.dat"), NbtAccounter.unlimitedHeap());
            return root.getCompoundOrEmpty("Data");
        } catch (IOException e) {
            throw new RuntimeException("failed reading level.dat under " + saveRoot, e);
        }
    }

    /**
     * The captured local player's tag at {@code <save>/players/data/<uuid>.dat} (the raw tag, no {@code Data} wrap).
     */
    static CompoundTag capturedPlayer(Path saveRoot) {
        Path dataDirectory = saveRoot.resolve("players").resolve("data");
        try (Stream<Path> files = Files.list(dataDirectory)) {
            Path player = files.filter(file -> file.getFileName().toString().endsWith(".dat")).findFirst()
                    .orElseThrow(() -> new AssertionError("no players/data/<uuid>.dat was written under " + saveRoot));
            return NbtIo.readCompressed(player, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new RuntimeException("failed reading players/data under " + saveRoot, e);
        }
    }

    /** The overworld clock's total ticks from {@code <save>/data/minecraft/world_clocks.dat} (6000 at noon). */
    static long overworldDayTime(Path saveRoot, RegistryAccess registries) {
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        PackedClockStates clocks = PackedClockStates.CODEC.parse(ops, savedDataInner(saveRoot, "world_clocks"))
                .getOrThrow();
        Holder<WorldClock> overworld = registries.lookupOrThrow(Registries.WORLD_CLOCK)
                .getOrThrow(WorldClocks.OVERWORLD);
        ClockState state = clocks.clocks().get(overworld);
        if (state == null) {
            throw new AssertionError("the overworld clock is absent from world_clocks.dat under " + saveRoot);
        }
        return state.totalTicks();
    }

    /** The curated game rules parsed from {@code <save>/data/minecraft/game_rules.dat}. */
    static GameRules gameRules(Path saveRoot) {
        return GameRules.codec(FeatureFlags.DEFAULT_FLAGS)
                .parse(NbtOps.INSTANCE, savedDataInner(saveRoot, "game_rules")).getOrThrow();
    }

    /** The inner {@code data} tag of a namespaced {@code <save>/data/minecraft/<name>.dat} SavedData envelope. */
    static CompoundTag savedDataInner(Path saveRoot, String name) {
        return readDataInner(saveRoot.resolve("data").resolve("minecraft").resolve(name + ".dat"));
    }

    /** The captured map-image files ({@code <save>/data/minecraft/maps/<id>.dat}) present, sorted; empty if none. */
    static List<Path> mapDataFiles(Path saveRoot) {
        Path mapsDirectory = saveRoot.resolve("data").resolve("minecraft").resolve("maps");
        if (!Files.isDirectory(mapsDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(mapsDirectory)) {
            return files.filter(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".dat") && !name.equals("last_id.dat");
            }).sorted().toList();
        } catch (IOException e) {
            throw new RuntimeException("failed listing data/minecraft/maps/ under " + saveRoot, e);
        }
    }

    /** The archive ids of the {@code maps/<id>.dat} files present, parsed from their {@code <id>.dat} names. */
    static List<Integer> mapDataIds(Path saveRoot) {
        List<Integer> ids = new ArrayList<>();
        for (Path file : mapDataFiles(saveRoot)) {
            String name = file.getFileName().toString();
            ids.add(Integer.parseInt(name.substring(0, name.length() - ".dat".length())));
        }
        return ids;
    }

    /** The inner {@code data} compound of a {@code data/<key>.dat} envelope (the {@code {data, DataVersion}} wrap). */
    static CompoundTag readDataInner(Path dataFile) {
        try {
            return NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data");
        } catch (IOException e) {
            throw new RuntimeException("failed reading data file " + dataFile, e);
        }
    }

    /** Whether a map-image inner tag carries a full 128x128 color array (the well-formedness gate). */
    static boolean isWellFormedMapImage(CompoundTag mapDataInner) {
        return mapDataInner.getByteArray("colors").map(colors -> colors.length == 16384).orElse(false);
    }

    /** Distinct non-zero color bytes in a map-image inner tag, the proof its color data round-tripped (not shape). */
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

    /**
     * The {@code map} high-water id in {@code <save>/data/minecraft/maps/last_id.dat}, or empty if none was written.
     */
    static OptionalInt idCountsMax(Path saveRoot) {
        Path file = saveRoot.resolve("data").resolve("minecraft").resolve("maps").resolve("last_id.dat");
        if (!Files.isRegularFile(file)) {
            return OptionalInt.empty();
        }
        int map = readDataInner(file).getIntOr("map", Integer.MIN_VALUE);
        return map == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(map);
    }

    /** The parsed {@code <save>/players/advancements/<uuid>.json}; fails the run if absent. */
    static JsonObject advancementsJson(Path saveRoot, UUID uuid) {
        return readJson(saveRoot.resolve("players").resolve("advancements").resolve(uuid + ".json"));
    }

    /** The parsed {@code <save>/players/stats/<uuid>.json}; fails the run if absent. */
    static JsonObject statsJson(Path saveRoot, UUID uuid) {
        return readJson(saveRoot.resolve("players").resolve("stats").resolve(uuid + ".json"));
    }

    /** Whether a progress file exists under {@code <save>/players/<directory>/<uuid>.json} (config-off assertion). */
    static boolean progressFileExists(Path saveRoot, String directory, UUID uuid) {
        return Files.isRegularFile(saveRoot.resolve("players").resolve(directory).resolve(uuid + ".json"));
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
