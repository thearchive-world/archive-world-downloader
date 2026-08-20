// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.class_99;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * level.dat round-trip: the writer produces a superflat VOID world whose Data tag, written to a real compressed
 * level.dat and read back, preserves the seed, DataVersion, and the flat-void generator name. At 1.15.2 the generator
 * is stored as a {@code generatorName} plus its options in the Data tag (the void world uses the {@code flat} preset),
 * not as the per-dimension {@code WorldGenSettings} of 1.16 and later.
 */
class LevelDatRoundTripTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    @Test
    void levelDatRoundTripsVoidGeneratorSeedAndDataVersion(@TempDir Path directory) throws IOException {
        TestRegistries.bootstrap();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);

        CompoundTag dataTag = built.worldData().createTag(null);
        long originalSeed = dataTag.getLong("RandomSeed");
        assertEquals("flat", dataTag.getString("generatorName"), "the void world is a (flat) superflat generator");

        Path levelDat = directory.resolve("level.dat");
        CompoundTag root = new CompoundTag();
        root.put("Data", dataTag);
        try (OutputStream out = Files.newOutputStream(levelDat)) {
            NbtIo.writeCompressed(root, out);
        }
        CompoundTag back;
        try (InputStream in = Files.newInputStream(levelDat)) {
            back = NbtIo.readCompressed(in).getCompound("Data");
        }

        assertTrue((back.contains("DataVersion") ? back.getInt("DataVersion") : -1) > 0, "DataVersion survives");
        assertTrue(back.contains("SpawnX"), "spawn survives");
        assertEquals("flat", back.getString("generatorName"), "the flat-void generator survives the round-trip");
        assertEquals(originalSeed, back.getLong("RandomSeed"), "seed survives the round-trip");
    }

    @Test
    void buildsWithoutWorldgenRegistries() {
        TestRegistries.bootstrap();
        // At 1.15.2 the writer records only a generator name and options, so it needs no worldgen registries.
        assertDoesNotThrow(() -> writer.buildLevelData(WorldOutputConfig.DEFAULTS, null),
                "must derive the void generator, not fail");
    }

    @Test
    void savesLevelDatThroughTheProductionLevelStorage(@TempDir Path saves) throws IOException {
        TestRegistries.bootstrap();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);

        // Drive the REAL production save (LevelStorage.saveLevelData) rather than a hand-rolled NbtIo write, so the
        // headless suite guards the band-specific save call inside LevelDataWriter.save().
        LevelStorage storage = (LevelStorage) storageSource(saves).selectLevel("wdltest", null);
        writer.save(storage, built, null);

        Path levelDat = saves.resolve("wdltest").resolve("level.dat");
        assertTrue(Files.exists(levelDat), "save() must write level.dat via the vanilla LevelStorage envelope");

        CompoundTag data = readData(levelDat);
        assertTrue((data.contains("DataVersion") ? data.getInt("DataVersion") : -1) > 0,
                "DataVersion survives the production save");
        assertEquals("flat", data.getString("generatorName"), "the void world is a (flat) superflat generator");
    }

    @Test
    void levelDatCarriesTheGivenWorldName(@TempDir Path saves) throws IOException {
        TestRegistries.bootstrap();
        LevelStorage storage = (LevelStorage) storageSource(saves).selectLevel("named", null);
        writer.save(storage, writer.buildLevelData(WorldOutputConfig.DEFAULTS, "My Base"), null);
        assertEquals("My Base", levelName(saves.resolve("named")), "the typed name is written as LevelName");
    }

    @Test
    void levelDatDefaultsTheWorldNameWhenAbsent(@TempDir Path saves) throws IOException {
        TestRegistries.bootstrap();
        LevelStorage storage = (LevelStorage) storageSource(saves).selectLevel("unnamed", null);
        writer.save(storage, writer.buildLevelData(WorldOutputConfig.DEFAULTS, null), null);
        assertEquals("Archive World Downloader", levelName(saves.resolve("unnamed")),
                "a null name falls back to the writer default");
    }

    private static LevelStorageSource storageSource(Path saves) {
        return new class_99(saves, saves.resolve("backups"), DataFixers.getDataFixer());
    }

    private static String levelName(Path worldFolder) throws IOException {
        return readData(worldFolder.resolve("level.dat")).getString("LevelName");
    }

    private static CompoundTag readData(Path levelDat) throws IOException {
        try (InputStream in = Files.newInputStream(levelDat)) {
            return NbtIo.readCompressed(in).getCompound("Data");
        }
    }
}
