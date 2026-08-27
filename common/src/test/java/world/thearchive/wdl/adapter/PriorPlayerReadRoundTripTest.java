// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.chunk.storage.AnvilSaveConverter;
import net.minecraft.world.storage.ISaveHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The resume-read guard, the read mirror of {@link LevelDatPlayerRoundTripTest}: after a player download, the plug's
 * own {@link LevelDataWriter#readPriorPlayer} recovers the captured player from wherever this band wrote it, so a
 * resume that never reopens the ender chest carries the prior contents forward instead of clobbering them with an empty
 * chest. Band-agnostic: it asserts only the recovered tag, not its on-disk location, so it holds on every band while
 * the plug owns where the read lands.
 */
class PriorPlayerReadRoundTripTest {
    private final LevelDataWriter writer = new LevelDataWriterImpl();

    private static final UUID PLAYER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static NBTTagCompound capturedPlayerTag(NBTTagList enderItems) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setUniqueId("UUID", PLAYER_UUID);
        tag.setTag("Inventory", new NBTTagList());
        tag.setTag("EnderItems", enderItems);
        return tag;
    }

    private Path saveDownload(Path saves, String name, NBTTagList enderItems) throws IOException {
        TestRegistries.bootstrap();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(enderItems), BlockPos.ORIGIN, 0.0F, 0.0F,
                DimensionType.OVERWORLD, GameType.SURVIVAL, EnumDifficulty.NORMAL);
        ISaveHandler storage = new AnvilSaveConverter(saves.toFile(), null).getSaveLoader(name, true);
        writer.save(storage, built, captured);
        return saves.resolve(name).resolve("level.dat");
    }

    @Test
    void readPriorPlayerRecoversTheCapturedEnderChest(@TempDir Path saves) throws IOException {
        Path levelDat = saveDownload(saves, "resume", ItemFixtures.items("minecraft:diamond", "minecraft:emerald"));

        NBTTagCompound prior = writer.readPriorPlayer(levelDat);

        assertNotNull(prior, "the resume read finds the captured player from this band's own player home");
        assertEquals(ImmutableList.of("minecraft:diamond", "minecraft:emerald"), enderIds(prior),
                "the recovered player still carries the ender chest the download captured");
    }

    @Test
    void aResumeCarriesThePriorEnderChestIntoFreshEmptyPlayer(@TempDir Path saves) throws IOException {
        Path levelDat = saveDownload(saves, "carry", ItemFixtures.items("minecraft:diamond"));

        NBTTagCompound prior = writer.readPriorPlayer(levelDat);
        assertNotNull(prior, "the prior player is found, or the resume clobbers the ender chest with an empty one");

        // A resume that never reopens the ender chest serializes a fresh player with an empty EnderItems.
        NBTTagCompound fresh = capturedPlayerTag(new NBTTagList());
        boolean carried = PlayerTag.carryForwardEnderItems(prior, fresh);

        assertTrue(carried, "the prior ender chest is carried into the fresh empty player");
        assertEquals(ImmutableList.of("minecraft:diamond"), enderIds(fresh),
                "the carried-forward ender chest still holds the prior download's contents");
    }

    @Test
    void readPriorPlayerIsNullForFreshFolder(@TempDir Path saves) {
        assertNull(writer.readPriorPlayer(saves.resolve("fresh").resolve("level.dat")),
                "a folder with no prior save reads no player, so the resume skips the carry-forward");
    }

    private static List<String> enderIds(NBTTagCompound player) {
        List<String> ids = new ArrayList<>();
        if (player.getTag("EnderItems") instanceof NBTTagList) {
            NBTTagList items = (NBTTagList) player.getTag("EnderItems");
            for (int i = 0; i < items.tagCount(); i++) {
                ids.add(((NBTTagCompound) items.get(i)).getString("id"));
            }
        }
        return ids;
    }
}
