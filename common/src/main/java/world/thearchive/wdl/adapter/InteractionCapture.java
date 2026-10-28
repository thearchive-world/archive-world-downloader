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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class InteractionCapture {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile @Nullable InteractionCapture active;

    private final ContainerSink containerSink;
    private final RegistryAccess registries;

    /**
     * Whether the recognizer is live, coupled to {@code recaptureChunks}: the reconcile gate confirms a candidate only
     * against a post-interaction block-state, so without re-capture a candidate (insert or place) in an
     * already-captured chunk fails the gate and is silently discarded. Record nothing in that combination rather than
     * stash a doomed candidate.
     */
    private final boolean recaptureEnabled;

    /**
     * The per-chunk half of the same rule: whether the chunk an interaction lands in will still be captured, so the
     * gate has a post-interaction block-state to confirm against and a flush to drain the candidate. A chunk already
     * written and not eligible for re-capture has neither, so a candidate recorded there can only ever be dropped,
     * while its optimistic mark, where it has one, has already told the outline the content is downloaded.
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

        boolean itemsBundle() {
            return itemsBundle;
        }

        boolean confirms(BlockState state) {
            return confirm.test(state);
        }
    }

    sealed interface Candidate permits BookshelfCandidate, HolderCandidate {}

    record BookshelfCandidate(Map<Integer, CompoundTag> slotEntries) implements Candidate {}

    record HolderCandidate(InteractionKind kind, CompoundTag holder) implements Candidate {}

    record ChunkBundles(Map<BlockPos, CompoundTag> items, Map<BlockPos, CompoundTag> holders) {}

    private void onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
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

    private void recognize(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        BlockPos clicked = hit.getBlockPos().immutable();
        BlockState state = level.getBlockState(clicked);
        boolean inserted = false;
        if (state.getBlock() instanceof ChiseledBookShelfBlock bookshelf) {
            inserted = recordBookshelfInsert(bookshelf, state, clicked, hit, stack);
        } else if (state.getBlock() instanceof JukeboxBlock) {
            inserted = recordJukeboxInsert(state, clicked, stack);
        }
        // A right-click on a jukebox that the block did not consume as an insert (a non-disc) still places a held block
        // against that face, so fall through to recordPlace rather than returning: vanilla's useItemOn yields to item
        // use on a non-consuming result.
        if (!inserted && recaptureEnabled) {
            recordPlace(player, hand, hit, stack);
        }
    }

    boolean recordBookshelfInsert(ChiseledBookShelfBlock bookshelf, BlockState state, BlockPos pos,
            BlockHitResult hit, ItemStack stack) {
        if (!stack.is(ItemTags.BOOKSHELF_BOOKS) || !isCapturable(pos)) {
            return false;
        }
        OptionalInt hitSlot = bookshelfHitSlot(state, hit);
        if (hitSlot.isEmpty()) {
            return false;
        }
        int slot = hitSlot.getAsInt();
        if (!slotInRange(slot) || slotOccupied(state, slot)) {
            return false;
        }
        // Vanilla inserts exactly one book and the slot holds one, so capture a single item rather than the held
        // stack's count, which would otherwise persist an invalid over-count on reload.
        CompoundTag entry = captureBookSlotEntry(containerSink, stack.copyWithCount(1), slot, registries);
        if (entry.isEmpty()) {
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
     * Mirrors vanilla's private {@code ChiseledBookShelfBlock.getHitSlot}: the face is three columns wide and two rows
     * tall, the top row slots 0 to 2 and the bottom row 3 to 5.
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
        if (state.getValue(JukeboxBlock.HAS_RECORD) || !(stack.getItem() instanceof RecordItem)
                || !isCapturable(pos)) {
            return false;
        }
        insertStash.put(pos, new HolderCandidate(InteractionKind.JUKEBOX, captureRecordItem(stack)));
        return true;
    }

    private void recordPlace(Player player, InteractionHand hand, BlockHitResult hit, ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return;
        }
        // Without this check every right-click holding a block item predicts a placement into whatever cell the clicked
        // face points at, including a cell already occupied by the very block type the reconcile gate confirms on,
        // which is exactly the state that makes writing wrong.
        BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hit);
        if (!context.canPlace()) {
            return;
        }
        // The cell the placement lands in: relative to the clicked face normally, but the clicked cell when the target
        // is replaceable (grass, water), which the naive relative(face) gets wrong.
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
            // The open-time captures are not stale here, and this is the one place that inverts. An un-capturable cell
            // is one whose chunk is already written and will not be captured again, so the block entity the residual
            // sweep folds onto is the one on disk, which still predates this placement. Dropping the holder there would
            // delete a capture that was about to land correctly and that nothing else can ever write.
            return;
        }
        placementSink.blockPlacedAt(placedPos.asLong());
        Block block = Block.byItem(stack.getItem());
        if (block instanceof ShulkerBoxBlock) {
            CompoundTag blockEntityTag = BlockItem.getBlockEntityData(stack);
            if (blockEntityTag != null && blockEntityTag.contains("Items", 9)) {
                NonNullList<ItemStack> items = NonNullList.withSize(ShulkerBoxBlockEntity.CONTAINER_SIZE,
                        ItemStack.EMPTY);
                ContainerHelper.loadAllItems(blockEntityTag, items);
                CompoundTag holder = containerSink.captureItems(items, registries);
                placeStash.put(placedPos, new HolderCandidate(InteractionKind.SHULKER, holder));
                placedContainerSink.containerCaptured(placedPos.asLong(), shulkerBlockEntityId());
            }
        } else if (block instanceof BeehiveBlock) {
            CompoundTag blockEntityTag = BlockItem.getBlockEntityData(stack);
            if (blockEntityTag != null) {
                ListTag beesList = blockEntityTag.getList("Bees", 10);
                if (!beesList.isEmpty()) {
                    CompoundTag holder = captureBees(beesList);
                    placeStash.put(placedPos, new HolderCandidate(InteractionKind.BEEHIVE, holder));
                }
            }
        }
    }

    @SuppressWarnings("NullAway") // getKey is non-null for a registered vanilla block-entity type
    private static String shulkerBlockEntityId() {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(BlockEntityType.SHULKER_BOX).toString();
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
            return new ChunkBundles(Map.of(), Map.of());
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
        if (candidate instanceof HolderCandidate held && !held.kind().itemsBundle()) {
            bundles.holders().put(pos, holder);
        } else {
            bundles.items().put(pos, holder);
        }
    }

    static Optional<CompoundTag> confirm(BlockState authoritative, Candidate candidate) {
        if (candidate instanceof BookshelfCandidate bookshelf) {
            return confirmBookshelf(authoritative, bookshelf.slotEntries());
        }
        if (candidate instanceof HolderCandidate held) {
            return held.kind().confirms(authoritative) ? Optional.of(held.holder()) : Optional.empty();
        }
        throw new IllegalStateException("unhandled candidate: " + candidate);
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

    private static boolean slotInRange(int slot) {
        return slot >= 0 && slot < ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.size();
    }

    private static boolean slotOccupied(BlockState state, int slot) {
        return slotInRange(slot) && state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot));
    }

    static @Nullable BlockState blockStateAt(ChunkSnapshotSource snapshot, BlockPos pos) {
        int sectionY = pos.getY() >> 4;
        for (ChunkSnapshotSource.SectionData section : snapshot.sections()) {
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
     * A six-slot serialize via {@link ContainerSink} whose one non-empty entry carries {@code Slot = slot}, so the gate
     * can assemble surviving slots into an {@code "Items"} list vanilla reads straight back.
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
     * Serialize {@code disc} to a holder carrying the jukebox {@code disc} ({@code "RecordItem"}) plus a just-started
     * playing state ({@code "IsPlaying"} true, {@code "RecordStartTick"} and {@code "TickCount"} zero). Vanilla's
     * {@code JukeboxBlockEntity.load} restores that state, so the saved jukebox emits the note particles.
     */
    static CompoundTag captureRecordItem(ItemStack disc) {
        CompoundTag holder = new CompoundTag();
        holder.put("RecordItem", disc.save(new CompoundTag()));
        holder.putBoolean("IsPlaying", true);
        holder.putLong("RecordStartTick", 0L);
        holder.putLong("TickCount", 0L);
        return holder;
    }

    static CompoundTag captureBees(ListTag beesList) {
        CompoundTag holder = new CompoundTag();
        holder.put("Bees", beesList.copy());
        return holder;
    }
}
