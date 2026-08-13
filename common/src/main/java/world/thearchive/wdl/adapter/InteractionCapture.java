// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The MC-typed, loader-agnostic recognizer for interaction-prediction capture: content the chunk packet and an opened
 * menu never carry to the client (chiseled-bookshelf books, jukebox discs, placed shulker contents, placed beehive
 * occupants) is predicted from the local player's own right-click and reconciled against the authoritative synced
 * block-state before it is persisted, so a prediction the server never confirmed is never written.
 *
 * <p>Mirrors {@link EntityPacketCapture}: a connection-scoped {@code static volatile} publication point the per-loader
 * use-block hook resolves (the hook has no session reference), {@code null} when no download is running so the hook
 * no-ops. At the click ({@link #onUseBlock}, client main thread) it serializes the hand content to immutable NBT at
 * once (the snapshot discipline), so the stashes hold only detached data; the session drains them per chunk at flush
 * ({@link #drainChunk}), reconciling each candidate against the captured snapshot's block-state ({@link #confirm},
 * {@link #blockStateAt}) and routing the confirmed holders to their merge ({@link ContainerMerge}). At most one capture
 * runs at a time, so a process singleton is correct.
 *
 * <p>Two of the four writes reuse the open-time {@code "Items"} path (bookshelf, shulker, via {@link ContainerSink});
 * the other two encode directly over {@code RegistryOps(NbtOps)} with no {@code ValueOutput} layer
 * ({@code ItemStack.CODEC} under {@code "RecordItem"}, {@code Occupant.LIST_CODEC} under {@code "bees"}), so they stay
 * byte-identical across the current bands and need no per-band sink (the {@link MapSink} encode discipline).
 */
public final class InteractionCapture {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile @Nullable InteractionCapture active;

    private final ContainerSink containerSink;
    private final RegistryAccess registries;

    /**
     * Whether the recognizer is live, coupled to {@code recaptureChunks}: the reconcile gate confirms a candidate only
     * against a re-captured, post-interaction block-state, so without re-capture every candidate (insert or place)
     * fails the gate and is silently discarded. Record nothing in that combination rather than stash a doomed
     * candidate.
     */
    private final boolean recaptureEnabled;

    /**
     * The per-chunk half of the same rule: whether the chunk an interaction lands in will still be captured, so the
     * gate has a post-interaction block-state to confirm against and a flush to drain the candidate. A chunk already
     * written and not eligible for re-capture has neither, so a candidate recorded there can only ever be dropped,
     * while its optimistic mark has already told the outline the content is downloaded.
     */
    private final ChunkCaptureGate chunkCaptureGate;

    /** Insert-time candidates (bookshelf, jukebox), keyed by the looked-at block pos. Main-thread only. */
    private final Map<BlockPos, Candidate> insertStash = new LinkedHashMap<>();

    /** Place-time candidates (shulker, beehive), keyed by the derived placed pos. Main-thread only. */
    private final Map<BlockPos, Candidate> placeStash = new LinkedHashMap<>();

    /** Notified the instant a bookshelf book insert is recorded, so the outline marks that slot captured. */
    private final BookshelfSlotSink bookshelfSlotSink;

    /** Notified the instant a content-bearing container is placed, so the outline marks its pos captured. */
    private final PlacedContainerSink placedContainerSink;

    /** Notified with every cell a placement lands in, so the session drops what it captured from the old block. */
    private final PlacementSink placementSink;

    /**
     * The optimistic-mark callback for the unsaved-container outline: invoked the instant a bookshelf book insert is
     * recorded, so the session marks that slot captured this session before the flush confirms it, the way the
     * open-time stash marks a chest captured on open. {@code occupiedMask} is the bookshelf's occupancy before this
     * insert (the clicked slot is empty pre-click), so the session can tell when the insert completes every occupied
     * slot and count the bookshelf as a downloaded container.
     */
    @FunctionalInterface
    interface BookshelfSlotSink {
        void slotCaptured(long posKey, int slot, int occupiedMask);
    }

    /**
     * The optimistic-mark callback for a placed container, the whole-block sibling of {@link BookshelfSlotSink}:
     * invoked the instant a content-bearing block (a shulker) is placed, so the session marks its pos captured this
     * session before the flush confirms it, the way the open-time stash marks a chest on open. Carries the placed
     * block-entity type id so the session records it alongside the mark, the way the open-time stashes do, letting Gate
     * 2 re-rim the position if a different container later replaces the shulker.
     */
    @FunctionalInterface
    interface PlacedContainerSink {
        void containerCaptured(long posKey, String blockTypeId);
    }

    /**
     * The staleness callback for a landing placement: invoked with the cell a block placement is about to occupy,
     * whether or not the placed block carries content worth predicting. Every open-time capture the session holds for
     * that cell describes the block being replaced, and a replacement of the same block-entity type leaves both the
     * position key and the merge-time type gate matching, so no later signal can tell the stale contents apart from the
     * new block's. The placement is the client's own evidence that the captured block is gone.
     *
     * <p>Raised only for a cell whose chunk will be captured again, which is what makes the captured contents stale.
     * Where it will not be, the block on disk still predates the placement and the capture still belongs to it. Under
     * {@code recaptureChunks} OFF no cell qualifies, since that mode gates the whole place path and keeps each area as
     * first seen.
     */
    @FunctionalInterface
    interface PlacementSink {
        void blockPlacedAt(long posKey);
    }

    /**
     * Whether a predicted interaction in {@code chunk} can still reach disk: the session answers from its own buffer
     * and re-capture mode. Consulted before anything is stashed or optimistically marked, so a chunk the capture will
     * never look at again produces no candidate and no cleared rim.
     */
    @FunctionalInterface
    interface ChunkCaptureGate {
        boolean isCapturable(ChunkPos chunk);
    }

    InteractionCapture(ContainerSink containerSink, RegistryAccess registries, boolean recaptureEnabled,
            ChunkCaptureGate chunkCaptureGate, BookshelfSlotSink bookshelfSlotSink,
            PlacedContainerSink placedContainerSink, PlacementSink placementSink) {
        this.containerSink = containerSink;
        this.registries = registries;
        this.recaptureEnabled = recaptureEnabled;
        this.chunkCaptureGate = chunkCaptureGate;
        this.bookshelfSlotSink = bookshelfSlotSink;
        this.placedContainerSink = placedContainerSink;
        this.placementSink = placementSink;
    }

    /** Whether an interaction at {@code pos} can still reach disk (both halves of the capturability rule). */
    private boolean isCapturable(BlockPos pos) {
        return recaptureEnabled && chunkCaptureGate.isCapturable(new ChunkPos(pos));
    }

    static void activate(InteractionCapture capture) {
        active = capture;
    }

    static void deactivate(InteractionCapture capture) {
        if (active == capture) {
            active = null;
        }
    }

    /**
     * The per-loader use-block hook entry: dispatch one observed right-click to the running recognizer, or no-op when
     * no download is active or it is not the local player's own client-side interaction (so a future logical-server
     * pass cannot double-fire). The loaders adapt their event to this one call and never cancel the interaction.
     *
     * <p>A spectator is excluded, because none of this can happen for one: the server's spectator branch opens a menu
     * from the block state's provider and never runs the block's use handler, so no insert, no page turn and no jukebox
     * load occurs. Recording a candidate anyway would clear that block's outline rim and add to the report's container
     * count for content that never reaches disk, which tells the user the opposite of the truth. The guard is inert on
     * a loader whose hook already declines to fire for a spectator and load-bearing on one whose hook does not.
     */
    public static void dispatchUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        InteractionCapture capture = active;
        if (capture != null && level.isClientSide() && player == Minecraft.getInstance().player
                && !player.isSpectator()) {
            capture.onUseBlock(player, level, hand, hit);
        }
    }

    /**
     * The kind of a {@link HolderCandidate} (a bookshelf insert is a {@link BookshelfCandidate}, not here), and the one
     * descriptor per holder content type. Each constant carries the reconcile predicate that confirms its content
     * against the authoritative block-state, and whether its confirmed holder folds into the open-time {@code "Items"}
     * container bundle (a placed shulker, which opening supersedes) or the generic holder merge
     * ({@link ContainerMerge#mergeHolderChunkStash}: a jukebox disc, beehive occupants). Adding a content type is one
     * constant that drives the gate, {@link #route}, and the merge together, so a half-wired type cannot compile rather
     * than silently capturing but never writing.
     */
    enum InteractionKind {
        JUKEBOX(false, state -> state.getBlock() instanceof JukeboxBlock && state.getValue(JukeboxBlock.HAS_RECORD)),
        SHULKER(true, state -> state.getBlock() instanceof ShulkerBoxBlock),
        BEEHIVE(false, state -> state.getBlock() instanceof BeehiveBlock);

        private final boolean itemsBundle;
        private final Predicate<BlockState> confirm;

        InteractionKind(boolean itemsBundle, Predicate<BlockState> confirm) {
            this.itemsBundle = itemsBundle;
            this.confirm = confirm;
        }

        /** Whether a confirmed holder of this kind folds into the open-time {@code "Items"} bundle (a shulker). */
        boolean itemsBundle() {
            return itemsBundle;
        }

        /** Whether the authoritative synced {@code state} confirms this kind's predicted content is present. */
        boolean confirms(BlockState state) {
            return confirm.test(state);
        }
    }

    /** A predicted interaction awaiting the reconcile gate. */
    sealed interface Candidate permits BookshelfCandidate, HolderCandidate {}

    /**
     * A chiseled-bookshelf insert: the captured {@code ItemStackWithSlot} entry per hit slot (last-seen-wins per slot).
     * The gate keeps slot {@code n} only when the authoritative {@code SLOT_n_OCCUPIED} bit is set, so several books
     * loaded into one bookshelf each stand or fall on their own slot.
     */
    record BookshelfCandidate(Map<Integer, CompoundTag> slotEntries) implements Candidate {}

    /**
     * A jukebox disc, placed shulker, or placed beehive: one merge-ready single-key holder
     * ({@code "RecordItem"}/{@code "Items"}/{@code "bees"}). The gate keeps it whole only when the authoritative
     * block-state confirms the content is present (HAS_RECORD, or the expected placed block type).
     */
    record HolderCandidate(InteractionKind kind, CompoundTag holder) implements Candidate {}

    /**
     * A chunk's confirmed holders, split by merge path: {@code items} folds through the open-time container
     * {@code "Items"} merge (placed shulker, bookshelf books) under the open-time-wins precedence; {@code holders}
     * takes the generic field-copy merge (jukebox disc under {@code "RecordItem"}, beehive occupants under
     * {@code "bees"}), each holder already carrying exactly the key its block entity reads. One bundle drains per
     * chunk.
     */
    record ChunkBundles(Map<BlockPos, CompoundTag> items, Map<BlockPos, CompoundTag> holders) {}

    /**
     * Observe one local-player right-click (client main thread). Recognize an insert into an existing bookshelf/jukebox
     * or a place of a content-bearing block-item, snapshot the hand content to immutable NBT at once, and stash it; the
     * reconcile gate at flush decides whether it survives. Never mutates the interaction or the live stack.
     */
    private void onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        recordFailSoft(hit.getBlockPos(), () -> recognize(player, level, hand, hit));
    }

    /**
     * Run {@code recognition} with per-click failure isolation, mirroring the writer-side merge: the click-time codec
     * encode can throw on a pathological component whose network form diverges from its persistent form, so isolate it
     * here rather than letting it escape to the loader event and crash the client mid-download. Skips that one capture
     * and logs.
     */
    void recordFailSoft(BlockPos pos, Runnable recognition) {
        try {
            recognition.run();
        } catch (RuntimeException e) {
            LOGGER.warn("skipping interaction capture at {}: recognition failed", pos, e);
        }
    }

    private void recognize(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        // getBlockPos may return a MutableBlockPos; the stash key must be immutable, like every other stash.
        BlockPos clicked = hit.getBlockPos().immutable();
        BlockState state = level.getBlockState(clicked);
        boolean inserted = false;
        if (state.getBlock() instanceof ChiseledBookShelfBlock bookshelf) {
            inserted = recordBookshelfInsert(bookshelf, state, clicked, hit, stack);
        } else if (state.getBlock() instanceof JukeboxBlock) {
            inserted = recordJukeboxInsert(state, clicked, stack);
        }
        // A right-click on a bookshelf or jukebox that the block did not consume as an insert (a non-book, a
        // non-disc, an occupied slot) still places a held block against that face, so fall through to recordPlace
        // rather than returning: vanilla's useItemOn yields to item use on a non-consuming result.
        if (!inserted && recaptureEnabled) {
            recordPlace(player, hand, hit, stack);
        }
    }

    boolean recordBookshelfInsert(ChiseledBookShelfBlock bookshelf, BlockState state, BlockPos pos,
            BlockHitResult hit, ItemStack stack) {
        if (!stack.is(ItemTags.BOOKSHELF_BOOKS) || !isCapturable(pos)) {
            return false; // only a book is consumed as an insert, and only where a later capture can confirm it
        }
        OptionalInt hitSlot = bookshelfHitSlot(state, hit);
        if (hitSlot.isEmpty()) {
            return false;
        }
        int slot = hitSlot.getAsInt();
        if (!slotInRange(slot) || slotOccupied(state, slot)) {
            return false; // out of range, or the slot is already occupied pre-click: this click is not inserting
        }
        // Vanilla inserts exactly one book and the slot holds one, so capture a single item rather than the held
        // stack's count, which would otherwise persist an invalid over-count on reload.
        CompoundTag entry = captureBookSlotEntry(containerSink, stack.copyWithCount(1), slot, registries);
        if (entry.isEmpty()) {
            // The click-time encode produced nothing. Recording it anyway would clear this slot's rim for a book
            // that can never reach disk, so leave the rim armed and let the player see it is not captured.
            LOGGER.warn("skipping bookshelf slot {} at {}: the book could not be serialized", slot, pos);
            return false;
        }
        Map<Integer, CompoundTag> slots = insertStash.get(pos) instanceof BookshelfCandidate existing
                ? new LinkedHashMap<>(existing.slotEntries())
                : new LinkedHashMap<>();
        slots.put(slot, entry);
        insertStash.put(pos, new BookshelfCandidate(slots));
        bookshelfSlotSink.slotCaptured(pos.asLong(), slot, BookshelfSlots.occupiedSlotMask(state));
        return true;
    }

    /**
     * The 0-to-5 chiseled-bookshelf slot a hit lands in, or empty when the hit is not on the shelf face. Mirrors
     * vanilla ChiseledBookShelfBlock.getHitSlot, which is private and takes the block state below 1.21.11: the face is
     * three columns wide and two rows tall, the top row slots 0 to 2 and the bottom row 3 to 5.
     */
    private static OptionalInt bookshelfHitSlot(BlockState state, BlockHitResult hit) {
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        if (facing != hit.getDirection()) {
            return OptionalInt.empty();
        }
        BlockPos frontPos = hit.getBlockPos().relative(facing);
        Vec3 local = hit.getLocation().subtract(frontPos.getX(), frontPos.getY(), frontPos.getZ());
        float upFace = (float) local.y();
        float acrossFace;
        switch (facing) {
            case NORTH -> acrossFace = (float) (1.0 - local.x());
            case SOUTH -> acrossFace = (float) local.x();
            case WEST -> acrossFace = (float) local.z();
            case EAST -> acrossFace = (float) (1.0 - local.z());
            default -> {
                return OptionalInt.empty();
            }
        }
        int row = upFace >= 0.5F ? 0 : 1;
        int column = acrossFace < 0.375F ? 0 : (acrossFace < 0.6875F ? 1 : 2);
        return OptionalInt.of(column + row * 3);
    }

    boolean recordJukeboxInsert(BlockState state, BlockPos pos, ItemStack stack) {
        if (state.getValue(JukeboxBlock.HAS_RECORD) || !stack.has(DataComponents.JUKEBOX_PLAYABLE)
                || !isCapturable(pos)) {
            return false; // an occupied jukebox ejects; only a playable disc inserts, and only where it can confirm
        }
        insertStash.put(pos, new HolderCandidate(InteractionKind.JUKEBOX, captureRecordItem(stack, registries)));
        return true;
    }

    private void recordPlace(Player player, InteractionHand hand, BlockHitResult hit, ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return;
        }
        // The same context vanilla's own BlockItem.place builds and asks first, so this is that question one
        // step earlier on the same client state. Without it every right-click holding a block item predicts a
        // placement into whatever cell the clicked face points at, including a cell already occupied by the
        // very block type the reconcile gate confirms on, which is exactly the state that makes writing wrong.
        BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hit);
        if (!context.canPlace()) {
            return;
        }
        // The cell the placement lands in: relative to the clicked face normally, but the clicked cell when the
        // target is replaceable (grass, water), which the naive relative(face) gets wrong. getClickedPos may
        // return a MutableBlockPos; the stash key must be immutable, like every other stash.
        recordPlaceAt(context.getClickedPos().immutable(), stack);
    }

    void recordPlaceAt(BlockPos placedPos, ItemStack stack) {
        // A placement supersedes every prediction AND every open-time capture at this cell: the block they
        // describe is being replaced, and a same-type replacement leaves both the position key and the
        // merge-time block-entity type gate matching, so nothing downstream can tell the stale contents from
        // the new block's. The supersede runs even where the cell can no longer be captured, since a stale
        // prediction there is worse than none.
        placeStash.remove(placedPos);
        insertStash.remove(placedPos);
        if (!isCapturable(placedPos)) {
            // The open-time captures are not stale here, and this is the one place that inverts. An
            // un-capturable cell is one whose chunk is already written and will not be captured again, so the
            // block entity the residual sweep folds onto is the one on disk, which still predates this
            // placement. Dropping the holder there would delete a capture that was about to land correctly and
            // that nothing else can ever write. The predictions above are dropped either way: their reconcile
            // gate needs a post-interaction snapshot this chunk will never produce.
            return;
        }
        placementSink.blockPlacedAt(placedPos.asLong());
        Block block = Block.byItem(stack.getItem());
        // Keyed by the (component, block) pairing: component presence alone does not imply this block saves
        // under the mapped key, and an unrecognized pairing has no general component-to-key mapping, so it drops.
        if (block instanceof ShulkerBoxBlock && stack.has(DataComponents.CONTAINER)) {
            ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            NonNullList<ItemStack> items = NonNullList.withSize(ShulkerBoxBlockEntity.CONTAINER_SIZE, ItemStack.EMPTY);
            contents.copyInto(items);
            CompoundTag holder = containerSink.captureItems(items, registries);
            placeStash.put(placedPos, new HolderCandidate(InteractionKind.SHULKER, holder));
            placedContainerSink.containerCaptured(placedPos.asLong(), shulkerBlockEntityId());
        } else if (block instanceof BeehiveBlock) {
            Bees bees = stack.getOrDefault(DataComponents.BEES, Bees.EMPTY);
            if (!bees.bees().isEmpty()) {
                CompoundTag holder = captureBees(bees.bees(), registries);
                placeStash.put(placedPos, new HolderCandidate(InteractionKind.BEEHIVE, holder));
            }
        }
    }

    @SuppressWarnings("NullAway") // getKey is non-null for a registered vanilla block-entity type
    private static String shulkerBlockEntityId() {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(BlockEntityType.SHULKER_BOX).toString();
    }

    /** The chunks holding a pending candidate, so the session can re-encode the loaded ones before the gate. */
    Set<ChunkPos> pendingCandidateChunks() {
        Set<ChunkPos> chunks = new HashSet<>();
        for (BlockPos pos : insertStash.keySet()) {
            chunks.add(new ChunkPos(pos));
        }
        for (BlockPos pos : placeStash.keySet()) {
            chunks.add(new ChunkPos(pos));
        }
        return chunks;
    }

    /**
     * Drop every pending candidate and return where each stood, for a dimension rebind or a finish: whatever is still
     * stashed after a whole-buffer drain reached no chunk flush, so none of it was written, and the caller counts it as
     * the loss it is. On a rebind the drop is also mandatory rather than merely honest: an old-dimension candidate's
     * pos must not carry into the new dimension's shared {@link ChunkPos} space, where a same-type block could merge
     * wrong-dimension content.
     */
    List<BlockPos> drainResidualPositions() {
        List<BlockPos> positions = new ArrayList<>(insertStash.keySet());
        positions.addAll(placeStash.keySet());
        insertStash.clear();
        placeStash.clear();
        return positions;
    }

    /**
     * Drain the candidates located in {@code chunk} from both stashes and reconcile them against the captured
     * {@code snapshot}, routing each confirmed holder by its merge target. Drains as the chunk leaves memory (the entry
     * is final and cannot wait), exactly like the open-time stash. Main-thread only.
     */
    ChunkBundles drainChunk(ChunkPos chunk, ChunkSnapshotSource snapshot) {
        if (insertStash.isEmpty() && placeStash.isEmpty()) {
            return new ChunkBundles(Map.of(), Map.of()); // the common no-interaction flush: skip the per-chunk maps
        }
        Map<BlockPos, Candidate> chunkCandidates = new LinkedHashMap<>();
        drainInto(insertStash, chunk, chunkCandidates);
        drainInto(placeStash, chunk, chunkCandidates);
        return reconcile(chunkCandidates, snapshot);
    }

    private static void drainInto(Map<BlockPos, Candidate> stash, ChunkPos chunk, Map<BlockPos, Candidate> out) {
        Iterator<Map.Entry<BlockPos, Candidate>> entries = stash.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlockPos, Candidate> entry = entries.next();
            if (new ChunkPos(entry.getKey()).equals(chunk)) {
                out.put(entry.getKey(), entry.getValue());
                entries.remove();
            }
        }
    }

    /**
     * The pure per-chunk reconcile: read each candidate's authoritative block-state off {@code snapshot}, drop a
     * candidate the gate does not confirm or whose section is missing (fail closed), and route every confirmed holder
     * by its merge target. Server-free, so the whole drain decision is exercised headless.
     */
    static ChunkBundles reconcile(Map<BlockPos, Candidate> candidates, ChunkSnapshotSource snapshot) {
        ChunkBundles bundles = new ChunkBundles(new LinkedHashMap<>(), new LinkedHashMap<>());
        for (Map.Entry<BlockPos, Candidate> entry : candidates.entrySet()) {
            BlockState state = blockStateAt(snapshot, entry.getKey());
            if (state == null) {
                continue;
            }
            Candidate candidate = entry.getValue();
            confirm(state, candidate).ifPresent(holder -> route(entry.getKey(), candidate, holder, bundles));
        }
        return bundles;
    }

    private static void route(BlockPos pos, Candidate candidate, CompoundTag holder, ChunkBundles bundles) {
        // The descriptor decides the merge path, so a new content type adds an InteractionKind constant, never a
        // case here: a holder kind not bound to the "Items" bundle takes the generic field-copy merge, while a
        // shulker and every bookshelf confirm to an open-time "Items" holder.
        if (candidate instanceof HolderCandidate held && !held.kind().itemsBundle()) {
            bundles.holders().put(pos, holder);
        } else {
            bundles.items().put(pos, holder);
        }
    }

    /**
     * The reconcile gate: keep a predicted candidate's content only when the authoritative synced block-state
     * {@code authoritative} confirms it, otherwise drop it (a placement the server refused or the player reverted
     * reduces to the same negative gate). Pure, so it is exercised headless against hand-built block-states. A
     * bookshelf is filtered per slot; the others stand or fall whole.
     *
     * <p>The whole-confirm kinds gate on block type alone, since the synced block-state carries no occupant or contents
     * dimension: a server that strips a placed container's contents, or bees that leave a hive on placement, still
     * confirms on type and persists the predicted content. That is an accepted inherent ceiling of optimistic
     * prediction, not a fixable case: there is no client-side signal that distinguishes it from a confirmed placement.
     */
    static Optional<CompoundTag> confirm(BlockState authoritative, Candidate candidate) {
        return switch (candidate) {
            case BookshelfCandidate bookshelf -> confirmBookshelf(authoritative, bookshelf.slotEntries());
            case HolderCandidate held -> held.kind().confirms(authoritative)
                    ? Optional.of(held.holder())
                    : Optional.empty();
        };
    }

    private static Optional<CompoundTag> confirmBookshelf(BlockState state, Map<Integer, CompoundTag> slotEntries) {
        if (!(state.getBlock() instanceof ChiseledBookShelfBlock)) {
            return Optional.empty();
        }
        ListTag kept = new ListTag();
        for (Map.Entry<Integer, CompoundTag> entry : slotEntries.entrySet()) {
            if (slotOccupied(state, entry.getKey())) {
                kept.add(entry.getValue());
            }
        }
        if (kept.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag holder = new CompoundTag();
        holder.put("Items", kept);
        return Optional.of(holder);
    }

    /** Whether bookshelf slot {@code slot} is addressable (a six-slot index), guarding the fixed-size capture. */
    private static boolean slotInRange(int slot) {
        return slot >= 0 && slot < ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.size();
    }

    /** Whether bookshelf {@code state}'s slot {@code slot} reads occupied (false for an out-of-range slot). */
    private static boolean slotOccupied(BlockState state, int slot) {
        return slotInRange(slot) && state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot));
    }

    /**
     * The authoritative block-state at {@code pos} read off the captured chunk {@code snapshot} (the gate's source of
     * truth): the same artifact the merge writes into, and current even after the chunk unloads, since the re-capture
     * machinery keeps a hot chunk's snapshot block-state in step with the server. Returns null when no captured section
     * covers the pos's Y, so the gate fails closed (drops the candidate) rather than reading a default state; a real
     * interaction's pos never lands out of range.
     */
    static @Nullable BlockState blockStateAt(ChunkSnapshotSource snapshot, BlockPos pos) {
        int sectionY = pos.getY() >> 4;
        for (SerializableChunkData.SectionData section : snapshot.sections()) {
            if (section.y() == sectionY) {
                LevelChunkSection chunkSection = section.chunkSection();
                if (chunkSection == null) {
                    return null;
                }
                return chunkSection.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            }
        }
        return null;
    }

    /**
     * The single {@code ItemStackWithSlot} entry for {@code book} at bookshelf slot {@code slot}, the
     * {@link BookshelfCandidate} per-slot value: a six-slot serialize via {@link ContainerSink} whose one non-empty
     * entry carries {@code Slot = slot}, so the gate can assemble surviving slots into an {@code
     * "Items"} list vanilla reads straight back. An empty book yields an empty compound the gate ignores.
     */
    static CompoundTag captureBookSlotEntry(ContainerSink sink, ItemStack book, int slot, RegistryAccess registries) {
        NonNullList<ItemStack> items = NonNullList.withSize(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.size(),
                ItemStack.EMPTY);
        items.set(slot, book);
        CompoundTag holder = sink.captureItems(items, registries);
        if (holder.get("Items") instanceof ListTag list && !list.isEmpty()
                && list.get(0) instanceof CompoundTag entry) {
            return entry;
        }
        return new CompoundTag();
    }

    /**
     * Serialize {@code disc} to a holder carrying the jukebox disc ({@code "RecordItem"}) plus its song-start tick
     * ({@code "ticks_since_song_started"} = 0, since the disc just started at the click). Vanilla loadAdditional starts
     * the song player from that tick on load, so the saved jukebox emits the note particles; the disc sound itself
     * cannot resume on load (an MC limitation, it fires only on the insert event).
     */
    static CompoundTag captureRecordItem(ItemStack disc, RegistryAccess registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        CompoundTag holder = new CompoundTag();
        holder.put("RecordItem", ItemStack.CODEC.encodeStart(ops, disc).getOrThrow());
        holder.putLong("ticks_since_song_started", 0L);
        return holder;
    }

    /** Serialize {@code occupants} to a holder carrying {@code "bees"} (the beehive occupants). */
    static CompoundTag captureBees(List<BeehiveBlockEntity.Occupant> occupants, RegistryAccess registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        CompoundTag holder = new CompoundTag();
        holder.put("bees", BeehiveBlockEntity.Occupant.LIST_CODEC.encodeStart(ops, occupants).getOrThrow());
        return holder;
    }
}
