// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.DynamicOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * level.dat round-trip: from a client-like reg (BIOME + DIMENSION_TYPE, NO LEVEL_STEM, just what a multiplayer client
 * syncs), the writer produces a superflat VOID world whose Data tag, written to a real compressed level.dat and read
 * back, preserves seed, the three dimensions and DataVersion, with the dimensions being flat-void generators.
 */
class LevelDatRoundTripTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    @Test
    void levelDatRoundTripsVoidDimensionsSeedAndDataVersion(@TempDir Path directory) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        LevelDataWriter.LevelData built = writer.buildLevelData(registries, WorldOutputConfig.DEFAULTS, null);
        DynamicOps<Tag> ops = built.registries().createSerializationContext(NbtOps.INSTANCE);

        CompoundTag dataTag = built.worldData().createTag(built.registries(), null);
        CompoundTag worldGenTag = dataTag.getCompoundOrEmpty("WorldGenSettings");
        assertFalse(worldGenTag.isEmpty(), "WorldGenSettings must be present in the level.dat Data tag");
        long originalSeed = WorldGenSettings.CODEC.parse(ops, worldGenTag).getOrThrow().options().seed();

        Path levelDat = directory.resolve("level.dat");
        CompoundTag root = new CompoundTag();
        root.put("Data", dataTag);
        NbtIo.writeCompressed(root, levelDat);
        CompoundTag back = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");

        assertTrue(back.getIntOr("DataVersion", -1) > 0, "DataVersion survives");
        assertTrue(back.contains("spawn"), "spawn survives");

        WorldGenSettings worldGen = WorldGenSettings.CODEC
                .parse(ops, back.getCompoundOrEmpty("WorldGenSettings"))
                .getOrThrow();
        assertEquals(3, worldGen.dimensions().dimensions().size(), "overworld + nether + end survive");
        assertEquals(originalSeed, worldGen.options().seed(), "seed survives the round-trip");
        assertInstanceOf(FlatLevelSource.class,
                worldGen.dimensions().dimensions().get(LevelStem.OVERWORLD).generator(),
                "overworld is a (void) superflat generator");
    }

    @Test
    void buildsFromClientRegistryWithoutLevelStem() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        // Precondition mirrors a real multiplayer client: dimension types + biomes synced, no LEVEL_STEM.
        assertTrue(registries.lookup(Registries.LEVEL_STEM).isEmpty(),
                "precondition: client-like reg has no LEVEL_STEM");
        assertDoesNotThrow(() -> writer.buildLevelData(registries, WorldOutputConfig.DEFAULTS, null),
                "must derive void dimensions, not fail");
    }

    @Test
    void savesLevelDatThroughTheProductionLevelStorageAccess(@TempDir Path saves) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        LevelDataWriter.LevelData built = writer.buildLevelData(registries, WorldOutputConfig.DEFAULTS, null);

        // Drive the REAL production save (LevelStorageAccess.saveDataTag) rather than a hand-rolled NbtIo write,
        // so the headless suite guards the band-specific saveDataTag call inside LevelDataWriter.save()
        // (that call drops its RegistryAccess argument at 26.1.2).
        LevelStorageSource source = LevelStorageSource.createDefault(saves);
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess("wdltest")) {
            writer.save(access, built, null);
        }

        Path levelDat = saves.resolve("wdltest").resolve("level.dat");
        assertTrue(Files.exists(levelDat), "save() must write level.dat via the vanilla LevelStorageAccess envelope");

        CompoundTag data = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
        DynamicOps<Tag> ops = built.registries().createSerializationContext(NbtOps.INSTANCE);
        assertTrue(data.getIntOr("DataVersion", -1) > 0, "DataVersion survives the production save");
        WorldGenSettings worldGen = WorldGenSettings.CODEC.parse(ops, data.getCompoundOrEmpty("WorldGenSettings"))
                .getOrThrow();
        assertEquals(3, worldGen.dimensions().dimensions().size(), "overworld + nether + end survive");
        assertInstanceOf(FlatLevelSource.class,
                worldGen.dimensions().dimensions().get(LevelStem.OVERWORLD).generator(),
                "overworld is a (void) superflat generator");
    }

    @Test
    void levelDatCarriesTheGivenWorldName(@TempDir Path saves) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        LevelStorageSource source = LevelStorageSource.createDefault(saves);
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess("named")) {
            writer.save(access, writer.buildLevelData(registries, WorldOutputConfig.DEFAULTS, "My Base"), null);
        }
        assertEquals("My Base", levelName(saves.resolve("named")), "the typed name is written as LevelName");
    }

    @Test
    void levelDatDefaultsTheWorldNameWhenAbsent(@TempDir Path saves) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        LevelStorageSource source = LevelStorageSource.createDefault(saves);
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess("unnamed")) {
            writer.save(access, writer.buildLevelData(registries, WorldOutputConfig.DEFAULTS, null), null);
        }
        assertEquals("Archive World Downloader", levelName(saves.resolve("unnamed")),
                "a null name falls back to the writer default");
    }

    private static String levelName(Path worldFolder) throws IOException {
        CompoundTag data = NbtIo.readCompressed(worldFolder.resolve("level.dat"), NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("Data");
        return data.getString("LevelName").orElse("");
    }
}
