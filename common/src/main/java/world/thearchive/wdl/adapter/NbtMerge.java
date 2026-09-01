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

/**
 * Band-stable primitives over already-serialized chunk and entity NBT: locate a block entity by its saved position, and
 * carry a captured list, compound or scalar forward from an on-disk tag into a freshly captured one. Each is a plain
 * tag operation over the post-1.18 serialized form with no per-band codec, so one definition stays byte-identical
 * across the region layouts the read-merges span.
 */
final class NbtMerge {
    private NbtMerge() {}

    /** Whether {@code tag}'s value at {@code key} is a non-empty list. */
    static boolean isNonEmptyList(NBTTagCompound tag, String key) {
        return tag.getTag(key) instanceof NBTTagList && !((NBTTagList) tag.getTag(key)).hasNoTags();
    }

    /** Copy {@code key}'s list from {@code disk} when {@code fresh}'s is absent or empty; true if a copy happened. */
    static boolean carryList(NBTTagCompound disk, NBTTagCompound fresh, String key) {
        NBTTagList diskList = disk.getTag(key) instanceof NBTTagList ? (NBTTagList) disk.getTag(key) : null;
        if (isNonEmptyList(fresh, key) || diskList == null || diskList.hasNoTags()) {
            return false;
        }
        fresh.setTag(key, diskList.copy());
        return true;
    }

    /**
     * Union {@code key}'s on-disk list into {@code fresh}'s by the vanilla {@code "Slot"} index, the fresh entry
     * winning for a slot both sides name; true if any on-disk entry was added. The carry-forward for a container whose
     * capture unit is a single slot rather than the whole container, where the fresh list names only the slots this
     * session captured and {@link #carryList}'s all-or-nothing rule would drop the rest. An entry with no
     * {@code "Slot"} is slot 0, which is what {@code ItemStackWithSlot}'s codec decodes an absent one as.
     *
     * <p>Not the rule for a container captured from an opened menu, whose fresh list is the whole container and
     * therefore ground truth: a union there would resurrect items the player watched leave.
     */
    static boolean carryListBySlot(NBTTagCompound disk, NBTTagCompound fresh, String key, int occupiedSlots) {
        NBTTagList diskList = disk.getTag(key) instanceof NBTTagList ? (NBTTagList) disk.getTag(key) : null;
        if (diskList == null || diskList.hasNoTags()) {
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
            // The authoritative block-state says whether a slot still holds anything. Carrying an entry for a
            // slot that now reads empty would put an item into the save that its own saved block-state denies:
            // invisible in the loaded world, unreachable by hand, and destroyed without a drop by the next
            // insert there.
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

    /**
     * An {@code ItemStackWithSlot} entry's slot index, compared numerically because vanilla's own read is numeric: an
     * absent key is slot 0, which is what the codec's default decodes it as, and a slot written as any numeric tag must
     * not read as a distinct slot from the same number written as a byte.
     */
    private static int slotOf(NBTTagCompound entry) {
        return entry.getTag("Slot") instanceof NBTPrimitive ? ((NBTPrimitive) entry.getTag("Slot")).getByte() & 0xFF
                : 0;
    }

    /**
     * Copy {@code key}'s compound, and its optional {@code sidecarKey}, from {@code disk} when {@code fresh} holds no
     * compound at {@code key}; true if a copy happened. The compound sibling of {@link #carryList}: a lectern's
     * {@code "Book"}/{@code "Page"} and a jukebox's {@code "RecordItem"}/{@code "ticks_since_song_started"} share it.
     */
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
     * Copy {@code key}'s tag from {@code disk} when {@code fresh} carries nothing this mod captured, treating
     * {@code clientDefault} as nothing; true if a copy happened. The scalar sibling of {@link #carryList}.
     *
     * <p>Testing against a default rather than against absence is load-bearing, not defensive. Vanilla writes these
     * keys unconditionally, and this mod's chunk capture serializes the client's own block entity, so a freshly
     * captured crafter or brewing stand always carries the key at the client's zero value rather than omitting it. An
     * absence test would therefore never fire and the carry-forward would be inert.
     *
     * <p>The cost is that a re-open which genuinely captured the default loses to an earlier non-default capture, since
     * the two are indistinguishable here. That is the over-capture direction: the archive keeps the older captured
     * state rather than dropping the state entirely.
     */
    static boolean carryValue(NBTTagCompound disk, NBTTagCompound fresh, String key, @Nullable NBTBase clientDefault) {
        NBTBase value = disk.getTag(key);
        if (value == null || value.equals(clientDefault)) {
            return false; // nothing worth carrying; the disk side is itself at the default
        }
        NBTBase current = fresh.getTag(key);
        if (current != null && !current.equals(clientDefault)) {
            return false; // the fresh side carries a real capture of its own
        }
        fresh.setTag(key, value.copy());
        return true;
    }

    /**
     * Whether {@code blockEntity}'s saved {@code x/y/z} match {@code pos}, compared as {@link NBTTagInt}s
     * (band-stable).
     */
    static boolean isBlockEntityAt(NBTTagCompound blockEntity, BlockPos pos) {
        return new NBTTagInt(pos.getX()).equals(blockEntity.getTag("x"))
                && new NBTTagInt(pos.getY()).equals(blockEntity.getTag("y"))
                && new NBTTagInt(pos.getZ()).equals(blockEntity.getTag("z"));
    }
}
