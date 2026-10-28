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
        return tag.get(key) instanceof ListTag list && !list.isEmpty();
    }

    static boolean carryList(CompoundTag disk, CompoundTag fresh, String key) {
        if (isNonEmptyList(fresh, key) || !(disk.get(key) instanceof ListTag diskList) || diskList.isEmpty()) {
            return false;
        }
        fresh.put(key, diskList.copy());
        return true;
    }

    /**
     * The carry-forward for a container whose capture unit is a single slot rather than the whole container, where the
     * fresh list names only the slots this session captured and {@link #carryList}'s all-or-nothing rule would drop the
     * rest.
     *
     * <p>Not the rule for a container captured from an opened menu, whose fresh list is the whole container and
     * therefore ground truth: a union there would resurrect items the player watched leave.
     */
    static boolean carryListBySlot(CompoundTag disk, CompoundTag fresh, String key, int occupiedSlots) {
        if (!(disk.get(key) instanceof ListTag diskList) || diskList.isEmpty()) {
            return false;
        }
        ListTag freshList = fresh.get(key) instanceof ListTag list ? list : new ListTag();
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < freshList.size(); i++) {
            if (freshList.get(i) instanceof CompoundTag entry) {
                slots.add(slotOf(entry));
            }
        }
        boolean carried = false;
        for (int i = 0; i < diskList.size(); i++) {
            if (!(diskList.get(i) instanceof CompoundTag entry)) {
                continue;
            }
            int slot = slotOf(entry);
            // The authoritative block-state says whether a slot still holds anything. Carrying an entry for a slot that
            // now reads empty would put an item into the save that its own saved block-state denies: invisible in the
            // loaded world, and destroyed without a drop by a hand insert there while it still reads empty.
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

    /**
     * An item entry's slot index, compared numerically because vanilla's own read is numeric: an absent key is slot 0,
     * which is what that read decodes it as, and a slot written as any numeric tag must not read as a distinct slot
     * from the same number written as a byte.
     */
    private static int slotOf(CompoundTag entry) {
        return entry.get("Slot") instanceof NumericTag slot ? slot.getAsByte() & 0xFF : 0;
    }

    static boolean carryCompound(CompoundTag disk, CompoundTag fresh, String key, @Nullable String sidecarKey) {
        if (fresh.get(key) instanceof CompoundTag || !(disk.get(key) instanceof CompoundTag diskCompound)) {
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
     * Testing against a default rather than against absence is load-bearing, not defensive. Vanilla writes these keys
     * unconditionally, and this mod's chunk capture serializes the client's own block entity, so a freshly captured
     * crafter or brewing stand always carries the key at the client's zero value rather than omitting it. An absence
     * test would therefore never fire and the carry-forward would be inert.
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
