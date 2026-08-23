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

/**
 * Band-stable primitives over already-serialized chunk and entity NBT: locate a block entity by its saved position, and
 * carry a captured list, compound or scalar forward from an on-disk tag into a freshly captured one. Each is a plain
 * tag operation over the post-1.18 serialized form with no per-band codec, so one definition stays byte-identical
 * across the region layouts the read-merges span.
 */
final class NbtMerge {
    private NbtMerge() {}

    /** Whether {@code tag}'s value at {@code key} is a non-empty list. */
    static boolean isNonEmptyList(CompoundTag tag, String key) {
        return tag.get(key) instanceof ListTag && !((ListTag) tag.get(key)).isEmpty();
    }

    /** Copy {@code key}'s list from {@code disk} when {@code fresh}'s is absent or empty; true if a copy happened. */
    static boolean carryList(CompoundTag disk, CompoundTag fresh, String key) {
        ListTag diskList = disk.get(key) instanceof ListTag ? (ListTag) disk.get(key) : null;
        if (isNonEmptyList(fresh, key) || diskList == null || diskList.isEmpty()) {
            return false;
        }
        fresh.put(key, diskList.copy());
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
    static boolean carryListBySlot(CompoundTag disk, CompoundTag fresh, String key, int occupiedSlots) {
        ListTag diskList = disk.get(key) instanceof ListTag ? (ListTag) disk.get(key) : null;
        if (diskList == null || diskList.isEmpty()) {
            return false;
        }
        ListTag freshList = fresh.get(key) instanceof ListTag ? (ListTag) fresh.get(key) : new ListTag();
        Set<Integer> slots = new HashSet<>();
        for (Tag element : freshList) {
            if (element instanceof CompoundTag) {
                CompoundTag entry = (CompoundTag) element;
                slots.add(slotOf(entry));
            }
        }
        boolean carried = false;
        for (Tag element : diskList) {
            if (!(element instanceof CompoundTag)) {
                continue;
            }
            CompoundTag entry = (CompoundTag) element;
            int slot = slotOf(entry);
            // The authoritative block-state says whether a slot still holds anything. Carrying an entry for a
            // slot that now reads empty would put an item into the save that its own saved block-state denies:
            // invisible in the loaded world, unreachable by hand, and destroyed without a drop by the next
            // insert there.
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
     * An {@code ItemStackWithSlot} entry's slot index, compared numerically because vanilla's own read is numeric: an
     * absent key is slot 0, which is what the codec's default decodes it as, and a slot written as any numeric tag must
     * not read as a distinct slot from the same number written as a byte.
     */
    private static int slotOf(CompoundTag entry) {
        return entry.get("Slot") instanceof NumericTag ? ((NumericTag) entry.get("Slot")).getAsByte() & 0xFF : 0;
    }

    /**
     * Copy {@code key}'s compound, and its optional {@code sidecarKey}, from {@code disk} when {@code fresh} holds no
     * compound at {@code key}; true if a copy happened. The compound sibling of {@link #carryList}: a lectern's
     * {@code "Book"}/{@code "Page"} and a jukebox's {@code "RecordItem"}/{@code "ticks_since_song_started"} share it.
     */
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
    static boolean carryValue(CompoundTag disk, CompoundTag fresh, String key, @Nullable Tag clientDefault) {
        Tag value = disk.get(key);
        if (value == null || value.equals(clientDefault)) {
            return false; // nothing worth carrying; the disk side is itself at the default
        }
        Tag current = fresh.get(key);
        if (current != null && !current.equals(clientDefault)) {
            return false; // the fresh side carries a real capture of its own
        }
        fresh.put(key, value.copy());
        return true;
    }

    /**
     * Whether {@code blockEntity}'s saved {@code x/y/z} match {@code pos}, compared as {@link IntTag}s (band-stable).
     */
    static boolean isBlockEntityAt(CompoundTag blockEntity, BlockPos pos) {
        return new IntTag(pos.getX()).equals(blockEntity.get("x"))
                && new IntTag(pos.getY()).equals(blockEntity.get("y"))
                && new IntTag(pos.getZ()).equals(blockEntity.get("z"));
    }
}
