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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.world.chunk.storage.AnvilSaveConverter;
import net.minecraft.world.storage.ISaveHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * level.dat round-trip: the writer produces a superflat VOID world whose Data tag, written to a real compressed
 * level.dat and read back, preserves the seed, DataVersion, and the flat-void generator name. At 1.12.2 the generator
 * is stored as a {@code WorldType} name plus its options string in the Data tag (the void world uses the {@code flat}
 * preset).
 */
class LevelDatRoundTripTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    @Test
    void levelDatRoundTripsVoidGeneratorSeedAndDataVersion(@TempDir Path directory) throws IOException {
        TestRegistries.bootstrap();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);

        NBTTagCompound dataTag = built.worldData().cloneNBTCompound(null);
        long originalSeed = dataTag.getLong("RandomSeed");
        assertEquals("flat", dataTag.getString("generatorName"), "the void world is a (flat) superflat generator");

        Path levelDat = directory.resolve("level.dat");
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("Data", dataTag);
        try (OutputStream out = Files.newOutputStream(levelDat)) {
            CompressedStreamTools.writeCompressed(root, out);
        }
        NBTTagCompound back;
        try (InputStream in = Files.newInputStream(levelDat)) {
            back = CompressedStreamTools.readCompressed(in).getCompoundTag("Data");
        }

        assertTrue((back.hasKey("DataVersion") ? back.getInteger("DataVersion") : -1) > 0, "DataVersion survives");
        assertTrue(back.hasKey("SpawnX"), "spawn survives");
        assertEquals("flat", back.getString("generatorName"), "the flat-void generator survives the round-trip");
        assertEquals(originalSeed, back.getLong("RandomSeed"), "seed survives the round-trip");
    }

    @Test
    void buildsWithoutWorldgenRegistries() {
        TestRegistries.bootstrap();
        // At 1.12.2 the writer records only a WorldType and options, so it needs no worldgen registries.
        assertDoesNotThrow(() -> writer.buildLevelData(WorldOutputConfig.DEFAULTS, null),
                "must derive the void generator, not fail");
    }

    @Test
    void savesLevelDatThroughTheProductionLevelStorage(@TempDir Path saves) throws IOException {
        TestRegistries.bootstrap();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);

        // Drive the REAL production save (ISaveHandler.saveWorldInfo) rather than a hand-rolled
        // CompressedStreamTools write, so the headless suite guards the band-specific save call inside
        // LevelDataWriter.save().
        ISaveHandler storage = storageSource(saves).getSaveLoader("wdltest", true);
        writer.save(storage, built, null);

        Path levelDat = saves.resolve("wdltest").resolve("level.dat");
        assertTrue(Files.exists(levelDat), "save() must write level.dat via the vanilla ISaveHandler envelope");

        NBTTagCompound data = readData(levelDat);
        assertTrue((data.hasKey("DataVersion") ? data.getInteger("DataVersion") : -1) > 0,
                "DataVersion survives the production save");
        assertEquals("flat", data.getString("generatorName"), "the void world is a (flat) superflat generator");
    }

    @Test
    void levelDatCarriesTheGivenWorldName(@TempDir Path saves) throws IOException {
        TestRegistries.bootstrap();
        ISaveHandler storage = storageSource(saves).getSaveLoader("named", true);
        writer.save(storage, writer.buildLevelData(WorldOutputConfig.DEFAULTS, "My Base"), null);
        assertEquals("My Base", levelName(saves.resolve("named")), "the typed name is written as LevelName");
    }

    @Test
    void levelDatDefaultsTheWorldNameWhenAbsent(@TempDir Path saves) throws IOException {
        TestRegistries.bootstrap();
        ISaveHandler storage = storageSource(saves).getSaveLoader("unnamed", true);
        writer.save(storage, writer.buildLevelData(WorldOutputConfig.DEFAULTS, null), null);
        assertEquals("Archive World Downloader", levelName(saves.resolve("unnamed")),
                "a null name falls back to the writer default");
    }

    private static AnvilSaveConverter storageSource(Path saves) {
        return new AnvilSaveConverter(saves.toFile(), null);
    }

    private static String levelName(Path worldFolder) throws IOException {
        return readData(worldFolder.resolve("level.dat")).getString("LevelName");
    }

    private static NBTTagCompound readData(Path levelDat) throws IOException {
        try (InputStream in = Files.newInputStream(levelDat)) {
            return CompressedStreamTools.readCompressed(in).getCompoundTag("Data");
        }
    }
}
