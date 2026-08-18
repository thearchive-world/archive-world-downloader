// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The pure three-way outline classifier: captured this session draws no rim, a prior-session-recovered container draws
 * the recovered hue, and an as-yet-unsaved container draws the unsaved hue, in that precedence. The any-position rule
 * makes one logical container, even a two-half double chest, classify as one entry. A captured block position stays
 * captured only while its live block-entity type still matches the recorded type (Gate 2): a same-position replacement
 * re-rims it.
 */
class OutlineClassifierTest {
    private static final UUID CART = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** A captured-set holding one block key at {@code posKey} with {@code typeId} recorded for it. */
    private static CapturedContainers capturedType(long posKey, String typeId) {
        Long2ObjectMap<String> types = new Long2ObjectOpenHashMap<>();
        types.put(posKey, typeId);
        return new CapturedContainers(new LongOpenHashSet(new long[] { posKey }), Set.of(), false,
                new Long2IntOpenHashMap(), types);
    }

    @Test
    void capturedByBlockMembership() {
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 5L }), Set.of(), false);
        assertEquals(OutlineClass.CAPTURED,
                OutlineClassifier.classify(new long[] { 5L }, null, null, false, captured, RecoveredCoverage.EMPTY));
    }

    @Test
    void capturedByEnderFlag() {
        CapturedContainers captured = new CapturedContainers(LongSets.EMPTY_SET, Set.of(), true);
        assertEquals(OutlineClass.CAPTURED,
                OutlineClassifier.classify(new long[] { 9L }, null, null, true, captured, RecoveredCoverage.EMPTY));
    }

    @Test
    void enderChestNoRimWhenRestoredOnResume() {
        // On a resume of a world whose ender inventory a prior download already saved, every ender chest is done
        // without reopening one, so it draws no rim, the same as captured this session, not the recovered hue.
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
        // The ender-recovered fact is ender-only: a non-ender block whose position is not covered stays unsaved
        // even while the shared ender inventory is restored.
        assertEquals(OutlineClass.UNSAVED, OutlineClassifier.classify(new long[] { 9L }, null, null, false,
                CapturedContainers.EMPTY, RecoveredCoverage.ENDER_ONLY));
    }

    @Test
    void capturedByEntityMembership() {
        CapturedContainers captured = new CapturedContainers(LongSets.EMPTY_SET, Set.of(CART), false);
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
        RecoveredCoverage recovered = new RecoveredCoverage(LongSets.EMPTY_SET, new Long2IntOpenHashMap(), Set.of(CART),
                false);
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classify(new long[] {}, null, CART, false,
                CapturedContainers.EMPTY, recovered));
    }

    @Test
    void capturedEntityTakesPrecedenceOverRecoveredEntity() {
        CapturedContainers captured = new CapturedContainers(LongSets.EMPTY_SET, Set.of(CART), false);
        RecoveredCoverage recovered = new RecoveredCoverage(LongSets.EMPTY_SET, new Long2IntOpenHashMap(), Set.of(CART),
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
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 4L }), Set.of(), false);
        RecoveredCoverage recovered = new RecoveredCoverage(new LongOpenHashSet(new long[] { 4L }));
        assertEquals(OutlineClass.CAPTURED,
                OutlineClassifier.classify(new long[] { 4L }, null, null, false, captured, recovered));
    }

    @Test
    void doubleChestCapturedWhenEitherHalfIsCaptured() {
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 11L }), Set.of(), false);
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
        // The stuck-rim bug: a captured barrel broken and replaced by a chest at the same pos must draw the
        // unsaved rim again, since the chest at that key is uncaptured.
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
        // A captured key with no recorded type stays captured regardless of live type, so the gate only ever
        // adds precision and never re-rims a validly captured container.
        CapturedContainers captured = new CapturedContainers(new LongOpenHashSet(new long[] { 5L }), Set.of(), false);
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
        // An insert into a prior-saved shelf leaves every occupied slot in the save, because the two masks are
        // unioned there rather than replaced. Warning the player off a shelf whose books are all on disk names a
        // risk that does not exist and points at no action that would clear it.
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classifyBookshelf(0b111, 0b001, 0b111));
    }

    @Test
    void bookshelfRecoveredWhenAnInsertWasRemovedAgain() {
        // Insert-then-remove leaves slot 0 empty with a stale captured bit, and the shelf is still fully covered
        // by what a prior session saved, so it stays violet.
        assertEquals(OutlineClass.RECOVERED, OutlineClassifier.classifyBookshelf(0b110, 0b001, 0b111));
    }

    @Test
    void bookshelfUnsavedWhenOneSlotIsCoveredByNeitherMask() {
        // The case the union rule still has to warn about: slot 2 is occupied and neither captured this session
        // nor saved before, so part of the shelf really is missing from the copy on disk.
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
