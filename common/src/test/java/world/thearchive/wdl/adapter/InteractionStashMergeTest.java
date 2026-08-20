// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for interaction-prediction's reconcile gate: a predicted interaction is captured optimistically
 * before the server acks it, so the gate ({@link InteractionCapture#confirm}) must persist content only when the
 * authoritative synced block-state confirms it and discard a prediction the server never confirmed. The decision is a
 * pure function of a {@link BlockState} and a {@code Candidate}, so it is proven headless with hand-built block-states
 * and candidates (no live client, no {@code Level}). The server-rejected discard is asserted directly on the pure
 * {@code confirm} decision.
 *
 * <p>Also covers the two seams the reconcile leans on headless: reading the authoritative block-state off a captured
 * snapshot section ({@link InteractionCapture#blockStateAt}) and the open-time-wins precedence
 * ({@link ContainerMerge#mergePlaceCandidates}).
 */
class InteractionStashMergeTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** An interaction capture with no-op bookshelf-slot and placed-container sinks, for the tests that ignore them. */
    private static InteractionCapture plainCapture(ContainerSink sink,
            boolean recaptureEnabled) {
        return plainCapture(sink, recaptureEnabled, chunk -> true);
    }

    private static InteractionCapture plainCapture(ContainerSink sink,
            boolean recaptureEnabled, InteractionCapture.ChunkCaptureGate gate) {
        return new InteractionCapture(sink, recaptureEnabled, gate,
                (posKey, slot, occupiedMask) -> {}, (posKey, blockTypeId) -> {}, posKey -> {});
    }

    private CompoundTag itemsHolder(int slot, ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(slot, stack);
        return sink.captureItems(items);
    }

    private static NonNullList<ItemStack> readItems(CompoundTag holder, int size) {
        NonNullList<ItemStack> back = NonNullList.withSize(size, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(holder, back);
        return back;
    }

    /** A placed shulker box item holding {@code contents} in its pre-component {@code BlockEntityTag.Items}. */
    private static ItemStack shulkerHolding(ItemStack... contents) {
        ItemStack shulker = new ItemStack(Blocks.SHULKER_BOX);
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.put("Items", ItemFixtures.items(contents));
        shulker.getOrCreateTag().put("BlockEntityTag", blockEntityTag);
        return shulker;
    }

    // A placed or inserted prediction the block-state confirms survives the gate.

    @Test
    void shulkerWithBlockPresentKeepsTheItemsHolder() {
        CompoundTag holder = itemsHolder(2, new ItemStack(Items.EMERALD, 7));
        InteractionCapture.HolderCandidate candidate = new InteractionCapture.HolderCandidate(
                InteractionCapture.InteractionKind.SHULKER, holder);

        Optional<CompoundTag> confirmed = InteractionCapture.confirm(Blocks.SHULKER_BOX.defaultBlockState(), candidate);

        assertTrue(confirmed.isPresent(), "a shulker present at the pos confirms the placement");
        assertSame(holder, confirmed.get(), "the confirmed shulker holder is the captured Items holder unchanged");
        assertEquals(Items.EMERALD, readItems(confirmed.get(), 27).get(2).getItem());
    }

    @Test
    void jukeboxWithRecordKeepsTheRecordHolder() {
        CompoundTag holder = InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_CAT));
        InteractionCapture.HolderCandidate candidate = new InteractionCapture.HolderCandidate(
                InteractionCapture.InteractionKind.JUKEBOX, holder);

        BlockState withRecord = Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true);
        Optional<CompoundTag> confirmed = InteractionCapture.confirm(withRecord, candidate);

        assertTrue(confirmed.isPresent(), "HAS_RECORD true confirms the inserted disc");
        assertSame(holder, confirmed.get());
    }

    // The headline discard: a prediction the synced block-state never confirms is dropped.

    @Test
    void shulkerDiscardedWhenBlockAbsent() {
        CompoundTag holder = itemsHolder(0, new ItemStack(Items.DIAMOND));
        InteractionCapture.HolderCandidate candidate = new InteractionCapture.HolderCandidate(
                InteractionCapture.InteractionKind.SHULKER, holder);

        // The server refused the placement (or it was rolled back): the authoritative block is not a shulker.
        assertTrue(!InteractionCapture.confirm(Blocks.STONE.defaultBlockState(), candidate).isPresent(),
                "a prediction the synced block-state never confirms is discarded, never persisted");
        assertTrue(!InteractionCapture.confirm(Blocks.AIR.defaultBlockState(), candidate).isPresent(),
                "an air block at the pos (the placement never landed) also discards");
    }

    @Test
    void jukeboxDiscardedWhenNoRecord() {
        CompoundTag holder = InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_11));
        InteractionCapture.HolderCandidate candidate = new InteractionCapture.HolderCandidate(
                InteractionCapture.InteractionKind.JUKEBOX, holder);

        BlockState noRecord = Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, false);
        assertTrue(!InteractionCapture.confirm(noRecord, candidate).isPresent(),
                "HAS_RECORD false (the disc was ejected or never accepted) discards the disc");
    }

    @Test
    void jukeboxDiscardedAgainstWrongBlockType() {
        CompoundTag holder = InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_CAT));
        InteractionCapture.HolderCandidate candidate = new InteractionCapture.HolderCandidate(
                InteractionCapture.InteractionKind.JUKEBOX, holder);

        // The block at the pos is not a jukebox, so the instanceof short-circuits before reading HAS_RECORD: the
        // candidate is discarded rather than throwing on a property the block does not have.
        assertTrue(!InteractionCapture.confirm(Blocks.STONE.defaultBlockState(), candidate).isPresent(),
                "a jukebox prediction against a non-jukebox block discards, it does not throw");
    }
    // Per-slot gate independence: one slot survives while another is dropped.
    // Place-then-open precedence: an opened container supersedes a stale place-time snapshot.

    @Test
    void openTimeHolderSupersedesPlaceCandidateAtTheSamePos() {
        BlockPos opened = new BlockPos(10, 70, 20);
        BlockPos placedOnly = new BlockPos(12, 70, 20);

        Map<BlockPos, CompoundTag> openTimeBundle = new LinkedHashMap<>();
        openTimeBundle.put(opened, itemsHolder(0, new ItemStack(Items.DIAMOND))); // ground-truth, edited open
        Map<BlockPos, CompoundTag> confirmedPlace = new LinkedHashMap<>();
        confirmedPlace.put(opened, itemsHolder(0, new ItemStack(Blocks.DIRT))); // stale place snapshot, must lose
        confirmedPlace.put(placedOnly, itemsHolder(0, new ItemStack(Items.GOLD_INGOT))); // place-only, must survive

        Map<BlockPos, CompoundTag> surviving = ContainerMerge.mergePlaceCandidates(openTimeBundle, confirmedPlace);

        assertFalse(surviving.containsKey(opened), "the open-time holder wins; the place candidate at that pos drops");
        assertTrue(surviving.containsKey(placedOnly), "a place-and-never-open pos still merges");
        assertEquals(1, surviving.size());
    }

    // The gate's authoritative block-state read off a captured snapshot section.

    @Test
    void blockStateAtReadsThePlacedBlockFromTheSnapshotSection() {
        BlockPos shulkerPos = new BlockPos(5, -60, 7); // a deliberately negative-Y, non-zero-local-coordinate pos
        ChunkSnapshotSource snapshot = SyntheticChunks.withBlockAt(shulkerPos,
                Blocks.SHULKER_BOX.defaultBlockState());

        BlockState read = InteractionCapture.blockStateAt(snapshot, shulkerPos);

        assertNotNull(read, "the section containing the pos is found");
        assertTrue(read.getBlock() instanceof ShulkerBoxBlock, "the placed shulker is read back from the section copy");
    }

    @Test
    void blockStateAtFailsClosedWhenNoSectionCoversTheY() {
        BlockPos shulkerPos = new BlockPos(5, -60, 7);
        ChunkSnapshotSource snapshot = SyntheticChunks.withBlockAt(shulkerPos,
                Blocks.SHULKER_BOX.defaultBlockState());

        assertNull(InteractionCapture.blockStateAt(snapshot, new BlockPos(5, 5000, 7)),
                "an out-of-range Y has no captured section, so the gate reads null and fails closed");
    }

    // The per-chunk reconcile: route each confirmed candidate to its merge target, drop the rest.

    private static Map<BlockPos, InteractionCapture.Candidate> oneCandidate(BlockPos pos,
            InteractionCapture.Candidate candidate) {
        Map<BlockPos, InteractionCapture.Candidate> candidates = new LinkedHashMap<>();
        candidates.put(pos, candidate);
        return candidates;
    }

    @Test
    void reconcileRoutesConfirmedShulkerToItemsBundle() {
        BlockPos pos = new BlockPos(5, -60, 7);
        InteractionCapture.ChunkBundles bundles = InteractionCapture.reconcile(
                oneCandidate(pos, new InteractionCapture.HolderCandidate(
                        InteractionCapture.InteractionKind.SHULKER, itemsHolder(0, new ItemStack(Items.DIAMOND)))),
                SyntheticChunks.withBlockAt(pos, Blocks.SHULKER_BOX.defaultBlockState()));
        assertTrue(bundles.items().containsKey(pos), "a confirmed shulker routes to the Items bundle");
        assertTrue(bundles.holders().isEmpty(), "and not to the holder-merge bundle");
    }

    @Test
    void reconcileRoutesConfirmedJukeboxToHolderBundle() {
        BlockPos pos = new BlockPos(5, -60, 7);
        InteractionCapture.ChunkBundles bundles = InteractionCapture.reconcile(
                oneCandidate(pos, new InteractionCapture.HolderCandidate(InteractionCapture.InteractionKind.JUKEBOX,
                        InteractionCapture.captureRecordItem(new ItemStack(Items.MUSIC_DISC_CAT)))),
                SyntheticChunks.withBlockAt(pos,
                        Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true)));
        assertTrue(bundles.holders().containsKey(pos), "a confirmed jukebox routes to the holder-merge bundle");
        assertTrue(bundles.items().isEmpty(), "and not to the Items bundle");
    }

    @Test
    void reconcileDropsAnUnconfirmedCandidate() {
        BlockPos pos = new BlockPos(5, -60, 7);
        InteractionCapture.ChunkBundles bundles = InteractionCapture.reconcile(
                oneCandidate(pos, new InteractionCapture.HolderCandidate(
                        InteractionCapture.InteractionKind.SHULKER, itemsHolder(0, new ItemStack(Items.DIAMOND)))),
                SyntheticChunks.withBlockAt(pos, Blocks.STONE.defaultBlockState()));

        assertTrue(bundles.items().isEmpty() && bundles.holders().isEmpty(),
                "the snapshot block does not confirm a shulker, so the candidate is dropped from every bundle");
    }

    @Test
    void reconcileDropsCandidateWithNoCapturedSection() {
        BlockPos placed = new BlockPos(5, -60, 7);
        BlockPos elsewhere = new BlockPos(5, 5000, 7); // a Y the snapshot has no section for
        InteractionCapture.ChunkBundles bundles = InteractionCapture.reconcile(
                oneCandidate(elsewhere, new InteractionCapture.HolderCandidate(
                        InteractionCapture.InteractionKind.SHULKER, itemsHolder(0, new ItemStack(Items.DIAMOND)))),
                SyntheticChunks.withBlockAt(placed, Blocks.SHULKER_BOX.defaultBlockState()));

        assertTrue(bundles.items().isEmpty(), "no captured section covers the pos, so the gate fails closed");
    }

    // Recognition: a right-click on a bookshelf or jukebox records an insert candidate only when the block would
    // consume it (a book, a playable disc), so a content block placed against that face falls through to a place
    // capture instead of being dropped.
    @Test
    void placedShulkerNotifiesThePlacedContainerSink() {
        long[] sinkPos = { -1L };
        String[] sinkType = { null };
        InteractionCapture capture = new InteractionCapture(sink, true, chunk -> true,
                (posKey, slot, occupied) -> {}, (posKey, blockTypeId) -> {
                    sinkPos[0] = posKey;
                    sinkType[0] = blockTypeId;
                }, posKey -> {});
        BlockPos pos = new BlockPos(4, 70, 8);
        ItemStack shulker = shulkerHolding(new ItemStack(Items.DIAMOND));

        capture.recordPlaceAt(pos, shulker);

        assertEquals(pos.asLong(), sinkPos[0],
                "a placed content-bearing shulker marks the outline captured-set at its pos on record");
        assertEquals("minecraft:shulker_box", sinkType[0],
                "and records its block-entity type so Gate 2 can re-rim a later cross-type replacement");
    }

    /**
     * A placement in a cell whose chunk will never be captured again must NOT drop what the session already captured
     * there. That chunk is on disk and the residual sweep folds onto the copy that predates this placement, so the
     * captured contents still belong to the block entity the fold will find. Dropping them would delete a capture that
     * was about to land correctly and that nothing else can ever write.
     */
    @Test
    void aPlacementInAnUncapturableCellDoesNotDropWhatWasCapturedThere() {
        long[] sinkPos = { -1L };
        InteractionCapture capture = new InteractionCapture(sink, true, chunk -> false,
                (posKey, slot, occupied) -> {}, (posKey, blockTypeId) -> {}, posKey -> sinkPos[0] = posKey);
        BlockPos pos = new BlockPos(9, 70, 9);

        capture.recordPlaceAt(pos, new ItemStack(Blocks.CHEST));

        assertEquals(-1L, sinkPos[0],
                "the chunk is written and frozen, so the block on disk still predates this placement");
    }

    /** And the same placement where the chunk WILL be captured again does drop it, or the guard is a blanket. */
    @Test
    void aPlacementInTheRecapturedCellDropsWhatWasCapturedThere() {
        long[] sinkPos = { -1L };
        InteractionCapture capture = new InteractionCapture(sink, true, chunk -> true,
                (posKey, slot, occupied) -> {}, (posKey, blockTypeId) -> {}, posKey -> sinkPos[0] = posKey);
        BlockPos pos = new BlockPos(9, 70, 9);

        capture.recordPlaceAt(pos, new ItemStack(Blocks.CHEST));

        assertEquals(pos.asLong(), sinkPos[0],
                "the chunk re-captures with the new block, so the old block's capture is stale there");
    }

    @Test
    void jukeboxInsertRecognizesPlayableDisc() {
        InteractionCapture capture = plainCapture(sink, true);
        BlockState emptyJukebox = Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, false);

        boolean recorded = capture.recordJukeboxInsert(emptyJukebox, new BlockPos(0, 70, 0),
                new ItemStack(Items.MUSIC_DISC_CAT));

        assertTrue(recorded, "a playable disc into an empty jukebox is an insert");
        assertFalse(capture.pendingCandidateChunks().isEmpty(), "the disc insert candidate is stashed");
    }

    @Test
    void jukeboxClickHoldingPlaceableBlockIsNotInsert() {
        InteractionCapture capture = plainCapture(sink, true);
        BlockState emptyJukebox = Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, false);

        boolean recorded = capture.recordJukeboxInsert(emptyJukebox, new BlockPos(0, 70, 0),
                new ItemStack(Blocks.SHULKER_BOX));

        assertFalse(recorded, "a shulker clicked on a jukebox face is a placement, not a disc insert");
        assertTrue(capture.pendingCandidateChunks().isEmpty(), "no phantom jukebox candidate is stashed");
    }

    @Test
    void noInsertIsRecordedWhenRecaptureIsOff() {
        // Without re-capture the reconcile gate never sees the post-insert block-state, so any candidate would be
        // silently discarded; the recognizer must record nothing rather than stash a doomed candidate.
        InteractionCapture capture = plainCapture(sink, false);
        BlockState emptyJukebox = Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, false);

        boolean recorded = capture.recordJukeboxInsert(emptyJukebox, new BlockPos(0, 70, 0),
                new ItemStack(Items.MUSIC_DISC_CAT));

        assertFalse(recorded, "a disc insert is not recorded when re-capture (the gate's snapshot refresh) is off");
        assertTrue(capture.pendingCandidateChunks().isEmpty(), "no candidate is stashed when re-capture is off");
    }

    @Test
    void throwingRecognitionIsSwallowedFailSoft() {
        InteractionCapture capture = plainCapture(sink, true);
        // A pathological component can make the click-time codec encode throw; the recognizer must skip that one
        // capture rather than letting it escape to the loader event and crash the client mid-download.
        assertDoesNotThrow(() -> capture.recordFailSoft(new BlockPos(0, 70, 0), () -> {
            throw new IllegalStateException("encode blew up");
        }));
    }

    @Test
    void theResidualDrainDropsEveryPendingCandidateAndNamesWhereItStood() {
        InteractionCapture capture = plainCapture(sink, true);
        BlockPos pos = new BlockPos(0, 70, 0);
        capture.recordJukeboxInsert(Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, false),
                pos, new ItemStack(Items.MUSIC_DISC_CAT));
        assertFalse(capture.pendingCandidateChunks().isEmpty(), "the disc insert is stashed");

        // A dimension rebind must drop every old-dimension candidate so none carries into the new ChunkPos space,
        // and the caller counts what it drops, since none of it was written.
        List<BlockPos> dropped = capture.drainResidualPositions();

        assertEquals(ImmutableList.of(pos), dropped, "the residual names the position whose content is missing");
        assertTrue(capture.pendingCandidateChunks().isEmpty(), "and leaves nothing stashed");
    }

    // The per-chunk capturability gate. A chunk already written and frozen is re-captured by nothing, so its
    // reconcile gate has no post-interaction block-state and no flush ever drains the candidate: recording one
    // there can only end in a silent drop, with the outline already told the content is downloaded.
    @Test
    void aJukeboxInsertInAnUncapturableChunkIsNotStashed() {
        InteractionCapture capture = plainCapture(sink, true, chunk -> false);

        boolean recorded = capture.recordJukeboxInsert(
                Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, false),
                new BlockPos(0, 70, 0), new ItemStack(Items.MUSIC_DISC_CAT));

        assertFalse(recorded, "a disc into a chunk nothing will capture again is not recorded");
        assertTrue(capture.pendingCandidateChunks().isEmpty(), "so no doomed candidate is stashed");
    }

    @Test
    void aPlacedShulkerInAnUncapturableChunkIsNeitherStashedNorMarked() {
        long[] sinkPos = { -1L };
        InteractionCapture capture = new InteractionCapture(sink, true, chunk -> false,
                (posKey, slot, occupied) -> {}, (posKey, blockTypeId) -> sinkPos[0] = posKey, posKey -> {});
        BlockPos pos = new BlockPos(4, 70, 8);
        ItemStack shulker = shulkerHolding(new ItemStack(Items.DIAMOND));

        capture.recordPlaceAt(pos, shulker);

        assertTrue(capture.pendingCandidateChunks().isEmpty(), "no doomed placement candidate is stashed");
        assertEquals(-1L, sinkPos[0], "and the outline is not told the container is downloaded");
    }

    @Test
    void aPlacementInAnUncapturableChunkStillSupersedesAnEarlierPredictionThere() {
        // The supersede is not a capture: leaving a stale prediction at a cell a new block now occupies is how
        // one block's contents get written onto another, which is worse than capturing nothing.
        boolean[] capturable = { true };
        InteractionCapture capture = plainCapture(sink, true, chunk -> capturable[0]);
        BlockPos pos = new BlockPos(4, 70, 8);
        ItemStack shulker = shulkerHolding(new ItemStack(Items.DIAMOND));
        capture.recordPlaceAt(pos, shulker);
        assertFalse(capture.pendingCandidateChunks().isEmpty(), "the first placement is predicted");

        capturable[0] = false;
        capture.recordPlaceAt(pos, new ItemStack(Blocks.STONE));

        assertTrue(capture.pendingCandidateChunks().isEmpty(),
                "the stale shulker prediction is dropped even though the new placement is not captured");
    }
}
