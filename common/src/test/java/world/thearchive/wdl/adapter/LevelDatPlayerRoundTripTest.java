// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the player-data level.dat apply: {@link LevelDataWriter#save} with a {@link CapturedPlayer}
 * routes the captured tag into the {@code "Player"} slot, flips {@code GameType}, sets the world {@code spawn}
 * ({@code RespawnData}) to the capture dimension + position, and writes the captured {@code Difficulty}; with a
 * {@code null} {@code CapturedPlayer} the output is today's void world (no {@code Player}, default spawn,
 * {@code SURVIVAL}). Driven through the real production {@code LevelStorageAccess.saveDataTag}, so the headless suite
 * guards the band-specific 3-argument form.
 */
class LevelDatPlayerRoundTripTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    private static CompoundTag capturedPlayerTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("wdlMarker", "captured-player"); // a sentinel proving this exact tag lands in the Player slot
        tag.put("Inventory", new ListTag());
        return tag;
    }

    private CompoundTag saveAndReadBack(Path saves, String name, @Nullable CapturedPlayer player)
            throws IOException {
        TestRegistries.bootstrap();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);
        LevelStorage storage = new LevelStorageSource(saves, saves.resolve("backups"), DataFixers.getDataFixer())
                .selectLevel(name, null);
        writer.save(storage, built, player);
        Path levelDat = saves.resolve(name).resolve("level.dat");
        try (InputStream in = Files.newInputStream(levelDat)) {
            return NbtIo.readCompressed(in).getCompound("Data");
        }
    }

    @Test
    void savesWithCapturedPlayerWritePlayerGameTypeSpawnAndDifficulty(@TempDir Path saves) throws IOException {
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(), new BlockPos(120, 72, -340), 90.0F, 12.0F,
                DimensionType.NETHER, GameType.CREATIVE, Difficulty.HARD);

        CompoundTag data = saveAndReadBack(saves, "withplayer", captured);

        assertTrue(data.contains("Player"), "the captured player is routed into the Player slot");
        assertEquals("captured-player", data.getCompound("Player").getString("wdlMarker"),
                "the Player slot is exactly the captured tag");
        assertEquals(GameType.CREATIVE.getId(), (data.contains("GameType") ? data.getInt("GameType") : -99),
                "GameType flips to creative");
        assertEquals((byte) Difficulty.HARD.getId(),
                (data.contains("Difficulty") ? data.getByte("Difficulty") : (byte) -1), "captured difficulty");

        BlockPos spawn = new BlockPos(data.getInt("SpawnX"), data.getInt("SpawnY"), data.getInt("SpawnZ"));
        assertEquals(new BlockPos(120, 72, -340), spawn, "the world spawn is the capture position");
    }

    @Test
    void savesWithSurvivalOptOutWriteTheCapturedMode(@TempDir Path saves) throws IOException {
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(), BlockPos.ZERO, 0.0F, 0.0F,
                DimensionType.OVERWORLD, GameType.SURVIVAL, Difficulty.NORMAL);

        CompoundTag data = saveAndReadBack(saves, "survival", captured);

        assertEquals(GameType.SURVIVAL.getId(), (data.contains("GameType") ? data.getInt("GameType") : -99),
                "the survival opt-out writes the captured (survival) mode, not creative");
    }

    @Test
    void savesWithNullCapturedPlayerMatchTodaysVoidOutput(@TempDir Path saves) throws IOException {
        CompoundTag data = saveAndReadBack(saves, "noplayer", null);

        assertFalse(data.contains("Player"), "no captured player -> no Player slot");
        assertEquals(GameType.SURVIVAL.getId(), (data.contains("GameType") ? data.getInt("GameType") : -99),
                "the void world stays the default survival");
        assertTrue(data.contains("SpawnX"), "the default spawn is still written");
    }

    @Test
    void savesCapturedPlayerRootVehicleInTheShapeLoadAndSpawnParentVehicleReads(@TempDir Path saves)
            throws IOException {
        CompoundTag playerTag = capturedPlayerTag();
        UUID boat = UUID.fromString("0fedcba9-8765-4321-fedc-ba9876543210");
        CompoundTag boatTag = EntityFixtures.entityTag("minecraft:chest_boat"); // the id loadEntityRecursive reads
        PlayerTag.setRootVehicle(playerTag, boat, boatTag);
        CapturedPlayer captured = new CapturedPlayer(playerTag, BlockPos.ZERO, 0.0F, 0.0F,
                DimensionType.OVERWORLD, GameType.CREATIVE, Difficulty.NORMAL);

        CompoundTag data = saveAndReadBack(saves, "rootvehicle", captured);

        CompoundTag rootVehicle = data.getCompound("Player").getCompound("RootVehicle");
        assertEquals("minecraft:chest_boat", rootVehicle.getCompound("Entity").getString("id"),
                "the Entity child keeps its id, or loadEntityRecursive silently skips it (no re-seat)");
        assertEquals(boat,
                rootVehicle.getUUID("Attach"),
                "Attach round-trips through CompoundTag.getUUID as the direct vehicle UUID the re-seat matches");
    }
}
