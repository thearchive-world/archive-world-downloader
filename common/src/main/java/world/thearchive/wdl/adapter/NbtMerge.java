// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

final class NbtMerge {
    private NbtMerge() {}

    static boolean isNonEmptyList(CompoundTag tag, String key) {
        return tag.get(key) instanceof ListTag && !((ListTag) tag.get(key)).isEmpty();
    }

    static boolean carryList(CompoundTag disk, CompoundTag fresh, String key) {
        ListTag diskList = disk.get(key) instanceof ListTag ? (ListTag) disk.get(key) : null;
        if (isNonEmptyList(fresh, key) || diskList == null || diskList.isEmpty()) {
            return false;
        }
        fresh.put(key, diskList.copy());
        return true;
    }

    /**
     * Not the rule for a container captured from an opened menu, whose fresh list is the whole container and therefore
     * ground truth: a union there would resurrect items the player watched leave.
     */
    static boolean carryListBySlot(CompoundTag disk, CompoundTag fresh, String key, int occupiedSlots) {
        ListTag diskList = disk.get(key) instanceof ListTag ? (ListTag) disk.get(key) : null;
        if (diskList == null || diskList.isEmpty()) {
            return false;
        }
        ListTag freshList = fresh.get(key) instanceof ListTag ? (ListTag) fresh.get(key) : new ListTag();
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < freshList.size(); i++) {
            if (freshList.get(i) instanceof CompoundTag) {
                CompoundTag entry = (CompoundTag) freshList.get(i);
                slots.add(slotOf(entry));
            }
        }
        boolean carried = false;
        for (int i = 0; i < diskList.size(); i++) {
            if (!(diskList.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag entry = (CompoundTag) diskList.get(i);
            int slot = slotOf(entry);
            if ((occupiedSlots & (1 << slot)) == 0 || !slots.add(slot)) {
                continue;
            }
            freshList.add(entry.copy());
            carried = true;
        }
        if (carried) {
            fresh.put(key, freshList);
        }
        return carried;
    }

    private static int slotOf(CompoundTag entry) {
        return entry.get("Slot") instanceof NumericTag ? ((NumericTag) entry.get("Slot")).getAsByte() & 0xFF : 0;
    }

    static boolean carryCompound(CompoundTag disk, CompoundTag fresh, String key, @Nullable String sidecarKey) {
        CompoundTag diskCompound = disk.get(key) instanceof CompoundTag ? (CompoundTag) disk.get(key) : null;
        if (fresh.get(key) instanceof CompoundTag || diskCompound == null) {
            return false;
        }
        fresh.put(key, diskCompound.copy());
        if (sidecarKey != null) {
            Tag sidecar = disk.get(sidecarKey);
            if (sidecar != null) {
                fresh.put(sidecarKey, sidecar.copy());
            }
        }
        return true;
    }

    /**
     * Testing against a default rather than against absence is load-bearing, not defensive. Vanilla writes the brewing
     * stand's keys unconditionally, and this mod's chunk capture serializes the client's own block entity, so a freshly
     * captured brewing stand always carries the key at the client's zero value rather than omitting it. An absence test
     * would therefore never fire and the carry-forward would be inert.
     */
    static boolean carryValue(CompoundTag disk, CompoundTag fresh, String key, @Nullable Tag clientDefault) {
        Tag value = disk.get(key);
        if (value == null || value.equals(clientDefault)) {
            return false;
        }
        Tag current = fresh.get(key);
        if (current != null && !current.equals(clientDefault)) {
            return false;
        }
        fresh.put(key, value.copy());
        return true;
    }

    static boolean isBlockEntityAt(CompoundTag blockEntity, BlockPos pos) {
        return IntTag.valueOf(pos.getX()).equals(blockEntity.get("x"))
                && IntTag.valueOf(pos.getY()).equals(blockEntity.get("y"))
                && IntTag.valueOf(pos.getZ()).equals(blockEntity.get("z"));
    }
}
