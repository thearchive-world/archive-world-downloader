// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the 26.x player-data apply: {@link LevelDataWriter#save} with a {@link CapturedPlayer} writes
 * the captured tag to {@code players/data/<uuid>.dat} and stamps its uuid as the level.dat {@code singleplayer_uuid},
 * flips {@code GameType}, sets the world {@code spawn} ({@code RespawnData}) to the capture dimension + position, and
 * writes the captured {@code Difficulty} into {@code difficulty_settings}; with a {@code null} {@code CapturedPlayer}
 * the output is the void world (no player file, default spawn, {@code SURVIVAL}). Driven through the real production
 * {@code LevelStorageAccess.saveDataTag}, so the headless suite guards the band-specific 2-argument form and the
 * separate player-file write.
 */
class LevelDatPlayerRoundTripTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    // A client saveWithoutId always carries its UUID; the 26.x plug reads it to place players/data/<uuid>.dat.
    private static final UUID PLAYER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static CompoundTag capturedPlayerTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("wdlMarker", "captured-player"); // a sentinel proving this exact tag lands in players/data
        tag.store("UUID", UUIDUtil.CODEC, PLAYER_UUID);
        tag.put("Inventory", new ListTag());
        return tag;
    }

    private CompoundTag saveAndReadBack(Path saves, String name, @Nullable CapturedPlayer player)
            throws IOException {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        LevelDataWriter.LevelData built = writer.buildLevelData(registries, WorldOutputConfig.DEFAULTS, null);
        LevelStorageSource source = LevelStorageSource.createDefault(saves);
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess(name)) {
            writer.save(access, built, player);
        }
        Path levelDat = saves.resolve(name).resolve("level.dat");
        return NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
    }

    /** The 26.x player home: the captured tag gzipped at {@code players/data/<uuid>.dat}, no Data wrapper. */
    private static CompoundTag playerData(Path saves, String name) throws IOException {
        Path file = saves.resolve(name).resolve("players").resolve("data").resolve(PLAYER_UUID + ".dat");
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }

    @Test
    void savesWithCapturedPlayerWritePlayerGameTypeSpawnAndDifficulty(@TempDir Path saves) throws IOException {
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(), new BlockPos(120, 72, -340), 90.0F, 12.0F,
                Level.NETHER, GameType.CREATIVE, Difficulty.HARD);

        CompoundTag data = saveAndReadBack(saves, "withplayer", captured);

        assertEquals(PLAYER_UUID, data.read("singleplayer_uuid", UUIDUtil.CODEC).orElse(null),
                "level.dat stamps the captured player's singleplayer_uuid");
        assertEquals("captured-player", playerData(saves, "withplayer").getStringOr("wdlMarker", ""),
                "players/data/<uuid>.dat is exactly the captured tag");
        assertEquals(GameType.CREATIVE.getId(), data.getIntOr("GameType", -99), "GameType flips to creative");
        LevelSettings.DifficultySettings difficulty = data
                .read("difficulty_settings", LevelSettings.DifficultySettings.CODEC).orElseThrow();
        assertEquals(Difficulty.HARD, difficulty.difficulty(), "captured difficulty");

        GlobalPos spawn = data.read("spawn", LevelData.RespawnData.CODEC).orElseThrow().globalPos();
        assertEquals(Level.NETHER, spawn.dimension(), "the world spawn carries the capture dimension");
        assertEquals(new BlockPos(120, 72, -340), spawn.pos(), "the world spawn is the capture position");
    }

    @Test
    void savesWithSurvivalOptOutWriteTheCapturedMode(@TempDir Path saves) throws IOException {
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(), BlockPos.ZERO, 0.0F, 0.0F,
                Level.OVERWORLD, GameType.SURVIVAL, Difficulty.NORMAL);

        CompoundTag data = saveAndReadBack(saves, "survival", captured);

        assertEquals(GameType.SURVIVAL.getId(), data.getIntOr("GameType", -99),
                "the survival opt-out writes the captured (survival) mode, not creative");
    }

    @Test
    void savesWithNullCapturedPlayerMatchTodaysVoidOutput(@TempDir Path saves) throws IOException {
        CompoundTag data = saveAndReadBack(saves, "noplayer", null);

        assertFalse(data.contains("Player"), "no captured player -> no Player slot");
        assertEquals(GameType.SURVIVAL.getId(), data.getIntOr("GameType", -99),
                "the void world stays the default survival");
        assertTrue(data.contains("spawn"), "the default spawn is still written");
    }

    @Test
    void savesCapturedPlayerRootVehicleInTheShapeLoadAndSpawnParentVehicleReads(@TempDir Path saves)
            throws IOException {
        CompoundTag playerTag = capturedPlayerTag();
        UUID boat = UUID.fromString("0fedcba9-8765-4321-fedc-ba9876543210");
        CompoundTag boatTag = EntityFixtures.entityTag("minecraft:chest_boat"); // the id loadEntityRecursive reads
        PlayerTag.setRootVehicle(playerTag, boat, boatTag);
        CapturedPlayer captured = new CapturedPlayer(playerTag, BlockPos.ZERO, 0.0F, 0.0F,
                Level.OVERWORLD, GameType.CREATIVE, Difficulty.NORMAL);

        saveAndReadBack(saves, "rootvehicle", captured);

        CompoundTag rootVehicle = playerData(saves, "rootvehicle").getCompoundOrEmpty("RootVehicle");
        assertEquals("minecraft:chest_boat", rootVehicle.getCompoundOrEmpty("Entity").getStringOr("id", ""),
                "the Entity child keeps its id, or loadEntityRecursive silently skips it (no re-seat)");
        assertEquals(boat, rootVehicle.read("Attach", UUIDUtil.CODEC).orElse(null),
                "Attach round-trips through UUIDUtil.CODEC as the direct vehicle UUID the re-seat matches");
    }
}
