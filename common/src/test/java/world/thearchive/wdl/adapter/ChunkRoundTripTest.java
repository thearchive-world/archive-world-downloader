// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.testsupport.RegionRoundTrip;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

class ChunkRoundTripTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    @Test
    void chunkRoundTripsSelfConsistently(@TempDir Path directory) {
        TestRegistries.bootstrap();

        CompoundTag tag = codec.encode(SyntheticChunks.full(true), false);
        CompoundTag back = RegionRoundTrip.writeThenRead(directory, new ChunkPos(0, 0), tag);

        assertTrue((tag.contains("DataVersion") ? tag.getInt("DataVersion") : -1) > 0,
                "codec must stamp a DataVersion");
        assertEquals((tag.contains("DataVersion") ? tag.getInt("DataVersion") : -1),
                (back.contains("DataVersion") ? back.getInt("DataVersion") : -2));

        CompoundTag heightmaps = back.getCompound("Level").getCompound("Heightmaps");
        assertTrue(heightmaps.contains("OCEAN_FLOOR"), "OCEAN_FLOOR is a LIVE_WORLD heightmap here and is written");
        assertTrue(heightmaps.contains("WORLD_SURFACE"), "the LIVE_WORLD heightmaps are written");

        assertSectionsDecode(back);
    }

    @Test
    void lightIsSectionResidentWithNoChunkLevelIsLightOn() {
        TestRegistries.bootstrap();

        CompoundTag lit = codec.encode(SyntheticChunks.full(true), false);
        CompoundTag dark = codec.encode(SyntheticChunks.full(false), false);

        assertFalse(lit.getCompound("Level").contains("isLightOn"), "lightCorrect chunk -> no isLightOn key");
        assertFalse(dark.getCompound("Level").contains("isLightOn"), "non-lightCorrect chunk -> no isLightOn key");
    }

    @Test
    void capturedLightRoundTripsInVanillaShape(@TempDir Path directory) {
        TestRegistries.bootstrap();
        int minSectionY = SyntheticChunks.MIN_Y;

        CompoundTag tag = codec.encode(SyntheticChunks.fullWithLight(), false);
        CompoundTag back = RegionRoundTrip.writeThenRead(directory, new ChunkPos(0, 0), tag);

        assertFalse(back.getCompound("Level").contains("isLightOn"), "no isLightOn key round-trips at 1.13.2");

        CompoundTag bottom = sectionAt(back, minSectionY);
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.BLOCK_LIGHT_FILL),
                bottom.getByteArray("BlockLight"), "bottom section block light survives");
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.SKY_LIGHT_FILL),
                bottom.getByteArray("SkyLight"), "bottom section sky light survives");

        int padY = minSectionY - 1;
        boolean padWritten = back.getCompound("Level").getList("Sections", 10).stream().map(t -> (CompoundTag) t)
                .anyMatch(section -> section.contains("Y") && section.getByte("Y") == padY);
        assertFalse(padWritten, "the below-chunk light pad is dropped at this band");

        assertSectionsDecode(back);
    }

    private static CompoundTag sectionAt(CompoundTag chunkTag, int sectionY) {
        return chunkTag.getCompound("Level").getList("Sections", 10).stream().map(t -> (CompoundTag) t)
                .filter(section -> (section.contains("Y") ? section.getByte("Y") : Byte.MIN_VALUE) == sectionY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no written section at Y=" + sectionY));
    }

    /**
     * Below 1.21.2 the full chunk read needs a level the headless test has none of, so the section containers are
     * decoded directly instead.
     */
    private static void assertSectionsDecode(CompoundTag chunkTag) {
        Registry<Biome> biomeRegistry = Registry.BIOME;
        CompoundTag level = chunkTag.getCompound("Level");
        for (Tag sectionTag : level.getList("Sections", 10)) {
            CompoundTag section = (CompoundTag) sectionTag;
            if (section.contains("BlockStates", 12)) {
                new LevelChunkSection(section.getByte("Y"), true).getStates()
                        .method_17103(section, "Palette", "BlockStates");
            }
        }
        int[] biomeIds = level.getIntArray("Biomes");
        assertEquals(16 * 16, biomeIds.length, "biomes are the flat 16x16 per-column grid at this band");
        for (int id : biomeIds) {
            assertNotNull(biomeRegistry.method_7326(id), "every written biome id resolves to a registered biome");
        }
    }
}
