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
        // A chunk on the axis at exactly R is covered; at R+1 it is not (the boundary of the send-range disc).
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
        // R=5 gives an exact 3-4-5 boundary: (3,4) sits on the disc edge and is covered; the square corner (5,5)
        // and (3,5) sit outside the Euclidean disc and are not, so the disc is a circle, not a square.
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
        // R=10 is the vanilla decoration send range: a chunk at Euclidean distance 10 is covered, beyond is not.
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
        // The disc is placed around the passed center, not the origin, so an off-origin path covers around itself.
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addDisc("minecraft:overworld", 100, -50, 2);
        long[] disc = index.snapshot("minecraft:overworld");
        assertTrue(contains(disc, RegionMath.chunkAsLong(100, -50)), "the off-origin center must be covered");
        assertTrue(contains(disc, RegionMath.chunkAsLong(102, -50)), "a chunk at R from the off-origin center");
        assertFalse(contains(disc, RegionMath.chunkAsLong(103, -50)), "a chunk at R+1 from the off-origin center");
    }

    @Test
    void overlappingDiscsUnionAndDedup() {
        // A path sweeps several centers; the covered set is the union, with the shared chunks counted once.
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addDisc("minecraft:overworld", 0, 0, 1);
        index.addDisc("minecraft:overworld", 1, 0, 1);
        long[] disc = index.snapshot("minecraft:overworld");
        // Each R=1 disc is a plus of 5 chunks sharing (0,0)/(1,0)/... ; the union is the 8 distinct positions.
        assertEquals(8, disc.length);
        assertTrue(contains(disc, RegionMath.chunkAsLong(-1, 0)));
        assertTrue(contains(disc, RegionMath.chunkAsLong(2, 0)));
    }

    @Test
    void dimensionsStayPartitionedByTheLiveKey() {
        // The overworld and nether share the position space; keying by the live id keeps them separate. A
        // Multiverse-style custom id is just another String key.
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addDisc("minecraft:overworld", 0, 0, 0);
        index.addDisc("minecraft:worlds/2b2t/2b2t_1", 5, 0, 0);
        assertArrayEquals(new long[] { RegionMath.chunkAsLong(0, 0) },
                index.snapshot("minecraft:overworld"));
        assertArrayEquals(new long[] { RegionMath.chunkAsLong(5, 0) },
                index.snapshot("minecraft:worlds/2b2t/2b2t_1"));
    }

    @Test
    void addAllSeedsUnderTheLiveKeyAndUnionsDeduped() {
        // The resume seed hands a batch of prior on-disk positions (drawn covered, never suspect); a later batch
        // unions in, deduped.
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
        index.addAll("minecraft:overworld", new long[] { 9L }); // must not mutate the already-taken snapshot
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
        index.addDisc("minecraft:overworld", 0, 0, 1); // an early small-radius disc
        index.recompute("minecraft:overworld", 2);     // grow to radius 2 over the whole trail
        long[] covered = index.snapshot("minecraft:overworld");
        // radius-2 discs around (0,0) and (6,0): (2,0) and (8,0) are the far axis members, present now.
        assertTrue(contains(covered, RegionMath.chunkAsLong(2, 0)));
        assertTrue(contains(covered, RegionMath.chunkAsLong(8, 0)));
        // (3,0) sits between the two discs, farther than 2 from either center, so it is NOT covered.
        assertFalse(contains(covered, RegionMath.chunkAsLong(3, 0)));
    }

    @Test
    void recomputeOverLongDryTrailRevealsCoverageAtFirstCalibration() {
        // A trail recorded before any radius was known: recompute at the first radius covers the whole trail.
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
        // The resume seed (addAll'd once on resume, a prior session's on-disk coverage) lives in its own set apart
        // from trail-derived coverage, so a recompute that clears and rebuilds the trail-derived set leaves it
        // intact; the snapshot is the union of both.
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.addAll("minecraft:overworld", new long[] { RegionMath.chunkAsLong(100, 100) });
        index.recordTrail("minecraft:overworld", 0, 0, 32);
        index.recompute("minecraft:overworld", 2);
        long[] covered = index.snapshot("minecraft:overworld");
        assertTrue(contains(covered, RegionMath.chunkAsLong(0, 0)), "the trail disc must be covered");
        assertTrue(contains(covered, RegionMath.chunkAsLong(100, 100)),
                "the resume seed must survive the rebuild");
    }

    @Test
    void recomputeShrinksTrailCoverageWhileKeepingTheSeed() {
        // A lower effective render distance clamps the running max down: recompute at the smaller
        // radius must contract the trail-derived coverage (a far disc chunk gone), and the seed must survive.
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
        // addDisc writes the trail-derived set, so an earlier larger disc laid before the range shrank must be
        // replaced by a smaller recompute, not unioned into it (safe because the resume seed lives apart from
        // the trail-derived set).
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.recordTrail("minecraft:overworld", 0, 0, 32);
        index.addDisc("minecraft:overworld", 0, 0, 3); // an early large disc, since superseded
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
        index.recordTrail("minecraft:overworld", 100, 0, 4); // a stretch walked at a low view distance
        index.recompute("minecraft:overworld", 9);
        long[] covered = index.snapshot("minecraft:overworld");
        // the low-cap center paints at min(9, 4) = 4: chunk (100+9, 0) must NOT be covered,
        // chunk (100+4, 0) must be.
        assertTrue(contains(covered, RegionMath.chunkAsLong(104, 0)));
        assertFalse(contains(covered, RegionMath.chunkAsLong(109, 0)));
        // the high-cap center paints at min(9, 10) = 9.
        assertTrue(contains(covered, RegionMath.chunkAsLong(9, 0)));
    }

    @Test
    void reCrossMaxMergesTheStoredCap() {
        CoveredChunkIndex index = new CoveredChunkIndex();
        index.recordTrail("minecraft:overworld", 100, 0, 4);
        index.recordTrail("minecraft:overworld", 100, 0, 10); // re-walked after restoring a higher view distance
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
        index.recordTrail("minecraft:overworld", 0, 0, 5); // re-record the same center after the clear
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
        // addAll and clear are coverage mutators too, and each bumps the version as its first statement, before any
        // early return. An empty addAll batch returns early yet must still bump, so a stale reader is not pinned to a
        // count that a later non-empty batch under the same version would silently extend.
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
