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
 * At a position in {@code openTimeCaptured} the fresh side wins even when it is empty, per
 * {@link CapturedBlockField#capturesWholeOnOpen}.
 */
final class ChunkMerge {
    /**
     * Written as a literal id rather than resolved through the block-entity registry, so this file stays a pure tag
     * operation with no registry or bootstrap dependency; where the version has the type, {@code ChunkMergeTest} pins
     * it against the registry.
     */
    static final String CHISELED_BOOKSHELF_ID = "minecraft:chiseled_bookshelf";

    private static final Tag CHISELED_BOOKSHELF_ID_TAG = StringTag.valueOf(CHISELED_BOOKSHELF_ID);

    private static final int ALL_SLOTS = -1;

    private static final Long2IntMap NO_OCCUPANCY = occupancyMap();

    private ChunkMerge() {}

    static LongSet capturedPositions(Collection<BlockPos> positions) {
        LongOpenHashSet packed = new LongOpenHashSet();
        for (BlockPos pos : positions) {
            packed.add(pos.asLong());
        }
        return packed;
    }

    static Long2IntOpenHashMap occupancyMap() {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap();
        map.defaultReturnValue(ALL_SLOTS);
        return map;
    }

    public static int merge(CompoundTag onDisk, CompoundTag fresh) {
        return merge(onDisk, fresh, NO_OCCUPANCY, LongSets.EMPTY_SET, LongSets.EMPTY_SET);
    }

    /**
     * Only a container captured a slot at a time consults {@code occupancyByPos}; a position the map does not name
     * keeps every on-disk slot, which is the over-capture direction taken when the occupancy is unknown.
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
                continue;
            }
            CompoundTag diskBlockEntity = matching(diskBlockEntities, freshBlockEntity);
            if (diskBlockEntity != null && carryForward(diskBlockEntity, freshBlockEntity, freshPos.getAsLong(),
                    occupancyByPos, openTimeCaptured, replaced)) {
                merged++;
            }
        }
        return merged;
    }

    static boolean hasCapturedContent(CompoundTag blockEntity) {
        for (CapturedBlockField field : CapturedBlockField.fields()) {
            if (field.holdsContent(blockEntity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean carryForward(CompoundTag disk, CompoundTag fresh, long freshPos,
            Long2IntMap occupancyByPos, LongSet openTimeCaptured, LongSet replaced) {
        if (replaced.contains(freshPos)) {
            // A block was placed in this cell this session, so the block entity on disk is the one it replaced. The
            // match this merge makes is position plus type, which a same-type replacement satisfies.
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
     * Deliberately not a sentinel long: every 64-bit value is a representable {@link BlockPos}, so a sentinel would be
     * a position rather than an absence.
     */
    private static OptionalLong packedPos(CompoundTag blockEntity) {
        IntTag x = blockEntity.get("x") instanceof IntTag ? (IntTag) blockEntity.get("x") : null;
        IntTag y = blockEntity.get("y") instanceof IntTag ? (IntTag) blockEntity.get("y") : null;
        IntTag z = blockEntity.get("z") instanceof IntTag ? (IntTag) blockEntity.get("z") : null;
        if (x == null || y == null || z == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(BlockPos.asLong(x.getAsInt(), y.getAsInt(), z.getAsInt()));
    }

    private static int occupancyOf(long pos, Long2IntMap occupancyByPos) {
        return occupancyByPos.isEmpty() ? ALL_SLOTS : occupancyByPos.get(pos);
    }

    /**
     * Every container's captured list is the whole container and therefore ground truth, so a fresh list there must
     * replace the on-disk one or an item the player watched leave would come back.
     */
    private static boolean capturesPerSlot(CompoundTag blockEntity) {
        return CHISELED_BOOKSHELF_ID_TAG.equals(blockEntity.get("id"));
    }

    /**
     * Position is the {@code x/y/z} {@link IntTag} compare; type is the {@code id}, so a position whose block entity
     * changed type between two visits (a chest replaced by a barrel at the same spot) is treated as new-and-empty
     * rather than inheriting the prior type's contents.
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
