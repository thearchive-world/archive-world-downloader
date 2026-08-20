// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The pack and unpack round-trip for the section-key bit scheme, the {@code SectionPos} stand-in at this band. The
 * producer packs a section and the per-band renderer unpacks it through this one class, so the encode and decode must
 * not drift: every section coordinate in the 22/20/22-bit signed range recovers exactly, sign included.
 */
class SectionKeyTest {
    // The signed extents of the packed fields: x and z are 22 bits, y is 20 bits.
    private static final int[] XZ = { 0, 1, -1, 42, -42, (1 << 21) - 1, -(1 << 21) };
    private static final int[] YS = { 0, 1, -1, 7, -7, (1 << 19) - 1, -(1 << 19) };

    @Test
    void sectionCoordinatesRoundTripAcrossTheirSignedRange() {
        for (int x : XZ) {
            for (int y : YS) {
                for (int z : XZ) {
                    long key = SectionKey.asLong(x, y, z);
                    assertEquals(x, SectionKey.x(key), "x recovers");
                    assertEquals(y, SectionKey.y(key), "y recovers");
                    assertEquals(z, SectionKey.z(key), "z recovers");
                }
            }
        }
    }

    @Test
    void distinctSectionsPackToDistinctKeys() {
        long base = SectionKey.asLong(1, 2, 3);
        assertNotEquals(base, SectionKey.asLong(1, 2, 4), "a different z packs to a different key");
        assertNotEquals(base, SectionKey.asLong(1, 5, 3), "a different y packs to a different key");
        assertNotEquals(base, SectionKey.asLong(-1, 2, 3), "a different x packs to a different key");
    }

    @Test
    void blockKeyMapsToItsSectionByFlooringDivisionBySixteen() {
        long blockKey = new BlockPos(33, -5, 16).asLong();
        long section = SectionKey.blockToSection(blockKey);
        assertEquals(2, SectionKey.x(section), "block x floor-divides by 16");
        assertEquals(-1, SectionKey.y(section), "a negative block y floors rather than truncating toward zero");
        assertEquals(1, SectionKey.z(section), "block z floor-divides by 16");
    }

    @Test
    void sectionToBlockCoordIsTheLowCornerOfTheSection() {
        assertEquals(32, SectionKey.sectionToBlockCoord(2), "section 2 low corner is block 32");
        assertEquals(-16, SectionKey.sectionToBlockCoord(-1), "section -1 low corner is block -16");
    }
}
