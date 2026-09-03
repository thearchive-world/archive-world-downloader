// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;
import world.thearchive.wdl.testsupport.HeadlessLevel;

/**
 * The sky-light content gate: pins what {@link ChunkCodecImpl#capture} puts under a section's {@code SkyLight} key in
 * each dimension, driven through the real per-dimension provider rather than a stub, because a stub would assert this
 * band's own reading of {@code hasNoSky} against itself and stay green however the read is spelled.
 *
 * <p>It asserts content, not presence. Vanilla writes {@code SkyLight} in every section of every dimension, its else
 * arm a zero-filled array of the block layer's length, and the encode reproduces that, so a presence assertion is false
 * of the on-disk format and would red on correct code. The defect this exists to catch is a sky layer that is present,
 * exactly 2048 bytes and entirely zero where the live chunk's is lit: a save the loader accepts and no reopen repairs,
 * because {@code LightPopulated} is captured true and vanilla relights only an unlit terrain-populated chunk.
 */
class ChunkSkyLightCaptureTest {
    private static final int SECTION_Y = 0;
    private static final int SECTION_LAYER_BYTES = 2048;

    // The two fills differ, and neither is zero, so "carries the live sky layer", "is not the zero-filled layer" and
    // "did not read the block layer instead" are three assertions that can fail apart rather than one repeated.
    private static final byte SKY_FILL = (byte) 0xB7;
    private static final byte BLOCK_FILL = (byte) 0x4C;

    private final ChunkCodec codec = new ChunkCodecImpl();

    @Test
    void overworldSectionCarriesTheLiveSkyLayer() {
        NBTTagCompound section = captureAndEncodeOneSection(DimensionType.OVERWORLD, true);
        byte[] skyLight = section.getByteArray("SkyLight");

        assertArrayEquals(filled(SKY_FILL), skyLight,
                "an overworld section's SkyLight must be the live sky layer");
        assertFalse(Arrays.equals(filled((byte) 0), skyLight),
                "an overworld section's SkyLight must not be the zero-filled layer, which darkens the chunk for good");
        assertArrayEquals(filled(BLOCK_FILL), section.getByteArray("BlockLight"),
                "an overworld section's BlockLight must be the live block layer, not the sky layer");
    }

    @Test
    void netherSectionCarriesThePresentZeroFilledSkyLayer() {
        assertSkylessSection(DimensionType.NETHER);
    }

    /**
     * The end reads the same field the nether does at this band, so this arm is what separates the correct read from a
     * nether-only dimension test that would otherwise pass both other arms.
     */
    @Test
    void endSectionCarriesThePresentZeroFilledSkyLayer() {
        assertSkylessSection(DimensionType.THE_END);
    }

    private void assertSkylessSection(DimensionType dimension) {
        NBTTagCompound section = captureAndEncodeOneSection(dimension, false);

        assertTrue(section.hasKey("SkyLight"),
                "a section without sky light must still carry the SkyLight key, as vanilla's own write does");
        assertArrayEquals(filled((byte) 0), section.getByteArray("SkyLight"),
                "a section without sky light must carry exactly 2048 zero bytes");
        assertArrayEquals(filled(BLOCK_FILL), section.getByteArray("BlockLight"),
                "a dimension without sky light still has real block light, which must survive the capture");
    }

    /**
     * Capture and encode a one-section chunk in {@code dimension}, and return that section's tag. The section is built
     * with {@code storeSkylight} matching the dimension, the way a live client chunk is, so the nether and end arms
     * fail against a capture that assumes every section has a sky layer instead of failing only on the content.
     */
    private NBTTagCompound captureAndEncodeOneSection(DimensionType dimension, boolean storeSkylight) {
        World level = HeadlessLevel.get(dimension);
        Chunk chunk = new Chunk(level, 0, 0);

        ExtendedBlockStorage live = new ExtendedBlockStorage(SECTION_Y << 4, storeSkylight);
        live.set(0, 0, 0, Blocks.STONE.getDefaultState());
        live.setBlocklightArray(new NibbleArray(filled(BLOCK_FILL)));
        if (storeSkylight) {
            live.setSkylightArray(new NibbleArray(filled(SKY_FILL)));
        }
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[chunk.getBlockStorageArray().length];
        sections[SECTION_Y] = live;
        chunk.setStorageArrays(sections);

        ChunkSnapshotSource snapshot = codec.capture(chunk);
        NBTTagList encoded = codec.encode(snapshot, false).getCompoundTag("Level").getTagList("Sections", 10);
        assertEquals(1, encoded.tagCount(), "the fixture's one non-empty section must be the one section encoded");
        return encoded.getCompoundTagAt(0);
    }

    private static byte[] filled(byte value) {
        byte[] layer = new byte[SECTION_LAYER_BYTES];
        Arrays.fill(layer, value);
        return layer;
    }
}
