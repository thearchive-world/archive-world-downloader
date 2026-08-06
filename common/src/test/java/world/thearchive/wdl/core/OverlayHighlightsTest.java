// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OverlayHighlightsTest {
    private static final int ALPHA = 0x64000000;

    @Test
    void savedAndNotCoveredIsTaggedSuspect() {
        Long2LongMap tags = OverlayHighlights.tag(new long[] { 1L, 2L, 3L }, new long[] { 2L });
        // SUSPECT is 0, which is also the Long2LongMap default-return for an absent key, so a plain get() cannot
        // tell "tagged suspect" from "not in the map". Assert the suspect chunks are real keys: a supplier that
        // dropped them would leave them undrawn rather than suspect-toned.
        assertEquals(3, tags.size());
        assertTrue(tags.containsKey(1L));
        assertTrue(tags.containsKey(3L));
        assertEquals(OverlayHighlights.SUSPECT, tags.get(1L));
        assertEquals(OverlayHighlights.COVERED, tags.get(2L));
        assertEquals(OverlayHighlights.SUSPECT, tags.get(3L));
    }

    @Test
    void everySavedChunkIsTaggedAndCoveredOnlyChunksAreDropped() {
        // A covered chunk that is not saved (e.g. an in-range chunk the server never sent terrain for) is not
        // drawn, so it is absent from the map; every saved chunk gets exactly one label.
        Long2LongMap tags = OverlayHighlights.tag(new long[] { 1L, 2L }, new long[] { 1L, 2L, 9L });
        assertEquals(2, tags.size());
        assertEquals(OverlayHighlights.COVERED, tags.get(1L));
        assertEquals(OverlayHighlights.COVERED, tags.get(2L));
    }

    @Test
    void emptySavedYieldsEmptyMap() {
        assertTrue(OverlayHighlights.tag(new long[0], new long[] { 1L }).isEmpty());
    }

    @Test
    void eachToneResolvesToItsOwnLiveHue() {
        int covered = MarkerHue.TEAL.rgb();
        int suspect = MarkerHue.AMBER.rgb();
        assertEquals(ALPHA | covered,
                OverlayHighlights.toneColor(OverlayHighlights.COVERED, covered, suspect, ALPHA));
        assertEquals(ALPHA | suspect,
                OverlayHighlights.toneColor(OverlayHighlights.SUSPECT, covered, suspect, ALPHA));
    }

    @Test
    void partitionSplitsSavedIntoCoveredAndSuspect() {
        long[] saved = { 1L, 2L, 3L };
        long[] covered = { 2L, 3L, 99L }; // 99 is covered-not-saved and must be dropped
        OverlayHighlights.TonePartition partition = OverlayHighlights.partitionTones(saved, covered);
        long[] coveredChunks = partition.covered();
        long[] suspectChunks = partition.suspect();
        Arrays.sort(coveredChunks);
        Arrays.sort(suspectChunks);
        assertArrayEquals(new long[] { 2L, 3L }, coveredChunks);
        assertArrayEquals(new long[] { 1L }, suspectChunks);
    }

    @Test
    void calibrationMirrorYieldsEmptySuspect() {
        long[] saved = { 1L, 2L, 3L };
        OverlayHighlights.TonePartition partition = OverlayHighlights.partitionTones(saved, saved.clone());
        assertEquals(0, partition.suspect().length);
        assertEquals(3, partition.covered().length);
    }

    @Test
    void emptySavedYieldsEmptyPartition() {
        OverlayHighlights.TonePartition partition = OverlayHighlights.partitionTones(new long[0], new long[] { 1L });
        assertEquals(0, partition.covered().length);
        assertEquals(0, partition.suspect().length);
    }

    @Test
    void partitionAgreesWithTag() {
        // The coexistence guarantee: the JourneyMap partition and the XaeroPlus per-chunk tag must label
        // identically, both saved-anchored, so the two providers never disagree on a chunk's tone.
        long[] saved = { 1L, 2L, 3L, 4L };
        long[] covered = { 2L, 4L, 99L };
        OverlayHighlights.TonePartition partition = OverlayHighlights.partitionTones(saved, covered);
        Long2LongMap tagged = OverlayHighlights.tag(saved, covered);
        for (long pos : partition.covered()) {
            assertEquals(OverlayHighlights.COVERED, tagged.get(pos), "partition-covered must be tagged COVERED");
        }
        for (long pos : partition.suspect()) {
            assertEquals(OverlayHighlights.SUSPECT, tagged.get(pos), "partition-suspect must be tagged SUSPECT");
        }
    }
}
