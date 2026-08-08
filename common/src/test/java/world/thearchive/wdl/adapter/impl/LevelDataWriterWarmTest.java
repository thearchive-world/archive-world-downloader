// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.CapturedPlayer;
import world.thearchive.wdl.adapter.LevelDataWriter;
import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The SPI warm hook: the 1.21.11 override drives the memoized reconstruction, the same warmed set reaches the
 * level-data build (so a DEFAULT world still encodes), and the interface default is inert for a band that never
 * reconstructs worldgen.
 */
class LevelDataWriterWarmTest {
    @BeforeAll
    static void primeTags() {
        TestRegistries.frozen();
    }

    @BeforeEach
    void coldMemo() {
        VanillaWorldgenRegistries.resetForTesting();
    }

    @AfterEach
    void restoreRealLoader() {
        VanillaWorldgenRegistries.resetForTesting();
    }

    @Test
    void warmReconstructsExactlyOnceAndTheSaveReusesIt() {
        new LevelDataWriterImpl().warmWorldgen();
        assertEquals(1, VanillaWorldgenRegistries.loadCountForTesting(), "the warm reconstructs on the worker");

        VanillaWorldgenRegistries.get();
        assertEquals(1, VanillaWorldgenRegistries.loadCountForTesting(),
                "the render thread's later get is a cache hit, not a second reconstruction");
    }

    @Test
    void defaultLevelDataBuiltAfterWarmEncodesSuccessfully() {
        LevelDataWriterImpl writer = new LevelDataWriterImpl();
        writer.warmWorldgen();

        LevelDataWriter.LevelData data = assertDoesNotThrow(() -> writer.buildLevelData(
                TestRegistries.frozen(), defaultWorld(), "Warmed"));
        assertNotNull(data.worldData(), "a DEFAULT world encodes against the same set the warm populated");
        assertEquals(1, VanillaWorldgenRegistries.loadCountForTesting(),
                "the build reuses the warmed set rather than reconstructing again");
    }

    @Test
    void theSpiDefaultWarmDoesNothing() {
        LevelDataWriter neverReconstructs = new LevelDataWriter() {
            @Override
            public LevelData buildLevelData(RegistryAccess clientRegistries, WorldOutputConfig worldOutput,
                    @Nullable String worldName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void save(LevelStorageSource.LevelStorageAccess access, LevelData data,
                    @Nullable CapturedPlayer player) {
                throw new UnsupportedOperationException();
            }

            @Override
            public @Nullable CompoundTag readPriorPlayer(Path levelDatFile) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<CuratedGameRule> curatedGameRules() {
                throw new UnsupportedOperationException();
            }
        };

        assertDoesNotThrow(neverReconstructs::warmWorldgen);
        assertEquals(0, VanillaWorldgenRegistries.loadCountForTesting(),
                "a band that inherits the default reconstructs nothing");
    }

    private static WorldOutputConfig defaultWorld() {
        Properties properties = new Properties();
        properties.setProperty("worldType", "DEFAULT");
        return WorldOutputConfig.parse(properties);
    }
}
