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
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.math.BlockPos;
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

    private static final NBTBase CHISELED_BOOKSHELF_ID_TAG = new NBTTagString(CHISELED_BOOKSHELF_ID);

    private static final int ALL_SLOTS = -1;

    private static final Long2IntMap NO_OCCUPANCY = occupancyMap();

    private ChunkMerge() {}

    static LongSet capturedPositions(Collection<BlockPos> positions) {
        LongOpenHashSet packed = new LongOpenHashSet();
        for (BlockPos pos : positions) {
            packed.add(pos.toLong());
        }
        return packed;
    }

    static Long2IntOpenHashMap occupancyMap() {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap();
        map.defaultReturnValue(ALL_SLOTS);
        return map;
    }

    public static int merge(NBTTagCompound onDisk, NBTTagCompound fresh) {
        return merge(onDisk, fresh, NO_OCCUPANCY, LongSets.EMPTY_SET, LongSets.EMPTY_SET);
    }

    /**
     * Only a container captured a slot at a time consults {@code occupancyByPos}; a position the map does not name
     * keeps every on-disk slot, which is the over-capture direction taken when the occupancy is unknown.
     */
    public static int merge(NBTTagCompound onDisk, NBTTagCompound fresh, Long2IntMap occupancyByPos,
            LongSet openTimeCaptured, LongSet replaced) {
        NBTTagList freshBlockEntities = fresh.getCompoundTag("Level").getTag("TileEntities") instanceof NBTTagList
                ? (NBTTagList) fresh.getCompoundTag("Level").getTag("TileEntities")
                : null;
        NBTTagList diskBlockEntities = onDisk.getCompoundTag("Level").getTag("TileEntities") instanceof NBTTagList
                ? (NBTTagList) onDisk.getCompoundTag("Level").getTag("TileEntities")
                : null;
        if (freshBlockEntities == null || diskBlockEntities == null) {
            return 0;
        }
        int merged = 0;
        for (NBTBase element : NbtElements.of(freshBlockEntities)) {
            if (!(element instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound freshBlockEntity = (NBTTagCompound) element;
            OptionalLong freshPos = packedPos(freshBlockEntity);
            if (!freshPos.isPresent()) {
                continue;
            }
            NBTTagCompound diskBlockEntity = matching(diskBlockEntities, freshBlockEntity);
            if (diskBlockEntity != null && carryForward(diskBlockEntity, freshBlockEntity, freshPos.getAsLong(),
                    occupancyByPos, openTimeCaptured, replaced)) {
                merged++;
            }
        }
        return merged;
    }

    static boolean hasCapturedContent(NBTTagCompound blockEntity) {
        for (CapturedBlockField field : CapturedBlockField.fields()) {
            if (field.holdsContent(blockEntity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean carryForward(NBTTagCompound disk, NBTTagCompound fresh, long freshPos,
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
    private static OptionalLong packedPos(NBTTagCompound blockEntity) {
        NBTTagInt x = blockEntity.getTag("x") instanceof NBTTagInt ? (NBTTagInt) blockEntity.getTag("x") : null;
        NBTTagInt y = blockEntity.getTag("y") instanceof NBTTagInt ? (NBTTagInt) blockEntity.getTag("y") : null;
        NBTTagInt z = blockEntity.getTag("z") instanceof NBTTagInt ? (NBTTagInt) blockEntity.getTag("z") : null;
        if (x == null || y == null || z == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(new BlockPos(x.getInt(), y.getInt(), z.getInt()).toLong());
    }

    private static int occupancyOf(long pos, Long2IntMap occupancyByPos) {
        return occupancyByPos.isEmpty() ? ALL_SLOTS : occupancyByPos.get(pos);
    }

    /**
     * Every container's captured list is the whole container and therefore ground truth, so a fresh list there must
     * replace the on-disk one or an item the player watched leave would come back.
     */
    private static boolean capturesPerSlot(NBTTagCompound blockEntity) {
        return CHISELED_BOOKSHELF_ID_TAG.equals(blockEntity.getTag("id"));
    }

    /**
     * Position is the {@code x/y/z} {@link NBTTagInt} compare; type is the {@code id}, so a position whose block entity
     * changed type between two visits is treated as new-and-empty rather than inheriting the prior type's contents.
     */
    private static @Nullable NBTTagCompound matching(NBTTagList diskBlockEntities, NBTTagCompound freshBlockEntity) {
        NBTBase x = freshBlockEntity.getTag("x");
        NBTBase y = freshBlockEntity.getTag("y");
        NBTBase z = freshBlockEntity.getTag("z");
        NBTBase id = freshBlockEntity.getTag("id");
        if (!(x instanceof NBTTagInt) || !(y instanceof NBTTagInt) || !(z instanceof NBTTagInt) || id == null) {
            return null;
        }
        for (NBTBase element : NbtElements.of(diskBlockEntities)) {
            NBTTagCompound diskBlockEntity = element instanceof NBTTagCompound ? (NBTTagCompound) element : null;
            if (diskBlockEntity != null && id.equals(diskBlockEntity.getTag("id"))
                    && x.equals(diskBlockEntity.getTag("x")) && y.equals(diskBlockEntity.getTag("y"))
                    && z.equals(diskBlockEntity.getTag("z"))) {
                return diskBlockEntity;
            }
        }
        return null;
    }
}
