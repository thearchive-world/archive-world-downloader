// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CoveredChunkIndexTest {
    private static long[] sorted(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    private static boolean contains(long[] values, long target) {
        for (long value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    @Test
    void addAllThenSnapshotReturnsThePositions() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addAll("minecraft:overworld", new long[] { 5L, 9L });
        assertArrayEquals(new long[] { 5L, 9L }, sorted(index.snapshot("minecraft:overworld")));
    }

    @Test
    void snapshotOfAnUnknownDimensionIsEmpty() {
        assertEquals(0, new CoveredChunkIndex().snapshot("minecraft:the_nether").length);
    }

    @Test
    void discCoversAtRadiusAndExcludesBeyondIt() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addDisc("minecraft:overworld", 0, 0, 3);
        long[] disc = index.snapshot("minecraft:overworld");
        assertTrue(contains(disc, RegionMath.chunkAsLong(0, 0)), "the center chunk must be covered");
        assertTrue(contains(disc, RegionMath.chunkAsLong(3, 0)), "a chunk at R on the axis must be covered");
        assertTrue(contains(disc, RegionMath.chunkAsLong(0, 3)), "a chunk at R on the axis must be covered");
        assertFalse(contains(disc, RegionMath.chunkAsLong(4, 0)), "a chunk at R+1 on the axis must not be covered");
        assertFalse(contains(disc, RegionMath.chunkAsLong(3, 1)),
                "a chunk whose Euclidean distance exceeds R must not be covered");
    }

    @Test
    void discBoundaryIsEuclideanNotChebyshev() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addDisc("minecraft:overworld", 0, 0, 5);
        long[] disc = index.snapshot("minecraft:overworld");
        assertTrue(contains(disc, RegionMath.chunkAsLong(3, 4)), "the 3-4-5 boundary chunk must be covered");
        assertTrue(contains(disc, RegionMath.chunkAsLong(4, 3)), "the 3-4-5 boundary chunk must be covered");
        assertFalse(contains(disc, RegionMath.chunkAsLong(3, 5)), "just outside the disc must not be covered");
        assertFalse(contains(disc, RegionMath.chunkAsLong(5, 5)), "the square corner must not be covered");
    }

    @Test
    void discAtTheTenChunkCapCoversTheSendRange() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addDisc("minecraft:overworld", 0, 0, 10);
        long[] disc = index.snapshot("minecraft:overworld");
        assertTrue(contains(disc, RegionMath.chunkAsLong(10, 0)), "a chunk at R=10 on the axis must be covered");
        assertTrue(contains(disc, RegionMath.chunkAsLong(6, 8)), "the 6-8-10 boundary chunk must be covered");
        assertFalse(contains(disc, RegionMath.chunkAsLong(11, 0)), "a chunk beyond R=10 must not be covered");
        assertFalse(contains(disc, RegionMath.chunkAsLong(7, 8)), "a chunk beyond the R=10 disc must not be covered");
    }

    @Test
    void discIsCenteredOnTheGivenChunk() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addDisc("minecraft:overworld", 100, -50, 2);
        long[] disc = index.snapshot("minecraft:overworld");
        assertTrue(contains(disc, RegionMath.chunkAsLong(100, -50)), "the off-origin center must be covered");
        assertTrue(contains(disc, RegionMath.chunkAsLong(102, -50)), "a chunk at R from the off-origin center");
        assertFalse(contains(disc, RegionMath.chunkAsLong(103, -50)), "a chunk at R+1 from the off-origin center");
    }

    @Test
    void overlappingDiscsUnionAndDedup() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addDisc("minecraft:overworld", 0, 0, 1);
        index.addDisc("minecraft:overworld", 1, 0, 1);
        long[] disc = index.snapshot("minecraft:overworld");
        assertEquals(8, disc.length);
        assertTrue(contains(disc, RegionMath.chunkAsLong(-1, 0)));
        assertTrue(contains(disc, RegionMath.chunkAsLong(2, 0)));
    }

    @Test
    void dimensionsStayPartitionedByTheLiveKey() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addDisc("minecraft:overworld", 0, 0, 0);
        index.addDisc("minecraft:worlds/2b2t/2b2t_1", 5, 0, 0);
        assertArrayEquals(new long[] { RegionMath.chunkAsLong(0, 0) }, index.snapshot("minecraft:overworld"));
        assertArrayEquals(new long[] { RegionMath.chunkAsLong(5, 0) }, index.snapshot("minecraft:worlds/2b2t/2b2t_1"));
    }

    @Test
    void addAllSeedsUnderTheLiveKeyAndUnionsDeduped() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addAll("minecraft:overworld", new long[] { 5L, 9L, 5L });
        index.addAll("minecraft:overworld", new long[] { 12L, 9L });
        assertArrayEquals(new long[] { 5L, 9L, 12L }, sorted(index.snapshot("minecraft:overworld")));
    }

    @Test
    void addAllOfAnEmptyBatchDoesNothing() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addAll("minecraft:overworld", new long[0]);
        assertEquals(0, index.snapshot("minecraft:overworld").length);
    }

    @Test
    void snapshotIsCopyNotLiveView() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addAll("minecraft:overworld", new long[] { 5L });
        long[] taken = index.snapshot("minecraft:overworld");
        index.addAll("minecraft:overworld", new long[] { 9L });
        assertArrayEquals(new long[] { 5L }, taken);
    }

    @Test
    void clearEmptiesEveryDimension() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addAll("minecraft:overworld", new long[] { 5L });
        index.addAll("minecraft:the_nether", new long[] { 7L });
        index.clear();
        assertEquals(0, index.snapshot("minecraft:overworld").length);
        assertEquals(0, index.snapshot("minecraft:the_nether").length);
    }

    @Test
    void recomputeRebuildsCoveredFromTheTrailAtTheNewRadius() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.recordTrail("minecraft:overworld", 0, 0, 32);
        index.recordTrail("minecraft:overworld", 6, 0, 32);
        index.addDisc("minecraft:overworld", 0, 0, 1);
        index.recompute("minecraft:overworld", 2);
        long[] covered = index.snapshot("minecraft:overworld");
        assertTrue(contains(covered, RegionMath.chunkAsLong(2, 0)));
        assertTrue(contains(covered, RegionMath.chunkAsLong(8, 0)));
        assertFalse(contains(covered, RegionMath.chunkAsLong(3, 0)));
    }

    @Test
    void recomputeOverLongDryTrailRevealsCoverageAtFirstCalibration() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        for (int x = 0; x <= 20; x++) {
            index.recordTrail("minecraft:overworld", x, 0, 32);
        }
        index.recompute("minecraft:overworld", 1);
        long[] covered = index.snapshot("minecraft:overworld");
        assertTrue(contains(covered, RegionMath.chunkAsLong(0, 0)));
        assertTrue(contains(covered, RegionMath.chunkAsLong(20, 0)));
    }

    @Test
    void recomputeKeepsTheResumeSeedNotDerivedFromTheTrail() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addAll("minecraft:overworld", new long[] { RegionMath.chunkAsLong(100, 100) });
        index.recordTrail("minecraft:overworld", 0, 0, 32);
        index.recompute("minecraft:overworld", 2);
        long[] covered = index.snapshot("minecraft:overworld");
        assertTrue(contains(covered, RegionMath.chunkAsLong(0, 0)), "the trail disc must be covered");
        assertTrue(contains(covered, RegionMath.chunkAsLong(100, 100)), "the resume seed must survive the rebuild");
    }

    @Test
    void recomputeShrinksTrailCoverageWhileKeepingTheSeed() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addAll("minecraft:overworld", new long[] { RegionMath.chunkAsLong(100, 100) });
        index.recordTrail("minecraft:overworld", 0, 0, 32);
        index.recompute("minecraft:overworld", 3);
        assertTrue(contains(index.snapshot("minecraft:overworld"), RegionMath.chunkAsLong(3, 0)),
                "a chunk at radius 3 is covered before the shrink");
        index.recompute("minecraft:overworld", 1);
        long[] covered = index.snapshot("minecraft:overworld");
        assertFalse(contains(covered, RegionMath.chunkAsLong(3, 0)),
                "the far trail-disc chunk must be gone after the shrink to radius 1");
        assertTrue(contains(covered, RegionMath.chunkAsLong(0, 0)), "the trail center stays covered at radius 1");
        assertTrue(contains(covered, RegionMath.chunkAsLong(100, 100)), "the resume seed must survive the shrink");
    }

    @Test
    void recomputeReplacesStaleAddDiscTrailCoverage() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.recordTrail("minecraft:overworld", 0, 0, 32);
        index.addDisc("minecraft:overworld", 0, 0, 3);
        index.recompute("minecraft:overworld", 1);
        long[] covered = index.snapshot("minecraft:overworld");
        assertTrue(contains(covered, RegionMath.chunkAsLong(0, 0)), "the trail center stays covered");
        assertFalse(contains(covered, RegionMath.chunkAsLong(3, 0)),
                "the stale radius-3 disc chunk must be replaced by the radius-1 recompute, not retained");
    }

    @Test
    void clearDropsTrailsSoRecomputeIsEmptyAfterward() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.recordTrail("minecraft:overworld", 0, 0, 32);
        index.clear();
        index.recompute("minecraft:overworld", 5);
        assertEquals(0, index.snapshot("minecraft:overworld").length);
    }

    @Test
    void recomputePaintsEachCenterAtItsRecordedCap() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.recordTrail("minecraft:overworld", 0, 0, 10);
        index.recordTrail("minecraft:overworld", 100, 0, 4);
        index.recompute("minecraft:overworld", 9);
        long[] covered = index.snapshot("minecraft:overworld");
        assertTrue(contains(covered, RegionMath.chunkAsLong(104, 0)));
        assertFalse(contains(covered, RegionMath.chunkAsLong(109, 0)));
        assertTrue(contains(covered, RegionMath.chunkAsLong(9, 0)));
    }

    @Test
    void reCrossMaxMergesTheStoredCap() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.recordTrail("minecraft:overworld", 100, 0, 4);
        index.recordTrail("minecraft:overworld", 100, 0, 10);
        index.recompute("minecraft:overworld", 9);
        assertTrue(contains(index.snapshot("minecraft:overworld"), RegionMath.chunkAsLong(109, 0)));
    }

    @Test
    void reCrossWithLowerCapKeepsTheHigherStoredCap() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.recordTrail("minecraft:overworld", 100, 0, 10);
        index.recordTrail("minecraft:overworld", 100, 0, 4);
        index.recompute("minecraft:overworld", 9);
        assertTrue(contains(index.snapshot("minecraft:overworld"), RegionMath.chunkAsLong(109, 0)));
    }

    @Test
    void reRecordingAfterClearRepaintsTheSameCenter() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.recordTrail("minecraft:overworld", 0, 0, 5);
        index.recompute("minecraft:overworld", 5);
        assertTrue(contains(index.snapshot("minecraft:overworld"), RegionMath.chunkAsLong(0, 0)));
        index.clear();
        assertEquals(0, index.snapshot("minecraft:overworld").length);
        index.recordTrail("minecraft:overworld", 0, 0, 5);
        index.recompute("minecraft:overworld", 5);
        assertTrue(contains(index.snapshot("minecraft:overworld"), RegionMath.chunkAsLong(0, 0)),
                "a stale cap-map entry surviving the clear would suppress the trail re-append, so nothing repaints");
    }

    @Test
    void versionRisesOnTheCoverageMutators() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        long v0 = index.version();
        index.addDisc("minecraft:overworld", 0, 0, 2);
        long v1 = index.version();
        assertTrue(v1 > v0, "addDisc bumps the version (coverage grows while stationary)");
        index.recordTrail("minecraft:overworld", 0, 0, 100);
        assertEquals(v1, index.version(), "recordTrail is trail bookkeeping only: no snapshot change, no bump");
        index.recompute("minecraft:overworld", 3);
        assertTrue(index.version() > v1, "recompute bumps the version (it runs with the trail recorded above)");
    }

    @Test
    void versionRisesOnAddAllAndClear() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        long v0 = index.version();
        index.addAll("minecraft:overworld", new long[0]);
        long v1 = index.version();
        assertTrue(v1 > v0, "addAll bumps the version before its empty-batch early return");
        index.addAll("minecraft:overworld", new long[] { 5L });
        long v2 = index.version();
        assertTrue(v2 > v1, "a non-empty addAll bumps the version");
        index.clear();
        assertTrue(index.version() > v2, "clear bumps the version");
    }
}
