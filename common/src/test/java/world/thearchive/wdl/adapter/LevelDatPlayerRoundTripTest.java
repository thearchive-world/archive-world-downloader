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
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        LevelDataWriter.LevelData built = writer.buildLevelData(registries, WorldOutputConfig.DEFAULTS, null);
        LevelStorageSource source = LevelStorageSource.createDefault(saves);
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess(name)) {
            writer.save(access, built, player);
        }
        Path levelDat = saves.resolve(name).resolve("level.dat");
        return NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
    }

    @Test
    void savesWithCapturedPlayerWritePlayerGameTypeSpawnAndDifficulty(@TempDir Path saves) throws IOException {
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(), new BlockPos(120, 72, -340), 90.0F, 12.0F,
                Level.NETHER, GameType.CREATIVE, Difficulty.HARD);

        CompoundTag data = saveAndReadBack(saves, "withplayer", captured);

        assertTrue(data.contains("Player"), "the captured player is routed into the Player slot");
        assertEquals("captured-player", data.getCompoundOrEmpty("Player").getStringOr("wdlMarker", ""),
                "the Player slot is exactly the captured tag");
        assertEquals(GameType.CREATIVE.getId(), data.getIntOr("GameType", -99), "GameType flips to creative");
        assertEquals((byte) Difficulty.HARD.getId(), data.getByteOr("Difficulty", (byte) -1), "captured difficulty");

        GlobalPos spawn = data.read("spawn", LevelData.RespawnData.CODEC).orElseThrow().globalPos();
        assertEquals(Level.NETHER, spawn.dimension(), "the world spawn carries the capture dimension");
        assertEquals(new BlockPos(120, 72, -340), spawn.pos(), "the world spawn is the capture position");
    }

    @Test
    void savesWithOutOfRangeCapturedYawPersistValidSpawnYaw(@TempDir Path saves) throws IOException {
        // 271.2334 is a captured yaw outside the RespawnData codec's [-180,180) bound.
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(), BlockPos.ZERO, 271.2334F, 12.0F,
                Level.OVERWORLD, GameType.SURVIVAL, Difficulty.NORMAL);

        CompoundTag data = saveAndReadBack(saves, "outofrangeyaw", captured);

        float yaw = data.read("spawn", LevelData.RespawnData.CODEC).orElseThrow().yaw();
        assertTrue(yaw >= -180.0F && yaw < 180.0F, "the persisted spawn yaw is wrapped into the codec's valid range");
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

        CompoundTag data = saveAndReadBack(saves, "rootvehicle", captured);

        CompoundTag rootVehicle = data.getCompoundOrEmpty("Player").getCompoundOrEmpty("RootVehicle");
        assertEquals("minecraft:chest_boat", rootVehicle.getCompoundOrEmpty("Entity").getStringOr("id", ""),
                "the Entity child keeps its id, or loadEntityRecursive silently skips it (no re-seat)");
        assertEquals(boat, rootVehicle.read("Attach", UUIDUtil.CODEC).orElse(null),
                "Attach round-trips through UUIDUtil.CODEC as the direct vehicle UUID the re-seat matches");
    }
}
