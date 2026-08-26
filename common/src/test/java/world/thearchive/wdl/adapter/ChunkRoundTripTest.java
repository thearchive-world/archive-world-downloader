// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.testsupport.RegionRoundTrip;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for chunk capture: the mod's {@link ChunkCodec#encode(ChunkSnapshotSource, boolean)} slice,
 * written through vanilla's real region pipeline and read back, is self-consistent. Every section's block-state bytes
 * decode, the flat {@code HeightMap} is written, and light is section-resident with no chunk-level {@code isLightOn} (a
 * post-Flattening lighting-engine key absent here).
 */
class ChunkRoundTripTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    @Test
    void chunkRoundTripsSelfConsistently(@TempDir Path directory) {
        TestRegistries.bootstrap();

        NBTTagCompound tag = codec.encode(SyntheticChunks.full(true), false);
        NBTTagCompound back = RegionRoundTrip.writeThenRead(directory, new ChunkPos(0, 0), tag);

        // write() stamps DataVersion, and it survives the on-disk region round-trip.
        assertTrue((tag.hasKey("DataVersion") ? tag.getInteger("DataVersion") : -1) > 0,
                "codec must stamp a DataVersion");
        assertEquals((tag.hasKey("DataVersion") ? tag.getInteger("DataVersion") : -1),
                (back.hasKey("DataVersion") ? back.getInteger("DataVersion") : -2));

        // Pre-Flattening the chunk carries exactly one flat heightmap, an int[256] HeightMap, with the sentinel this
        // band's own SyntheticChunks stamps at column 0.
        int[] heightMap = back.getCompoundTag("Level").getIntArray("HeightMap");
        assertEquals(SyntheticChunks.HEIGHT, heightMap.length, "HeightMap is the flat int[256]");
        assertEquals(SyntheticChunks.WORLD_SURFACE_SENTINEL, heightMap[0], "the kept heightmap value survives");

        assertSectionsDecode(back);
    }

    @Test
    void lightIsSectionResidentWithNoChunkLevelIsLightOn() {
        TestRegistries.bootstrap();

        NBTTagCompound lit = codec.encode(SyntheticChunks.full(true), false);
        NBTTagCompound dark = codec.encode(SyntheticChunks.full(false), false);

        // isLightOn is a post-Flattening lighting-engine field; this band carries no such key. Light is
        // section-resident here (each section's own BlockLight/SkyLight arrays), so the codec emits no chunk-level
        // light flag and the captured lightCorrect state maps only onto LightPopulated.
        assertFalse(lit.getCompoundTag("Level").hasKey("isLightOn"), "lightCorrect chunk -> no isLightOn key");
        assertFalse(dark.getCompoundTag("Level").hasKey("isLightOn"), "non-lightCorrect chunk -> no isLightOn key");
        assertTrue(lit.getCompoundTag("Level").getBoolean("LightPopulated"), "lightCorrect maps to LightPopulated");
        assertFalse(dark.getCompoundTag("Level").getBoolean("LightPopulated"),
                "non-lightCorrect maps to a false LightPopulated");
    }

    @Test
    void capturedLightRoundTripsInVanillaShape(@TempDir Path directory) {
        TestRegistries.bootstrap();
        int minSectionY = SyntheticChunks.MIN_Y;

        NBTTagCompound tag = codec.encode(SyntheticChunks.fullWithLight(), false);
        NBTTagCompound back = RegionRoundTrip.writeThenRead(directory, new ChunkPos(0, 0), tag);

        // Captured light survives on-disk as the section-resident arrays; no chunk-level isLightOn is written or
        // read back, that flag being a post-Flattening key absent at this band.
        assertFalse(back.getCompoundTag("Level").hasKey("isLightOn"), "no isLightOn key round-trips at this band");

        NBTTagCompound bottom = sectionAt(back, minSectionY);
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.BLOCK_LIGHT_FILL),
                bottom.getByteArray("BlockLight"), "bottom section block light survives");
        assertArrayEquals(SyntheticChunks.lightFill(SyntheticChunks.SKY_LIGHT_FILL),
                bottom.getByteArray("SkyLight"), "bottom section sky light survives");

        // The below-chunk light pad (a null-section, sky-only entry) has no home in this band's 0..15 block column,
        // so the codec drops it.
        int padY = minSectionY - 1;
        NBTTagCompound sectionsHolder = back.getCompoundTag("Level");
        boolean padWritten = false;
        for (int i = 0; i < sectionsHolder.getTagList("Sections", 10).tagCount(); i++) {
            NBTTagCompound section = sectionsHolder.getTagList("Sections", 10).getCompoundTagAt(i);
            if (section.hasKey("Y") && section.getByte("Y") == padY) {
                padWritten = true;
            }
        }
        assertFalse(padWritten, "the below-chunk light pad is dropped at this band");

        assertSectionsDecode(back);
    }

    /** The written section tag with the given Y, failing the test if absent. */
    private static NBTTagCompound sectionAt(NBTTagCompound chunkTag, int sectionY) {
        NBTTagCompound level = chunkTag.getCompoundTag("Level");
        for (int i = 0; i < level.getTagList("Sections", 10).tagCount(); i++) {
            NBTTagCompound section = level.getTagList("Sections", 10).getCompoundTagAt(i);
            if ((section.hasKey("Y") ? section.getByte("Y") : Byte.MIN_VALUE) == sectionY) {
                return section;
            }
        }
        throw new AssertionError("no written section at Y=" + sectionY);
    }

    /**
     * Serverless self-consistency: every written section's block-state bytes decode through the same vanilla
     * {@code BlockStateContainer.setDataFromNBT} the real {@code AnvilChunkLoader} read uses, and every biome id is a
     * real byte value.
     */
    private static void assertSectionsDecode(NBTTagCompound chunkTag) {
        NBTTagCompound level = chunkTag.getCompoundTag("Level");
        NBTTagList sections = level.getTagList("Sections", 10);
        for (int i = 0; i < sections.tagCount(); i++) {
            NBTTagCompound section = sections.getCompoundTagAt(i);
            assertTrue(section.hasKey("Blocks"), "every written section carries the flat Blocks byte array");
            assertEquals(4096, section.getByteArray("Blocks").length, "Blocks is the flat numeric byte[4096]");
            assertTrue(section.hasKey("Data"), "every written section carries its Data nibble array");
            assertFalse(section.hasKey("Palette"), "no post-Flattening Palette is written");
            assertFalse(section.hasKey("BlockStates"), "no post-Flattening packed BlockStates is written");

            // The decode throws on malformed block-state data, so reaching the next line proves the section
            // decodes; this is the exact call AnvilChunkLoader.readChunkFromNBT makes on load.
            byte[] blocks = section.getByteArray("Blocks");
            NibbleArray data = new NibbleArray(section.getByteArray("Data"));
            NibbleArray add = section.hasKey("Add", 7) ? new NibbleArray(section.getByteArray("Add")) : null;
            new ExtendedBlockStorage(section.getByte("Y") << 4, true).getData().setDataFromNBT(blocks, data, add);
        }
        byte[] biomeIds = level.getByteArray("Biomes");
        assertEquals(16 * 16, biomeIds.length, "biomes are the flat 16x16 per-column byte grid at this band");
    }
}
