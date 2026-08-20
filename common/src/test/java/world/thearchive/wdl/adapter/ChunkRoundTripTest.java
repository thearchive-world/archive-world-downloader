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
 * biome container decodes, {@code OCEAN_FLOOR} is dropped, and {@code isLightOn} tracks the captured light flag (not a
 * hardcoded {@code true}). Full game-load validity is not exercised headless.
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

        // OCEAN_FLOOR (LIVE_WORLD, never sent to a client) is omitted so vanilla read() re-primes it;
        // the three CLIENT-usage heightmaps are kept. (Re-prime itself is ServerLevel-only, not exercised
        // headless; this asserts the omission.)
        CompoundTag heightmaps = back.getCompound("Level").getCompound("Heightmaps");
        assertFalse(heightmaps.contains("OCEAN_FLOOR"), "OCEAN_FLOOR must be dropped from the written tag");
        assertTrue(heightmaps.contains("WORLD_SURFACE"), "client-sent heightmaps must be kept");

        assertSectionsDecode(back);
    }

    @Test
    void isLightOnTracksCapturedLightNotHardcoded() {
        TestRegistries.bootstrap();

        CompoundTag lit = codec.encode(SyntheticChunks.full(true), false);
        CompoundTag dark = codec.encode(SyntheticChunks.full(false), false);

        assertTrue(lit.getCompound("Level").getBoolean("isLightOn"), "lightCorrect chunk -> isLightOn=true");
        assertFalse(dark.getCompound("Level").getBoolean("isLightOn"), "non-lightCorrect chunk -> isLightOn omitted");
    }

    @Test
    void capturedLightRoundTripsInVanillaShape(@TempDir Path directory) {
        TestRegistries.bootstrap();
        int minSectionY = SyntheticChunks.MIN_Y;

        CompoundTag tag = codec.encode(SyntheticChunks.fullWithLight(), false);
        CompoundTag back = RegionRoundTrip.writeThenRead(directory, new ChunkPos(0, 0), tag);

        assertTrue(back.getCompound("Level").getBoolean("isLightOn"), "lit snapshot -> isLightOn=true");

        CompoundTag bottom = sectionAt(back, minSectionY);
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.BLOCK_LIGHT_FILL),
                bottom.getByteArray("BlockLight"), "bottom section block light survives");
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.SKY_LIGHT_FILL),
                bottom.getByteArray("SkyLight"), "bottom section sky light survives");

        CompoundTag padding = sectionAt(back, minSectionY - 1);
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.SKY_LIGHT_FILL),
                padding.getByteArray("SkyLight"), "below-chunk padding sky light survives");
        assertFalse(padding.contains("BlockStates"), "padding section carries no block states");

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
                // read() throws on malformed palette or block-state data, so reaching the next line proves decode.
                new LevelChunkSection(section.getByte("Y")).getStates()
                        .read(section.getList("Palette", 10), section.getLongArray("BlockStates"));
            }
        }
        int[] biomeIds = level.getIntArray("Biomes");
        assertEquals(16 * ((SyntheticChunks.HEIGHT + 3) / 4), biomeIds.length, "biomes cover the whole column");
        for (int id : biomeIds) {
            assertNotNull(biomeRegistry.byId(id), "every written biome id resolves to a registered biome");
        }
    }
}
