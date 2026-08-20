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

/**
 * The automated guard for chunk capture: the mod's {@link ChunkCodec#encode(ChunkSnapshotSource, boolean)} slice,
 * written through vanilla's real region pipeline and read back, is self-consistent. Every section's block-state and
 * biome container decodes, {@code OCEAN_FLOOR} is written (a LIVE_WORLD heightmap at this band), and light is
 * section-resident with no chunk-level {@code isLightOn} (a 1.14 lighting-engine field absent here). Full game-load
 * validity is not exercised headless.
 */
class ChunkRoundTripTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    @Test
    void chunkRoundTripsSelfConsistently(@TempDir Path directory) {
        TestRegistries.bootstrap();

        CompoundTag tag = codec.encode(SyntheticChunks.full(true), false);
        CompoundTag back = RegionRoundTrip.writeThenRead(directory, new ChunkPos(0, 0), tag);

        // write() stamps DataVersion, and it survives the on-disk region round-trip.
        assertTrue((tag.contains("DataVersion") ? tag.getInt("DataVersion") : -1) > 0,
                "codec must stamp a DataVersion");
        assertEquals((tag.contains("DataVersion") ? tag.getInt("DataVersion") : -1),
                (back.contains("DataVersion") ? back.getInt("DataVersion") : -2));

        // At this band the Heightmap.Usage enum has no CLIENT tier, so every captured heightmap is LIVE_WORLD and
        // the codec writes it, OCEAN_FLOOR included; vanilla class_1205 writes the same set.
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

        // isLightOn is a 1.14 lighting-engine field; the 1.13.2 jar carries no such key and vanilla class_1205 never
        // writes it. Light is section-resident at this band (each section's own BlockLight/SkyLight arrays), so the
        // codec emits no chunk-level light flag and the captured lightCorrect state has no on-disk form either way.
        assertFalse(lit.getCompound("Level").contains("isLightOn"), "lightCorrect chunk -> no isLightOn key");
        assertFalse(dark.getCompound("Level").contains("isLightOn"), "non-lightCorrect chunk -> no isLightOn key");
    }

    @Test
    void capturedLightRoundTripsInVanillaShape(@TempDir Path directory) {
        TestRegistries.bootstrap();
        int minSectionY = SyntheticChunks.MIN_Y;

        CompoundTag tag = codec.encode(SyntheticChunks.fullWithLight(), false);
        CompoundTag back = RegionRoundTrip.writeThenRead(directory, new ChunkPos(0, 0), tag);

        // Captured light survives on-disk as the section-resident arrays; no chunk-level isLightOn is written or read
        // back, that flag being a 1.14 field absent at this band.
        assertFalse(back.getCompound("Level").contains("isLightOn"), "no isLightOn key round-trips at 1.13.2");

        CompoundTag bottom = sectionAt(back, minSectionY);
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.BLOCK_LIGHT_FILL),
                bottom.getByteArray("BlockLight"), "bottom section block light survives");
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.SKY_LIGHT_FILL),
                bottom.getByteArray("SkyLight"), "bottom section sky light survives");

        // The below-chunk light pad (a null-section, sky-only entry) is a 1.14 light-engine shape; light is
        // section-resident at this band and the codec writes only the 0..15 block column, so the pad is dropped.
        int padY = minSectionY - 1;
        boolean padWritten = back.getCompound("Level").getList("Sections", 10).stream().map(t -> (CompoundTag) t)
                .anyMatch(section -> section.contains("Y") && section.getByte("Y") == padY);
        assertFalse(padWritten, "the below-chunk light pad is dropped at this band");

        assertSectionsDecode(back);
    }

    /** The written section tag with the given Y, failing the test if absent. */
    private static CompoundTag sectionAt(CompoundTag chunkTag, int sectionY) {
        return chunkTag.getCompound("Level").getList("Sections", 10).stream().map(t -> (CompoundTag) t)
                .filter(section -> (section.contains("Y") ? section.getByte("Y") : Byte.MIN_VALUE) == sectionY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no written section at Y=" + sectionY));
    }

    /**
     * Serverless self-consistency: every written section's block-state and biome container decodes through the same
     * vanilla codecs the codec encoded them with. Below 1.21.2 the full {@code ChunkSerializer.read} needs a
     * {@code ServerLevel} the headless test has none of, so the section containers are decoded directly instead.
     */
    private static void assertSectionsDecode(CompoundTag chunkTag) {
        Registry<Biome> biomeRegistry = Registry.BIOME;
        CompoundTag level = chunkTag.getCompound("Level");
        for (Tag sectionTag : level.getList("Sections", 10)) {
            CompoundTag section = (CompoundTag) sectionTag;
            if (section.contains("BlockStates", 12)) {
                // The palette read throws on malformed palette or block-state data, so reaching the next line proves
                // decode; at this band it reads straight from the section tag, the mirror of the codec's write.
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
