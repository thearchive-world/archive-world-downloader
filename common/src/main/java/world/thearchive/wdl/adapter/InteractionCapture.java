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
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemRecord;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

public final class InteractionCapture {
    private static final Logger LOGGER = LogManager.getLogger(InteractionCapture.class);

    private static volatile @Nullable InteractionCapture active;

    private final ContainerSink containerSink;

    /**
     * Whether the recognizer is live, coupled to {@code recaptureChunks}: the reconcile gate confirms a candidate only
     * against a post-interaction block-state, so without re-capture a candidate in an already-captured chunk fails the
     * gate and is silently discarded. Record nothing in that combination rather than stash a doomed candidate.
     */
    private final boolean recaptureEnabled;

    /**
     * The per-chunk half of the same rule: whether the chunk an interaction lands in will still be captured, so the
     * gate has a post-interaction block-state to confirm against and a flush to drain the candidate. A chunk already
     * written and not eligible for re-capture has neither, so a candidate recorded there can only ever be dropped.
     */
    private final ChunkCaptureGate chunkCaptureGate;

    private final Map<BlockPos, Candidate> insertStash = new LinkedHashMap<>();

    private final Map<BlockPos, Candidate> placeStash = new LinkedHashMap<>();

    private final BookshelfSlotSink bookshelfSlotSink;

    private final PlacedContainerSink placedContainerSink;

    private final PlacementSink placementSink;

    @FunctionalInterface
    interface BookshelfSlotSink {
        void slotCaptured(long posKey, int slot, int occupiedMask);
    }

    @FunctionalInterface
    interface PlacedContainerSink {
        void containerCaptured(long posKey, String blockTypeId);
    }

    @FunctionalInterface
    interface PlacementSink {
        void blockPlacedAt(long posKey);
    }

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
     * no download is active or it is not the local player's own client-side interaction.
     *
     * <p>A spectator is excluded, because none of this can happen for one: the server's spectator branch opens only a
     * container block entity's menu and never runs the block's use handler, so no insert and no jukebox load occurs.
     * The guard is inert on a loader whose hook already declines to fire for a spectator and load-bearing on one whose
     * hook does not.
     */
    public static void dispatchUseBlock(EntityPlayer player, World level, EnumHand hand, RayTraceResult hit) {
        InteractionCapture capture = active;
        if (capture != null && level.isRemote && player == Minecraft.getMinecraft().player
                && !player.isSpectator()) {
            capture.onUseBlock(player, level, hand, hit);
        }
    }

    enum InteractionKind {
        JUKEBOX(false, state -> state.getBlock() instanceof BlockJukebox && state.getValue(BlockJukebox.HAS_RECORD));

        private final boolean itemsBundle;
        private final Predicate<IBlockState> confirm;

        InteractionKind(boolean itemsBundle, Predicate<IBlockState> confirm) {
            this.itemsBundle = itemsBundle;
            this.confirm = confirm;
        }

        boolean itemsBundle() {
            return itemsBundle;
        }

        boolean confirms(IBlockState state) {
            return confirm.test(state);
        }
    }

    interface Candidate {}

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

    private void onUseBlock(EntityPlayer player, World level, EnumHand hand, RayTraceResult hit) {
        recordFailSoft(hit.getBlockPos(), () -> recognize(player, level, hand, hit));
    }

    /**
     * Run {@code recognition} with per-click failure isolation: the click-time encode can throw, so isolate it here
     * rather than letting it escape to the loader event and crash the client mid-download.
     */
    void recordFailSoft(BlockPos pos, Runnable recognition) {
        try {
            recognition.run();
        } catch (RuntimeException e) {
            LOGGER.warn("skipping interaction capture at {}: recognition failed", pos, e);
        }
    }

    private void recognize(EntityPlayer player, World level, EnumHand hand, RayTraceResult hit) {
        @Nullable
        ItemStack stack = player.getHeldItem(hand);
        BlockPos clicked = hit.getBlockPos().toImmutable();
        IBlockState state = level.getBlockState(clicked);
        boolean inserted = false;
        if (state.getBlock() instanceof BlockJukebox) {
            inserted = recordJukeboxInsert(state, clicked, stack);
        }
        // A right-click on a jukebox that the block did not consume as an insert (a non-disc) still places a held block
        // against that face, so fall through to recordPlace rather than returning: vanilla's use path yields to item
        // use on a non-consuming result.
        if (!inserted && recaptureEnabled) {
            recordPlace(player, hit, stack);
        }
    }

    boolean recordJukeboxInsert(IBlockState state, BlockPos pos, @Nullable ItemStack stack) {
        if (stack == null || state.getValue(BlockJukebox.HAS_RECORD) || !(stack.getItem() instanceof ItemRecord)
                || !isCapturable(pos)) {
            return false;
        }
        insertStash.put(pos, new HolderCandidate(InteractionKind.JUKEBOX, captureRecordItem(stack)));
        return true;
    }

    private void recordPlace(EntityPlayer player, RayTraceResult hit, @Nullable ItemStack stack) {
        // An empty hand is a null stack at this band, so the emptiness test has to precede the first read of the
        // stack rather than sit in the placement gate below.
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return;
        }
        // The stash key is only right while this stays in step with vanilla's own ItemBlock.onItemUse, which places
        // into the clicked cell when the clicked block is replaceable and into the cell against the clicked face
        // otherwise.
        World level = player.world;
        Block placeBlock = Block.getBlockFromItem(stack.getItem());
        BlockPos clicked = hit.getBlockPos();
        EnumFacing facing = hit.sideHit;
        IBlockState clickedState = level.getBlockState(clicked);
        BlockPos placedPos = clickedState.getBlock().isReplaceable(level, clicked) ? clicked : clicked.offset(facing);
        if (!player.canPlayerEdit(placedPos, facing, stack)
                || !level.canBlockBePlaced(placeBlock, placedPos, false, facing, player, stack)) {
            return;
        }
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
            // The open-time captures are not stale here, and this is the one place that inverts. An un-capturable cell
            // is one whose chunk is already written and will not be captured again, so the block entity the residual
            // sweep folds onto is the one on disk, which still predates this placement. Dropping the holder there would
            // delete a capture that was about to land correctly and that nothing else can ever write.
            return;
        }
        placementSink.blockPlacedAt(placedPos.toLong());
    }

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
     * On a rebind the drop is mandatory: an old-dimension candidate's pos must not carry into the new dimension's
     * shared {@link ChunkPos} space, where a same-type block could merge wrong-dimension content.
     */
    List<BlockPos> drainResidualPositions() {
        List<BlockPos> positions = new ArrayList<>(insertStash.keySet());
        positions.addAll(placeStash.keySet());
        insertStash.clear();
        placeStash.clear();
        return positions;
    }

    ChunkBundles drainChunk(ChunkPos chunk, ChunkSnapshotSource snapshot) {
        if (insertStash.isEmpty() && placeStash.isEmpty()) {
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
        if (candidate instanceof HolderCandidate && !((HolderCandidate) candidate).kind().itemsBundle()) {
            bundles.holders().put(pos, holder);
        } else {
            bundles.items().put(pos, holder);
        }
    }

    static Optional<NBTTagCompound> confirm(IBlockState authoritative, Candidate candidate) {
        if (candidate instanceof HolderCandidate) {
            HolderCandidate held = (HolderCandidate) candidate;
            return held.kind().confirms(authoritative) ? Optional.of(held.holder()) : Optional.empty();
        }
        throw new IllegalStateException("unhandled candidate: " + candidate);
    }

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
     * Below 1.15 vanilla {@code ItemStack.writeToNBT} puts the live stack's own {@code tag} compound into its output,
     * so the returned holder is detached before it is handed on and the caller owns it.
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
