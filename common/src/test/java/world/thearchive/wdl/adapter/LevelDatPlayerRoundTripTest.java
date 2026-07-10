// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the level.dat apply: {@link LevelDataWriter#save} writes the void world's output, no
 * {@code "Player"} slot, the default spawn and {@code SURVIVAL}. Driven through the real production
 * {@code LevelStorageAccess.saveDataTag}, so the headless suite guards the band-specific form rather than a stand-in.
 */
class LevelDatPlayerRoundTripTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    private CompoundTag saveAndReadBack(Path saves, String name) throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        LevelDataWriter.LevelData built = writer.buildLevelData(registries, WorldOutputConfig.DEFAULTS, null);
        LevelStorageSource source = LevelStorageSource.createDefault(saves);
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess(name)) {
            writer.save(access, built);
        }
        Path levelDat = saves.resolve(name).resolve("level.dat");
        return NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
    }

    @Test
    void savesMatchTodaysVoidOutput(@TempDir Path saves) throws IOException {
        CompoundTag data = saveAndReadBack(saves, "noplayer");

        assertFalse(data.contains("Player"), "the void world writes no Player slot");
        assertEquals(GameType.SURVIVAL.getId(), data.getIntOr("GameType", -99),
                "the void world stays the default survival");
        assertTrue(data.contains("spawn"), "the default spawn is still written");
    }
}
