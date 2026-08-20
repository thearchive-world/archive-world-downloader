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
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.level.storage.class_99;
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

    // Bind the item data components before any fixture builds an ItemStack: at 26.x the components bind in the
    // resource reload TestRegistries.frozen drives, not in Bootstrap, so an EnderItems stack built first throws.
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static CompoundTag capturedPlayerTag(ListTag enderItems) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UUID", PLAYER_UUID);
        tag.put("Inventory", new ListTag());
        tag.put("EnderItems", enderItems);
        return tag;
    }

    private Path saveDownload(Path saves, String name, ListTag enderItems) throws IOException {
        TestRegistries.bootstrap();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);
        CapturedPlayer captured = new CapturedPlayer(capturedPlayerTag(enderItems), BlockPos.ZERO, 0.0F, 0.0F,
                DimensionType.field_18954, GameType.SURVIVAL, Difficulty.NORMAL);
        LevelStorage storage = (LevelStorage) new class_99(saves, saves.resolve("backups"), DataFixers.getDataFixer())
                .selectLevel(name, null);
        writer.save(storage, built, captured);
        return saves.resolve(name).resolve("level.dat");
    }

    @Test
    void readPriorPlayerRecoversTheCapturedEnderChest(@TempDir Path saves) throws IOException {
        Path levelDat = saveDownload(saves, "resume", ItemFixtures.items("minecraft:diamond", "minecraft:emerald"));

        CompoundTag prior = writer.readPriorPlayer(levelDat);

        assertNotNull(prior, "the resume read finds the captured player from this band's own player home");
        assertEquals(ImmutableList.of("minecraft:diamond", "minecraft:emerald"), enderIds(prior),
                "the recovered player still carries the ender chest the download captured");
    }

    @Test
    void aResumeCarriesThePriorEnderChestIntoFreshEmptyPlayer(@TempDir Path saves) throws IOException {
        Path levelDat = saveDownload(saves, "carry", ItemFixtures.items("minecraft:diamond"));

        CompoundTag prior = writer.readPriorPlayer(levelDat);
        assertNotNull(prior, "the prior player is found, or the resume clobbers the ender chest with an empty one");

        // A resume that never reopens the ender chest serializes a fresh player with an empty EnderItems.
        CompoundTag fresh = capturedPlayerTag(new ListTag());
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

    private static List<String> enderIds(CompoundTag player) {
        List<String> ids = new ArrayList<>();
        if (player.get("EnderItems") instanceof ListTag) {
            ListTag items = (ListTag) player.get("EnderItems");
            for (int i = 0; i < items.size(); i++) {
                ids.add(((CompoundTag) items.get(i)).getString("id"));
            }
        }
        return ids;
    }
}
