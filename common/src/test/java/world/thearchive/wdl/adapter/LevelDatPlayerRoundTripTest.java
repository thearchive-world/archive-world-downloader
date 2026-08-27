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
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.chunk.storage.AnvilSaveConverter;
import net.minecraft.world.storage.ISaveHandler;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the player-data level.dat apply: {@link LevelDataWriter#save} with a {@link CapturedPlayer}
 * routes the captured tag into the {@code "Player"} slot, flips {@code GameType}, sets the world spawn
 * ({@code SpawnX}/{@code SpawnY}/{@code SpawnZ}) to the capture position, and writes the captured {@code Difficulty};
 * with a {@code null} {@code CapturedPlayer} the output is today's void world (no {@code Player}, default spawn,
 * {@code SURVIVAL}). Driven through the real production {@code ISaveHandler.saveWorldInfoWithPlayer}, so the headless
 * suite guards the band-specific save call.
 */
class LevelDatPlayerRoundTripTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    private static NBTTagCompound capturedPlayerTag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("wdlMarker", "captured-player"); // a sentinel proving this exact tag lands in the Player slot
        tag.setTag("Inventory", new NBTTagList());
        return tag;
    }

    private NBTTagCompound saveAndReadBack(Path saves, String name, @Nullable CapturedPlayer player)
            throws IOException {
        TestRegistries.bootstrap();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);
        ISaveHandler storage = new AnvilSaveConverter(saves.toFile(), null).getSaveLoader(name, true);
        writer.save(storage, built, player);
        Path levelDat = saves.resolve(name).resolve("level.dat");
        try (InputStream in = Files.newInputStream(levelDat)) {
            return CompressedStreamTools.readCompressed(in).getCompoundTag("Data");
        }
    }

    @Test
    void savesWithCapturedPlayerWritePlayerGameTypeSpawnAndDifficulty(@TempDir Path saves) throws IOException {
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(), new BlockPos(120, 72, -340), 90.0F, 12.0F,
                DimensionType.NETHER, GameType.CREATIVE, EnumDifficulty.HARD);

        NBTTagCompound data = saveAndReadBack(saves, "withplayer", captured);

        assertTrue(data.hasKey("Player"), "the captured player is routed into the Player slot");
        assertEquals("captured-player", data.getCompoundTag("Player").getString("wdlMarker"),
                "the Player slot is exactly the captured tag");
        assertEquals(GameType.CREATIVE.getID(), (data.hasKey("GameType") ? data.getInteger("GameType") : -99),
                "GameType flips to creative");
        assertEquals((byte) EnumDifficulty.HARD.getId(),
                (data.hasKey("Difficulty") ? data.getByte("Difficulty") : (byte) -1), "captured difficulty");

        BlockPos spawn = new BlockPos(data.getInteger("SpawnX"), data.getInteger("SpawnY"), data.getInteger("SpawnZ"));
        assertEquals(new BlockPos(120, 72, -340), spawn, "the world spawn is the capture position");
    }

    @Test
    void savesWithSurvivalOptOutWriteTheCapturedMode(@TempDir Path saves) throws IOException {
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(), BlockPos.ORIGIN, 0.0F, 0.0F,
                DimensionType.OVERWORLD, GameType.SURVIVAL, EnumDifficulty.NORMAL);

        NBTTagCompound data = saveAndReadBack(saves, "survival", captured);

        assertEquals(GameType.SURVIVAL.getID(), (data.hasKey("GameType") ? data.getInteger("GameType") : -99),
                "the survival opt-out writes the captured (survival) mode, not creative");
    }

    @Test
    void savesWithNullCapturedPlayerMatchTodaysVoidOutput(@TempDir Path saves) throws IOException {
        NBTTagCompound data = saveAndReadBack(saves, "noplayer", null);

        assertFalse(data.hasKey("Player"), "no captured player -> no Player slot");
        assertEquals(GameType.SURVIVAL.getID(), (data.hasKey("GameType") ? data.getInteger("GameType") : -99),
                "the void world stays the default survival");
        assertTrue(data.hasKey("SpawnX"), "the default spawn is still written");
    }

    @Test
    void savesCapturedPlayerRootVehicleInTheShapeLoadAndSpawnParentVehicleReads(@TempDir Path saves)
            throws IOException {
        NBTTagCompound playerTag = capturedPlayerTag();
        UUID boat = UUID.fromString("0fedcba9-8765-4321-fedc-ba9876543210");
        NBTTagCompound boatTag = EntityFixtures.entityTag("minecraft:chest_boat"); // the id loadEntityRecursive reads
        PlayerTag.setRootVehicle(playerTag, boat, boatTag);
        CapturedPlayer captured = new CapturedPlayer(playerTag, BlockPos.ORIGIN, 0.0F, 0.0F,
                DimensionType.OVERWORLD, GameType.CREATIVE, EnumDifficulty.NORMAL);

        NBTTagCompound data = saveAndReadBack(saves, "rootvehicle", captured);

        NBTTagCompound rootVehicle = data.getCompoundTag("Player").getCompoundTag("RootVehicle");
        assertEquals("minecraft:chest_boat", rootVehicle.getCompoundTag("Entity").getString("id"),
                "the Entity child keeps its id, or loadEntityRecursive silently skips it (no re-seat)");
        assertEquals(boat,
                rootVehicle.getUniqueId("Attach"),
                "Attach round-trips through NBTTagCompound.getUniqueId as the direct vehicle UUID the re-seat matches");
    }
}
