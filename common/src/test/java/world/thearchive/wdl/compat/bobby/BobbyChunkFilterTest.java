// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.bobby;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.johni0702.minecraft.bobby.FakeChunk;
import org.junit.jupiter.api.Test;

class BobbyChunkFilterTest {
    private static final String REAL = "de.johni0702.minecraft.bobby.FakeChunk";
    private static final String ABSENT = "de.johni0702.minecraft.bobby.NoSuchClass";
    private static final ClassLoader loader = BobbyChunkFilterTest.class.getClassLoader();

    @Test
    void bobbyAbsentReturnsInactiveMatchesNothing() {
        BobbyChunkFilter filter = BobbyChunkFilter.resolve(false, REAL, loader);
        assertSame(BobbyChunkFilter.INACTIVE, filter);
        assertFalse(filter.isBobbyChunk(new FakeChunk()));
        assertFalse(filter.isBobbyChunk(new Object()));
    }

    @Test
    void bobbyPresentClassResolvesMatchesOnlyThatClass() {
        BobbyChunkFilter filter = BobbyChunkFilter.resolve(true, REAL, loader);
        assertTrue(filter.isBobbyChunk(new FakeChunk()));
        assertFalse(filter.isBobbyChunk(new Object()));
        assertFalse(filter.isBobbyChunk("not a chunk"));
    }

    @Test
    void bobbyPresentClassUnresolvableReturnsInactive() {
        BobbyChunkFilter filter = BobbyChunkFilter.resolve(true, ABSENT, loader);
        assertSame(BobbyChunkFilter.INACTIVE, filter);
        assertFalse(filter.isBobbyChunk(new FakeChunk()));
    }
}
