// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.block.BlockJukebox;
import net.minecraft.block.BlockShulkerBox;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemRecord;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityShulkerBox;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The MC-typed, loader-agnostic recognizer for interaction-prediction capture: content the chunk packet and an opened
 * menu never carry to the client (jukebox discs, placed shulker contents) is predicted from the local player's own
 * right-click and reconciled against the authoritative synced block-state before it is persisted, so a prediction the
 * server never confirmed is never written.
 *
 * <p>Mirrors {@link EntityPacketCapture}: a connection-scoped {@code static volatile} publication point the per-loader
 * use-block hook resolves (the hook has no session reference), {@code null} when no download is running so the hook
 * no-ops. At the click ({@link #onUseBlock}, client main thread) it serializes the hand content to immutable NBT at
 * once (the snapshot discipline), so the stashes hold only detached data; the session drains them per chunk at flush
 * ({@link #drainChunk}), reconciling each candidate against the captured snapshot's block-state ({@link #confirm},
 * {@link #blockStateAt}) and routing the confirmed holders to their merge ({@link ContainerMerge}). At most one capture
 * runs at a time, so a process singleton is correct.
 *
 * <p>One of the two writes reuses the open-time {@code "Items"} path (shulker, via {@link ContainerSink}); the other
 * writes the item's pre-component block-entity form directly ({@code ItemStack.writeToNBT} under {@code "RecordItem"}).
 */
public final class InteractionCapture {
    private static final Logger LOGGER = LogManager.getLogger(InteractionCapture.class);

    private static volatile @Nullable InteractionCapture active;

    private final ContainerSink containerSink;

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

    /** Insert-time candidates (jukebox), keyed by the looked-at block pos. Main-thread only. */
    private final Map<BlockPos, Candidate> insertStash = new LinkedHashMap<>();

    /** Place-time candidates (shulker), keyed by the derived placed pos. Main-thread only. */
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

    InteractionCapture(ContainerSink containerSink, boolean recaptureEnabled,
            ChunkCaptureGate chunkCaptureGate, BookshelfSlotSink bookshelfSlotSink,
            PlacedContainerSink placedContainerSink, PlacementSink placementSink) {
        this.containerSink = containerSink;
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
    public static void dispatchUseBlock(EntityPlayer player, World level, EnumHand hand, RayTraceResult hit) {
        InteractionCapture capture = active;
        if (capture != null && level.isRemote && player == Minecraft.getMinecraft().player
                && !player.isSpectator()) {
            capture.onUseBlock(player, level, hand, hit);
        }
    }

    /**
     * The kind of a {@link HolderCandidate}, and the one descriptor per holder content type. Each constant carries the
     * reconcile predicate that confirms its content against the authoritative block-state, and whether its confirmed
     * holder folds into the open-time {@code "Items"} container bundle (a placed shulker, which opening supersedes) or
     * the generic holder merge ({@link ContainerMerge#mergeHolderChunkStash}: a jukebox disc). Adding a content type is
     * one constant that drives the gate, {@link #route}, and the merge together, so a half-wired type cannot compile
     * rather than silently capturing but never writing.
     */
    enum InteractionKind {
        JUKEBOX(false, state -> state.getBlock() instanceof BlockJukebox && state.getValue(BlockJukebox.HAS_RECORD)),
        SHULKER(true, state -> state.getBlock() instanceof BlockShulkerBox);

        private final boolean itemsBundle;
        private final Predicate<IBlockState> confirm;

        InteractionKind(boolean itemsBundle, Predicate<IBlockState> confirm) {
            this.itemsBundle = itemsBundle;
            this.confirm = confirm;
        }

        /** Whether a confirmed holder of this kind folds into the open-time {@code "Items"} bundle (a shulker). */
        boolean itemsBundle() {
            return itemsBundle;
        }

        /** Whether the authoritative synced {@code state} confirms this kind's predicted content is present. */
        boolean confirms(IBlockState state) {
            return confirm.test(state);
        }
    }

    /** A predicted interaction awaiting the reconcile gate. */
    interface Candidate {}

    /**
     * A jukebox disc or placed shulker: one merge-ready single-key holder ({@code "RecordItem"}/{@code "Items"}). The
     * gate keeps it whole only when the authoritative block-state confirms the content is present (HAS_RECORD, or the
     * expected placed block type).
     */
    static final class HolderCandidate implements Candidate {
        private final InteractionKind kind;
        private final NBTTagCompound holder;

        HolderCandidate(InteractionKind kind, NBTTagCompound holder) {
            this.kind = kind;
            this.holder = holder;
        }

        InteractionKind kind() {
            return kind;
        }

        NBTTagCompound holder() {
            return holder;
        }
    }

    /**
     * A chunk's confirmed holders, split by merge path: {@code items} folds through the open-time container
     * {@code "Items"} merge (placed shulker) under the open-time-wins precedence; {@code holders} takes the generic
     * field-copy merge (jukebox disc under {@code "RecordItem"}), each holder already carrying exactly the key its
     * block entity reads. One bundle drains per chunk.
     */
    static final class ChunkBundles {
        private final Map<BlockPos, NBTTagCompound> items;
        private final Map<BlockPos, NBTTagCompound> holders;

        ChunkBundles(Map<BlockPos, NBTTagCompound> items, Map<BlockPos, NBTTagCompound> holders) {
            this.items = items;
            this.holders = holders;
        }

        Map<BlockPos, NBTTagCompound> items() {
            return items;
        }

        Map<BlockPos, NBTTagCompound> holders() {
            return holders;
        }
    }

    /**
     * Observe one local-player right-click (client main thread). Recognize an insert into an existing jukebox or a
     * place of a content-bearing block-item, snapshot the hand content to immutable NBT at once, and stash it; the
     * reconcile gate at flush decides whether it survives. Never mutates the interaction or the live stack.
     */
    private void onUseBlock(EntityPlayer player, World level, EnumHand hand, RayTraceResult hit) {
        recordFailSoft(hit.getBlockPos(), () -> recognize(player, level, hand, hit));
    }

    /**
     * Run {@code recognition} with per-click failure isolation, mirroring the writer-side merge: the click-time encode
     * can throw on a pathological item whose network form diverges from its persistent form, so isolate it here rather
     * than letting it escape to the loader event and crash the client mid-download. Skips that one capture and logs.
     */
    void recordFailSoft(BlockPos pos, Runnable recognition) {
        try {
            recognition.run();
        } catch (RuntimeException e) {
            LOGGER.warn("skipping interaction capture at {}: recognition failed", pos, e);
        }
    }

    private void recognize(EntityPlayer player, World level, EnumHand hand, RayTraceResult hit) {
        ItemStack stack = player.getHeldItem(hand);
        // getBlockPos may return a MutableBlockPos; the stash key must be immutable, like every stash.
        BlockPos clicked = hit.getBlockPos().toImmutable();
        IBlockState state = level.getBlockState(clicked);
        boolean inserted = false;
        if (state.getBlock() instanceof BlockJukebox) {
            inserted = recordJukeboxInsert(state, clicked, stack);
        }
        // A right-click on a jukebox that the block did not consume as an insert (a non-disc, an occupied slot) still
        // places a held block against that face, so fall through to recordPlace rather than returning: vanilla's use
        // path yields to item use on a non-consuming result.
        if (!inserted && recaptureEnabled) {
            recordPlace(player, hit, stack);
        }
    }

    boolean recordJukeboxInsert(IBlockState state, BlockPos pos, ItemStack stack) {
        if (state.getValue(BlockJukebox.HAS_RECORD) || !(stack.getItem() instanceof ItemRecord)
                || !isCapturable(pos)) {
            return false; // an occupied jukebox ejects; only a playable disc inserts, and only where it can confirm
        }
        insertStash.put(pos, new HolderCandidate(InteractionKind.JUKEBOX, captureRecordItem(stack)));
        return true;
    }

    private void recordPlace(EntityPlayer player, RayTraceResult hit, ItemStack stack) {
        if (!(stack.getItem() instanceof ItemBlock)) {
            return;
        }
        // The same question vanilla's own ItemBlock.onItemUse asks before it places, one step earlier on the same
        // client state. This band has no unified (player, hand, hit) UseOnContext / BlockPlaceContext; reproduce its
        // positional math directly. The placement lands on the clicked cell when the clicked block is replaceable
        // (grass, water), else the cell against the clicked face; the place is allowed only where the player may edit
        // it and World.mayPlace accepts the block, exactly vanilla's own gate (the player is the mayPlace placer, so
        // it is excluded from the placement collision check just as vanilla's ItemBlock.onItemUse does).
        World level = player.world;
        Block placeBlock = Block.getBlockFromItem(stack.getItem());
        BlockPos clicked = hit.getBlockPos();
        EnumFacing facing = hit.sideHit;
        IBlockState clickedState = level.getBlockState(clicked);
        BlockPos placedPos = clickedState.getBlock().isReplaceable(level, clicked) ? clicked : clicked.offset(facing);
        if (stack.isEmpty() || !player.canPlayerEdit(placedPos, facing, stack)
                || !level.mayPlace(placeBlock, placedPos, false, facing, player)) {
            return;
        }
        // The stash key must be immutable, like every other stash.
        recordPlaceAt(placedPos.toImmutable(), stack);
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
        placementSink.blockPlacedAt(placedPos.toLong());
        Block block = Block.getBlockFromItem(stack.getItem());
        // Keyed by the (block-entity-NBT key, block) pairing: key presence alone does not imply this block saves
        // under the mapped key, and an unrecognized pairing has no general block-to-key mapping, so it drops.
        if (block instanceof BlockShulkerBox) {
            NBTTagCompound blockEntityTag = stack.getSubCompound("BlockEntityTag");
            if (blockEntityTag != null && blockEntityTag.hasKey("Items", 9)) {
                NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
                ItemStackHelper.loadAllItems(blockEntityTag, items);
                NBTTagCompound holder = containerSink.captureItems(items);
                placeStash.put(placedPos, new HolderCandidate(InteractionKind.SHULKER, holder));
                placedContainerSink.containerCaptured(placedPos.toLong(), shulkerBlockEntityId());
            }
        }
    }

    @SuppressWarnings("NullAway") // getKey is non-null for the registered vanilla shulker box block entity
    private static String shulkerBlockEntityId() {
        return TileEntity.getKey(TileEntityShulkerBox.class).toString();
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
            // the common no-interaction flush: skip the per-chunk maps
            return new ChunkBundles(ImmutableMap.of(), ImmutableMap.of());
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
            IBlockState state = blockStateAt(snapshot, entry.getKey());
            if (state == null) {
                continue;
            }
            Candidate candidate = entry.getValue();
            confirm(state, candidate).ifPresent(holder -> route(entry.getKey(), candidate, holder, bundles));
        }
        return bundles;
    }

    private static void route(BlockPos pos, Candidate candidate, NBTTagCompound holder, ChunkBundles bundles) {
        // The descriptor decides the merge path, so a new content type adds an InteractionKind constant, never a
        // case here: a holder kind not bound to the "Items" bundle takes the generic field-copy merge, while a
        // shulker confirms to an open-time "Items" holder.
        if (candidate instanceof HolderCandidate && !((HolderCandidate) candidate).kind().itemsBundle()) {
            bundles.holders().put(pos, holder);
        } else {
            bundles.items().put(pos, holder);
        }
    }

    /**
     * The reconcile gate: keep a predicted candidate's content only when the authoritative synced block-state
     * {@code authoritative} confirms it, otherwise drop it (a placement the server refused or the player reverted
     * reduces to the same negative gate). Pure, so it is exercised headless against hand-built block-states. The
     * candidates stand or fall whole.
     *
     * <p>The whole-confirm kinds gate on block type alone, since the synced block-state carries no occupant or contents
     * dimension: a server that strips a placed container's contents still confirms on type and persists the predicted
     * content. That is an accepted inherent ceiling of optimistic prediction, not a fixable case: there is no
     * client-side signal that distinguishes it from a confirmed placement.
     */
    static Optional<NBTTagCompound> confirm(IBlockState authoritative, Candidate candidate) {
        if (candidate instanceof HolderCandidate) {
            HolderCandidate held = (HolderCandidate) candidate;
            return held.kind().confirms(authoritative) ? Optional.of(held.holder()) : Optional.empty();
        }
        throw new IllegalStateException("unhandled candidate: " + candidate);
    }

    /**
     * The authoritative block-state at {@code pos} read off the captured chunk {@code snapshot} (the gate's source of
     * truth): the same artifact the merge writes into, and current even after the chunk unloads, since the re-capture
     * machinery keeps a hot chunk's snapshot block-state in step with the server. Returns null when no captured section
     * covers the pos's Y, so the gate fails closed (drops the candidate) rather than reading a default state; a real
     * interaction's pos never lands out of range.
     */
    static @Nullable IBlockState blockStateAt(ChunkSnapshotSource snapshot, BlockPos pos) {
        int sectionY = pos.getY() >> 4;
        for (ChunkSnapshotSource.SectionData section : snapshot.sections()) {
            if (section.y() == sectionY) {
                ExtendedBlockStorage chunkSection = section.chunkSection();
                if (chunkSection == null) {
                    return null;
                }
                return chunkSection.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            }
        }
        return null;
    }

    /**
     * Serialize {@code disc} to a holder carrying the jukebox disc ({@code "RecordItem"}) plus the just-started playing
     * state a fresh insert leaves ({@code "IsPlaying"} true, {@code "RecordStartTick"} and {@code "TickCount"} zero).
     * Vanilla restores that state, so the saved jukebox plays and emits the note particles; the disc sound itself
     * cannot resume on load (an MC limitation, it fires only on the insert event).
     *
     * <p>Below 1.15 vanilla {@code ItemStack.writeToNBT} puts the live stack's own {@code tag} compound into its
     * output, so the returned holder is detached before it is handed on and the caller owns it.
     */
    static NBTTagCompound captureRecordItem(ItemStack disc) {
        NBTTagCompound holder = new NBTTagCompound();
        holder.setTag("RecordItem", disc.writeToNBT(new NBTTagCompound()).copy());
        holder.setBoolean("IsPlaying", true);
        holder.setLong("RecordStartTick", 0L);
        holder.setLong("TickCount", 0L);
        return holder;
    }
}
