// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;

final class NbtMerge {
    private NbtMerge() {}

    static boolean isNonEmptyList(NBTTagCompound tag, String key) {
        return tag.getTag(key) instanceof NBTTagList && !((NBTTagList) tag.getTag(key)).isEmpty();
    }

    static boolean carryList(NBTTagCompound disk, NBTTagCompound fresh, String key) {
        NBTTagList diskList = disk.getTag(key) instanceof NBTTagList ? (NBTTagList) disk.getTag(key) : null;
        if (isNonEmptyList(fresh, key) || diskList == null || diskList.isEmpty()) {
            return false;
        }
        fresh.setTag(key, diskList.copy());
        return true;
    }

    /**
     * Not the rule for a container captured from an opened menu, whose fresh list is the whole container and therefore
     * ground truth: a union there would resurrect items the player watched leave.
     */
    static boolean carryListBySlot(NBTTagCompound disk, NBTTagCompound fresh, String key, int occupiedSlots) {
        NBTTagList diskList = disk.getTag(key) instanceof NBTTagList ? (NBTTagList) disk.getTag(key) : null;
        if (diskList == null || diskList.isEmpty()) {
            return false;
        }
        NBTTagList freshList = fresh.getTag(key) instanceof NBTTagList ? (NBTTagList) fresh.getTag(key)
                : new NBTTagList();
        Set<Integer> slots = new HashSet<>();
        for (NBTBase element : freshList) {
            if (element instanceof NBTTagCompound) {
                NBTTagCompound entry = (NBTTagCompound) element;
                slots.add(slotOf(entry));
            }
        }
        boolean carried = false;
        for (NBTBase element : diskList) {
            if (!(element instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound entry = (NBTTagCompound) element;
            int slot = slotOf(entry);
            if ((occupiedSlots & (1 << slot)) == 0 || !slots.add(slot)) {
                continue;
            }
            freshList.appendTag(entry.copy());
            carried = true;
        }
        if (carried) {
            fresh.setTag(key, freshList);
        }
        return carried;
    }

    private static int slotOf(NBTTagCompound entry) {
        return entry.getTag("Slot") instanceof NBTPrimitive ? ((NBTPrimitive) entry.getTag("Slot")).getByte() & 0xFF
                : 0;
    }

    static boolean carryCompound(NBTTagCompound disk, NBTTagCompound fresh, String key, @Nullable String sidecarKey) {
        NBTTagCompound diskCompound = disk.getTag(key) instanceof NBTTagCompound ? (NBTTagCompound) disk.getTag(key)
                : null;
        if (fresh.getTag(key) instanceof NBTTagCompound || diskCompound == null) {
            return false;
        }
        fresh.setTag(key, diskCompound.copy());
        if (sidecarKey != null) {
            NBTBase sidecar = disk.getTag(sidecarKey);
            if (sidecar != null) {
                fresh.setTag(sidecarKey, sidecar.copy());
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
    static boolean carryValue(NBTTagCompound disk, NBTTagCompound fresh, String key, @Nullable NBTBase clientDefault) {
        NBTBase value = disk.getTag(key);
        if (value == null || value.equals(clientDefault)) {
            return false;
        }
        NBTBase current = fresh.getTag(key);
        if (current != null && !current.equals(clientDefault)) {
            return false;
        }
        fresh.setTag(key, value.copy());
        return true;
    }

    static boolean isBlockEntityAt(NBTTagCompound blockEntity, BlockPos pos) {
        return new NBTTagInt(pos.getX()).equals(blockEntity.getTag("x"))
                && new NBTTagInt(pos.getY()).equals(blockEntity.getTag("y"))
                && new NBTTagInt(pos.getZ()).equals(blockEntity.getTag("z"));
    }
}
