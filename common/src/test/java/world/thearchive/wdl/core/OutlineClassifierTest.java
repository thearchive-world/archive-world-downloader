// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutlineClassifierTest {
    private static final UUID CART = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static CapturedContainers capturedType(long posKey, String typeId) {
        Long2ObjectMap<String> types = new Long2ObjectOpenHashMap<>();
        types.put(posKey, typeId);
        return new CapturedContainers(new LongOpenHashSet(new long[] { posKey }), ImmutableSet.of(), false,
                new Long2IntOpenHashMap(), types);
    }

    @Test
    void capturedByBlockMembership() {
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 5L }), ImmutableSet.of(),
                false);
        assertEquals(OutlineClass.CAPTURED,
                OutlineClassifier.classify(new long[] { 5L }, null, null, false, captured, RecoveredCoverage.EMPTY));
    }

    @Test
    void capturedByEnderFlag() {
        CapturedContainers captured = new CapturedContainers(LongSets.EMPTY_SET, ImmutableSet.of(), true);
        assertEquals(OutlineClass.CAPTURED,
                OutlineClassifier.classify(new long[] { 9L }, null, null, true, captured, RecoveredCoverage.EMPTY));
    }

    @Test
    void enderChestNoRimWhenRestoredOnResume() {
        RecoveredCoverage restored = RecoveredCoverage.ENDER_ONLY;
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classify(new long[] { 9L }, null, null, true,
                CapturedContainers.EMPTY, restored));
    }

    @Test
    void enderChestUnsavedWhenNeitherCapturedNorRestored() {
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classify(new long[] { 9L }, null, null, true,
                CapturedContainers.EMPTY, RecoveredCoverage.EMPTY));
    }

    @Test
    void enderRecoveredDoesNotRimNonEnderContainers() {
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classify(new long[] { 9L }, null, null, false,
                CapturedContainers.EMPTY, RecoveredCoverage.ENDER_ONLY));
    }

    @Test
    void capturedByEntityMembership() {
        CapturedContainers captured = new CapturedContainers(LongSets.EMPTY_SET, ImmutableSet.of(CART), false);
        assertEquals(OutlineClass.CAPTURED,
                OutlineClassifier.classify(new long[] {}, null, CART, false, captured, RecoveredCoverage.EMPTY));
    }

    @Test
    void recoveredWhenPriorSessionCoveredIt() {
        RecoveredCoverage recovered = new RecoveredCoverage(new LongOpenHashSet(new long[] { 7L }));
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classify(new long[] { 7L }, null, null, false,
                CapturedContainers.EMPTY, recovered));
    }

    @Test
    void unsavedWhenNeitherCapturedNorRecovered() {
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classify(new long[] { 3L }, null, null, false,
                CapturedContainers.EMPTY, RecoveredCoverage.EMPTY));
    }

    @Test
    void recoveredWhenPriorSessionCoveredTheEntity() {
        RecoveredCoverage recovered = new RecoveredCoverage(LongSets.EMPTY_SET, new Long2IntOpenHashMap(),
                ImmutableSet.of(CART),
                false);
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classify(new long[] {}, null, CART, false,
                CapturedContainers.EMPTY, recovered));
    }

    @Test
    void capturedEntityTakesPrecedenceOverRecoveredEntity() {
        CapturedContainers captured = new CapturedContainers(LongSets.EMPTY_SET, ImmutableSet.of(CART), false);
        RecoveredCoverage recovered = new RecoveredCoverage(LongSets.EMPTY_SET, new Long2IntOpenHashMap(),
                ImmutableSet.of(CART),
                false);
        assertEquals(OutlineClass.CAPTURED,
                OutlineClassifier.classify(new long[] {}, null, CART, false, captured, recovered));
    }

    @Test
    void unsavedEntityWhenNeitherCapturedNorRecovered() {
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classify(new long[] {}, null, CART, false,
                CapturedContainers.EMPTY, RecoveredCoverage.EMPTY));
    }

    @Test
    void capturedTakesPrecedenceOverRecovered() {
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 4L }), ImmutableSet.of(),
                false);
        RecoveredCoverage recovered = new RecoveredCoverage(new LongOpenHashSet(new long[] { 4L }));
        assertEquals(OutlineClass.CAPTURED,
                OutlineClassifier.classify(new long[] { 4L }, null, null, false, captured, recovered));
    }

    @Test
    void doubleChestCapturedWhenEitherHalfIsCaptured() {
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 11L }), ImmutableSet.of(),
                false);
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classify(new long[] { 11L, 12L }, null, null, false,
                captured, RecoveredCoverage.EMPTY));
    }

    @Test
    void doubleChestRecoveredWhenEitherHalfIsRecovered() {
        RecoveredCoverage recovered = new RecoveredCoverage(new LongOpenHashSet(new long[] { 12L }));
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classify(new long[] { 11L, 12L }, null, null, false,
                CapturedContainers.EMPTY, recovered));
    }

    @Test
    void capturedBlockStaysCapturedWhenTheLiveTypeMatchesTheRecordedType() {
        CapturedContainers captured = capturedType(5L, "minecraft:barrel");
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classify(new long[] { 5L }, "minecraft:barrel", null,
                false, captured, RecoveredCoverage.EMPTY));
    }

    @Test
    void capturedBlockRerimsWhenTheLiveTypeDiffersFromTheRecordedType() {
        CapturedContainers captured = capturedType(5L, "minecraft:barrel");
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classify(new long[] { 5L }, "minecraft:chest", null,
                false, captured, RecoveredCoverage.EMPTY));
    }

    @Test
    void replacedCapturedBlockStillFallsToRecoveredWhenPriorSessionCoveredIt() {
        CapturedContainers captured = capturedType(5L, "minecraft:barrel");
        RecoveredCoverage recovered = new RecoveredCoverage(new LongOpenHashSet(new long[] { 5L }));
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classify(new long[] { 5L }, "minecraft:chest", null,
                false, captured, recovered));
    }

    @Test
    void capturedBlockWithNoRecordedTypeFallsBackToBareMembership() {
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 5L }), ImmutableSet.of(),
                false);
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classify(new long[] { 5L }, "minecraft:chest", null,
                false, captured, RecoveredCoverage.EMPTY));
    }

    @Test
    void bookshelfEmptyDrawsNoRim() {
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classifyBookshelf(0, 0, 0));
    }

    @Test
    void bookshelfCapturedWhenEveryOccupiedSlotCapturedThisSession() {
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classifyBookshelf(0b111, 0b111, 0));
    }

    @Test
    void bookshelfUnsavedWhenAnOccupiedSlotIsUncaptured() {
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classifyBookshelf(0b111, 0b011, 0));
    }

    @Test
    void bookshelfRecoveredWhenEveryOccupiedSlotSavedPriorAndUndisturbed() {
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classifyBookshelf(0b111, 0, 0b111));
    }

    @Test
    void bookshelfUnsavedWhenOnlySomeOccupiedSlotsSavedPrior() {
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classifyBookshelf(0b111, 0, 0b011));
    }

    @Test
    void bookshelfRecoveredWhenThisSessionAddedToTheSavedShelf() {
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classifyBookshelf(0b111, 0b001, 0b111));
    }

    @Test
    void bookshelfRecoveredWhenAnInsertWasRemovedAgain() {
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classifyBookshelf(0b110, 0b001, 0b111));
    }

    @Test
    void bookshelfUnsavedWhenOneSlotIsCoveredByNeitherMask() {
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classifyBookshelf(0b111, 0b001, 0b011));
    }

    @Test
    void bookshelfIgnoresCapturedBitsForUnoccupiedSlots() {
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classifyBookshelf(0b1, 0b100001, 0));
    }

    @Test
    void bookshelfIgnoresSavedBitsForUnoccupiedSlots() {
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classifyBookshelf(0b1, 0, 0b100001));
    }

    @Test
    void bookshelfHandlesAllSixSlots() {
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classifyBookshelf(0b111111, 0b111111, 0));
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classifyBookshelf(0b100000, 0, 0));
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classifyBookshelf(0b100000, 0b100000, 0));
    }

    @Test
    void bookshelfCapturedTakesPrecedenceOverRecovered() {
        assertEquals(OutlineClass.CAPTURED, OutlineClassifier.classifyBookshelf(0b111, 0b111, 0b111));
    }

    @Test
    void hueForMapsRecoveredToTheRecoveredHueAndTheRestToUnsaved() {
        OutlineConfig config = new OutlineConfig(true, 96, MarkerHue.RED, MarkerHue.VIOLET, 1.0f, false);
        assertEquals(MarkerHue.VIOLET, OutlineClassifier.hueFor(OutlineClass.RECOVERED, config));
        assertEquals(MarkerHue.RED, OutlineClassifier.hueFor(OutlineClass.UNSAVED, config));
        assertEquals(MarkerHue.RED, OutlineClassifier.hueFor(OutlineClass.CAPTURED, config));
    }
}
