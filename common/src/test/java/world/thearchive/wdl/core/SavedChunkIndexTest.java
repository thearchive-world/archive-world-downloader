// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SavedChunkIndexTest {
    private static long[] sorted(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    @Test
    void addThenSnapshotReturnsThePositions() {
        SavedChunkIndex index = new SavedChunkIndex();
        index.add("minecraft:overworld", 5L);
        index.add("minecraft:overworld", 9L);
        assertArrayEquals(new long[] { 5L, 9L }, sorted(index.snapshot("minecraft:overworld")));
    }

    @Test
    void snapshotOfAnUnknownDimensionIsEmpty() {
        assertEquals(0, new SavedChunkIndex().snapshot("minecraft:the_nether").length);
    }

    @Test
    void dimensionsStayPartitionedByTheLiveKey() {
        // The overworld and nether share the position space; keying by the live id keeps them separate. A
        // Multiverse-style custom id is just another String key.
        SavedChunkIndex index = new SavedChunkIndex();
        index.add("minecraft:overworld", 5L);
        index.add("minecraft:worlds/2b2t/2b2t_1", 5L);
        index.add("minecraft:worlds/2b2t/2b2t_1", 7L);
        assertArrayEquals(new long[] { 5L }, sorted(index.snapshot("minecraft:overworld")));
        assertArrayEquals(new long[] { 5L, 7L }, sorted(index.snapshot("minecraft:worlds/2b2t/2b2t_1")));
    }

    @Test
    void addAllSeedsUnderTheLiveKeyAndUnionsWithLiveAdds() {
        // The resume seed hands a batch of prior on-disk positions; a later live add unions in, deduped.
        SavedChunkIndex index = new SavedChunkIndex();
        index.addAll("minecraft:overworld", new long[] { 5L, 9L, 5L });
        index.add("minecraft:overworld", 12L);
        assertArrayEquals(new long[] { 5L, 9L, 12L }, sorted(index.snapshot("minecraft:overworld")));
    }

    @Test
    void reAddingTheSamePositionIsIdempotent() {
        // A revisit re-buffers a chunk already in the overlay, so re-adding its position must not double-count it.
        SavedChunkIndex index = new SavedChunkIndex();
        index.add("minecraft:overworld", 5L);
        index.add("minecraft:overworld", 5L);
        assertArrayEquals(new long[] { 5L }, sorted(index.snapshot("minecraft:overworld")));
    }

    @Test
    void addAllOfAnEmptyBatchDoesNothing() {
        SavedChunkIndex index = new SavedChunkIndex();
        index.addAll("minecraft:overworld", new long[0]);
        assertEquals(0, index.snapshot("minecraft:overworld").length);
    }

    @Test
    void snapshotIsCopyNotLiveView() {
        SavedChunkIndex index = new SavedChunkIndex();
        index.add("minecraft:overworld", 5L);
        long[] taken = index.snapshot("minecraft:overworld");
        index.add("minecraft:overworld", 9L); // must not mutate the already-taken snapshot
        assertArrayEquals(new long[] { 5L }, taken);
    }

    @Test
    void clearEmptiesEveryDimension() {
        SavedChunkIndex index = new SavedChunkIndex();
        index.add("minecraft:overworld", 5L);
        index.add("minecraft:the_nether", 7L);
        index.clear();
        assertEquals(0, index.snapshot("minecraft:overworld").length);
        assertEquals(0, index.snapshot("minecraft:the_nether").length);
    }

    @Test
    void versionRisesOnAddAndClearAndHoldsOtherwise() {
        SavedChunkIndex index = new SavedChunkIndex();
        long v0 = index.version();
        index.add("minecraft:overworld", 1L);
        long v1 = index.version();
        assertTrue(v1 > v0, "add bumps the version");
        index.snapshot("minecraft:overworld");
        assertEquals(v1, index.version(), "a read does not bump the version");
        index.addAll("minecraft:overworld", new long[] { 2L });
        assertTrue(index.version() > v1, "a batch seed bumps the version, invalidating version-keyed snapshots");
        index.clear();
        assertTrue(index.version() > v1, "clear bumps the version");
    }
}
