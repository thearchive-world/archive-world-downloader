// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Collection;
import java.util.OptionalLong;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The read-merge for the {@code region/} target, reached only where a prior chunk was found at the position, whether
 * that prior is a resumed download's or this one's own earlier flush of the same chunk. Carry forward every
 * interaction-captured datum from the on-disk chunk into a freshly re-captured one, in place, so a re-walk live-updates
 * the terrain without wiping what only an open could capture. Driven by the fresh chunk's block entities (the current
 * terrain is authoritative, so a container removed since the prior write is not resurrected), matched to the on-disk
 * chunk by {@code x/y/z}, preferring non-empty where nothing this write captured says otherwise: a container captured
 * rich by either write keeps it, captured by neither stays empty, re-captured rich takes the fresher copy.
 *
 * <p>Two exemptions cut across that. {@code openTimeCaptured} names the positions whose contents this write folded on,
 * where the fresh side is a whole-container capture rather than a client default and therefore wins even when it is
 * empty, per {@link CapturedBlockField#capturesWholeOnOpen}; it exempts the contents and the open-time state keys and
 * nothing else. {@code replaced} is the wider one and refuses every field.
 *
 * <p>The on-disk layer beside {@link ContainerMerge}'s in-session stash merge: where {@link ContainerMerge} folds an
 * open-time stash holder into the fresh chunk on the main thread before the tag is queued, this folds the prior on-disk
 * chunk into the fresh one on the writer thread after the on-disk read. Pure {@code CompoundTag} in/out and
 * band-agnostic over the pre-1.18 {@code Level.TileEntities} layout: both tags are already in the region's own
 * serialized form, so a carry-forward is a direct tag copy with no per-band serialize. The fields carried are
 * {@link CapturedBlockField}, the same list the open-time write reads, so a datum this mod writes on one visit cannot
 * be dropped by the next.
 *
 * <p>The preferring-non-empty rule holds only where the fresh list is the whole container, which the chiseled
 * bookshelf's never is; see {@link #capturesPerSlot} for that one and for what replaces the rule there.
 */
final class ChunkMerge {
    /**
     * The one block entity whose {@code "Items"} this mod can only ever capture a slot at a time. Written as the
     * literal vanilla registry key rather than resolved through {@code BuiltInRegistries}, so this file stays a pure
     * tag operation with no registry or bootstrap dependency; {@code ChunkMergeTest} pins it against the registry.
     */
    static final String CHISELED_BOOKSHELF_ID = "minecraft:chiseled_bookshelf";

    private static final Tag CHISELED_BOOKSHELF_ID_TAG = new StringTag(CHISELED_BOOKSHELF_ID);

    /** Every slot occupied, the answer used where no occupancy was recorded for a position. */
    private static final int ALL_SLOTS = -1;

    private static final Long2IntMap NO_OCCUPANCY = occupancyMap();

    private ChunkMerge() {}

    /** The positions a bundle of open-time holders names, for the main thread to hand the merge. */
    static LongSet capturedPositions(Collection<BlockPos> positions) {
        LongOpenHashSet packed = new LongOpenHashSet();
        for (BlockPos pos : positions) {
            packed.add(pos.asLong());
        }
        return packed;
    }

    /** An occupancy map whose unrecorded positions answer {@link #ALL_SLOTS}, for the main thread to fill. */
    static Long2IntOpenHashMap occupancyMap() {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap();
        map.defaultReturnValue(ALL_SLOTS);
        return map;
    }

    /**
     * Carry forward the on-disk chunk's interaction-captured block-entity contents into {@code fresh}, in place,
     * returning how many block entities received a carry-forward. A fresh chunk with no {@code Level.TileEntities}
     * list, or an on-disk chunk with none, is a no-op.
     */
    public static int merge(CompoundTag onDisk, CompoundTag fresh) {
        return merge(onDisk, fresh, NO_OCCUPANCY, LongSets.EMPTY_SET, LongSets.EMPTY_SET);
    }

    /**
     * The same carry-forward with the per-slot occupancy the main thread read off the captured snapshot, keyed by
     * packed {@link BlockPos}. Only a container captured a slot at a time consults it; a position the map does not name
     * keeps every on-disk slot, which is the over-capture direction taken when the occupancy is unknown.
     *
     * <p>{@code openTimeCaptured} names the positions this write folded an open-time holder onto. Their contents and
     * state keys are a fresh capture from the menu, so nothing is carried over them; everywhere else the fresh side is
     * a client default that says only that nothing was captured. {@code replaced} names the positions a block placement
     * landed in this session, where nothing carries at all: what is on disk there describes the block the placement
     * replaced.
     */
    public static int merge(CompoundTag onDisk, CompoundTag fresh, Long2IntMap occupancyByPos,
            LongSet openTimeCaptured, LongSet replaced) {
        ListTag freshBlockEntities = fresh.getCompound("Level").get("TileEntities") instanceof ListTag
                ? (ListTag) fresh.getCompound("Level").get("TileEntities")
                : null;
        ListTag diskBlockEntities = onDisk.getCompound("Level").get("TileEntities") instanceof ListTag
                ? (ListTag) onDisk.getCompound("Level").get("TileEntities")
                : null;
        if (freshBlockEntities == null || diskBlockEntities == null) {
            return 0;
        }
        int merged = 0;
        for (int i = 0; i < freshBlockEntities.size(); i++) {
            if (!(freshBlockEntities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag freshBlockEntity = (CompoundTag) freshBlockEntities.get(i);
            OptionalLong freshPos = packedPos(freshBlockEntity);
            if (!freshPos.isPresent()) {
                continue; // no complete position: it matches nothing on disk and names neither occupancy nor open
            }
            CompoundTag diskBlockEntity = matching(diskBlockEntities, freshBlockEntity);
            if (diskBlockEntity != null && carryForward(diskBlockEntity, freshBlockEntity, freshPos.getAsLong(),
                    occupancyByPos, openTimeCaptured, replaced)) {
                merged++;
            }
        }
        return merged;
    }

    /**
     * Whether {@code blockEntity}'s on-disk tag holds captured content: a non-empty container ({@code "Items"}), a
     * lectern book ({@code "Book"}), a jukebox disc ({@code "RecordItem"}), or beehive occupants ({@code "bees"}). The
     * single definition of prior-captured content, shared with {@link RecoveredScan} so the outline's recovered set and
     * the carry-forward read one definition of content; both read {@link CapturedBlockField}, so they extend together.
     * Open-time state alone is not content.
     *
     * <p>Content on disk is not the same as content the carry-forward will preserve, and this answers only the first
     * question; {@link RecoveredScan} enumerates where the two part.
     */
    static boolean hasCapturedContent(CompoundTag blockEntity) {
        for (CapturedBlockField field : CapturedBlockField.fields()) {
            if (field.holdsContent(blockEntity)) {
                return true;
            }
        }
        return false;
    }

    /** Carry forward every captured field the fresh block entity lacks from the disk one; true if any was. */
    private static boolean carryForward(CompoundTag disk, CompoundTag fresh, long freshPos,
            Long2IntMap occupancyByPos, LongSet openTimeCaptured, LongSet replaced) {
        if (replaced.contains(freshPos)) {
            // A block was placed in this cell this session, so the block entity on disk is the one it replaced.
            // The match this merge makes is position plus type, which a same-type replacement satisfies, and
            // the placement is the only evidence the client ever gets that the archived block is gone.
            return false;
        }
        boolean itemsBySlot = capturesPerSlot(fresh);
        int occupiedSlots = itemsBySlot ? occupancyOf(freshPos, occupancyByPos) : ALL_SLOTS;
        boolean freshlyOpened = openTimeCaptured.contains(freshPos);
        boolean carried = false;
        for (CapturedBlockField field : CapturedBlockField.fields()) {
            if (freshlyOpened && field.capturesWholeOnOpen(itemsBySlot)) {
                continue;
            }
            carried |= field.carry(disk, fresh, itemsBySlot, occupiedSlots);
        }
        return carried;
    }

    /**
     * This block entity's saved position packed, or an empty result when it carries no complete position. Deliberately
     * not a sentinel long: every 64-bit value is a representable {@link BlockPos}, so a sentinel would be a position
     * rather than an absence.
     */
    private static OptionalLong packedPos(CompoundTag blockEntity) {
        IntTag x = blockEntity.get("x") instanceof IntTag ? (IntTag) blockEntity.get("x") : null;
        IntTag y = blockEntity.get("y") instanceof IntTag ? (IntTag) blockEntity.get("y") : null;
        IntTag z = blockEntity.get("z") instanceof IntTag ? (IntTag) blockEntity.get("z") : null;
        if (x == null || y == null || z == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(new BlockPos(x.getAsInt(), y.getAsInt(), z.getAsInt()).asLong());
    }

    /** The occupancy mask recorded for {@code pos}, or every slot when none was recorded. */
    private static int occupancyOf(long pos, Long2IntMap occupancyByPos) {
        return occupancyByPos.isEmpty() ? ALL_SLOTS : occupancyByPos.get(pos);
    }

    /**
     * Whether this block entity's captured {@code "Items"} names single slots rather than the whole container, which
     * decides whether the on-disk list is unioned per slot or replaced wholesale. True for the chiseled bookshelf
     * alone: its contents never reach the client (its update tag is empty and it has no menu), so the only evidence of
     * a book is the local player's own insert click, and one capture is one slot. Every other container is captured
     * from an opened menu or a placed item's own component, which is the whole container and therefore ground truth, so
     * a fresh list there must replace the on-disk one or an item the player watched leave would come back.
     */
    private static boolean capturesPerSlot(CompoundTag blockEntity) {
        return CHISELED_BOOKSHELF_ID_TAG.equals(blockEntity.get("id"));
    }

    /**
     * The on-disk block entity sharing {@code freshBlockEntity}'s position and type, or null. Position is the
     * {@code x/y/z} {@link IntTag} compare; type is the {@code id}, so a position whose block entity changed type
     * between two visits (a chest replaced by a barrel at the same spot) is treated as new-and-empty rather than
     * inheriting the prior type's contents. A fresh block entity missing either its position or its {@code id} matches
     * nothing, so the carry-forward reaches only positions whose type is confirmed unchanged.
     */
    private static @Nullable CompoundTag matching(ListTag diskBlockEntities, CompoundTag freshBlockEntity) {
        Tag x = freshBlockEntity.get("x");
        Tag y = freshBlockEntity.get("y");
        Tag z = freshBlockEntity.get("z");
        Tag id = freshBlockEntity.get("id");
        if (!(x instanceof IntTag) || !(y instanceof IntTag) || !(z instanceof IntTag) || id == null) {
            return null;
        }
        for (int i = 0; i < diskBlockEntities.size(); i++) {
            CompoundTag diskBlockEntity = diskBlockEntities.get(i) instanceof CompoundTag
                    ? (CompoundTag) diskBlockEntities.get(i)
                    : null;
            if (diskBlockEntity != null && id.equals(diskBlockEntity.get("id"))
                    && x.equals(diskBlockEntity.get("x")) && y.equals(diskBlockEntity.get("y"))
                    && z.equals(diskBlockEntity.get("z"))) {
                return diskBlockEntity;
            }
        }
        return null;
    }
}
