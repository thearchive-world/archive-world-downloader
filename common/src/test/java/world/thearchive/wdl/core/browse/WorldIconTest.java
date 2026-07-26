// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static world.thearchive.wdl.testsupport.PngFixtures.png;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WorldIconTest {
    @Test
    void rejectsBytesWithoutThePngSignature() {
        byte[] notPng = { 'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0, 'I', 'H', 'D', 'R',
                0, 0, 0, 64, 0, 0, 0, 64 };
        assertNull(WorldIcon.validate(notPng));
    }

    @Test
    void acceptsValidSmallPng() {
        byte[] png = png(64, 64);
        assertArrayEquals(png, WorldIcon.validate(png));
    }

    @Test
    void rejectsNullBytes() {
        assertNull(WorldIcon.validate(null));
    }

    @Test
    void rejectsBytesTooShortToCarryAnIhdrHeader() {
        assertNull(WorldIcon.validate(Arrays.copyOf(png(64, 64), 16)));
    }

    @Test
    void rejectsFirstChunkNotIhdr() {
        byte[] png = png(64, 64);
        png[12] = 'I';
        png[13] = 'D';
        png[14] = 'A';
        png[15] = 'T';
        assertNull(WorldIcon.validate(png));
    }

    @Test
    void rejectsZeroDimensions() {
        assertNull(WorldIcon.validate(png(0, 64)));
    }

    @Test
    void rejectsOversizedDimensionsInSmallFile() {
        assertNull(WorldIcon.validate(png(70000, 70000)));
    }

    @Test
    void rejectsValidPngThatIsNot64x64() {
        assertNull(WorldIcon.validate(png(128, 128)), "the view's FaviconTexture only accepts 64x64");
    }

    @Test
    void rejectsFileLargerThanByteCap() {
        assertNull(WorldIcon.validate(Arrays.copyOf(png(64, 64), WorldIcon.MAX_BYTES + 1)));
    }

    @Test
    void acceptsFileExactlyAtByteCap() {
        byte[] atCap = Arrays.copyOf(png(64, 64), WorldIcon.MAX_BYTES);
        assertArrayEquals(atCap, WorldIcon.validate(atCap), "the cap is inclusive (length > cap rejects)");
    }
}
