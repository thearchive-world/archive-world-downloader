// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Tracks whether an open menu's slot contents (or the lectern's reading page) changed since the last stash, so the
 * per-tick last-seen-wins stash re-serializes only on a real change rather than every tick (a shulker-filled double
 * chest is around fifteen hundred item encodes per pass). The change signal is per-slot stack identity, count, and
 * bundle contents: a client-side slot mutation either replaces the slot's stack object (a sync packet's fresh stack, a
 * click prediction's move or split), changes its count (a prediction's grow or shrink), or, in the one vanilla in-place
 * case, mutates the bundle's contents NBT in place (BundleItem's click-prediction override mutates the slot-resident
 * stack's tag, and the server's matching ack resends no slot; the contents are a mutable {@code "Items"} list, so a
 * value compare of a per-slot snapshot catches an insert or remove). BundleItem is the only vanilla overrider of the
 * stack-on-stack click hooks, so no other item mutates its contents in a slot. The signal also carries a small
 * menu-only ContainerData vector (a crafter's disabled and triggered flags, a brewing stand's brew time and fuel), so a
 * data-only tick like a running brew with no slot movement re-stashes. Main-thread only, reset at bind and close so one
 * open menu never inherits another's snapshot.
 */
final class MenuChangeTracker {
    static final int[] NO_DATA = new int[0];

    private ItemStack @Nullable [] lastStacks;
    private int @Nullable [] lastCounts;
    // A slot with no bundle holds a null element (most slots), so this array's elements are nullable at
    // runtime. Each element is a value copy of the bundle's contents NBT: below the component era a bundle
    // mutates its stack's tag in place, so only a value compare of a snapshot catches an insert or remove.
    private Tag @Nullable [] lastBundles;
    private int @Nullable [] lastData;
    private int lastPage;

    /**
     * Whether {@code slots} (or {@code page}) differ from the last snapshot; when they do, the snapshot advances to the
     * current state, so a true return means "stash now". The first call after a reset is always a change (there is
     * nothing to compare against, and the stash must run at least once).
     */
    boolean changedSince(List<Slot> slots, int page, int[] data) {
        ItemStack[] stacks = lastStacks;
        int[] counts = lastCounts;
        Tag[] bundles = lastBundles;
        if (stacks == null || counts == null || bundles == null || stacks.length != slots.size()
                || page != lastPage || !Arrays.equals(data, lastData)) {
            snapshot(slots, page, data);
            return true;
        }
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = slots.get(i).getItem();
            if (current != stacks[i] || current.getCount() != counts[i]
                    || !Objects.equals(bundleSignal(current), bundles[i])) {
                snapshot(slots, page, data);
                return true;
            }
        }
        return false;
    }

    /** Drop the snapshot (and its strong stack references) so the next open starts fresh. */
    void reset() {
        lastStacks = null;
        lastCounts = null;
        lastBundles = null;
        lastData = null;
        lastPage = 0;
    }

    private void snapshot(List<Slot> slots, int page, int[] data) {
        ItemStack[] stacks = lastStacks;
        int[] counts = lastCounts;
        Tag[] bundles = lastBundles;
        if (stacks == null || counts == null || bundles == null || stacks.length != slots.size()) {
            stacks = new ItemStack[slots.size()];
            counts = new int[slots.size()];
            bundles = new Tag[slots.size()];
            lastStacks = stacks;
            lastCounts = counts;
            lastBundles = bundles;
        }
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = slots.get(i).getItem();
            stacks[i] = current;
            counts[i] = current.getCount();
            bundles[i] = bundleSignal(current);
        }
        lastData = data.clone();
        lastPage = page;
    }

    /** A value copy of a bundle's contents list, or null when the stack is not a bundle or carries none. */
    private static @Nullable Tag bundleSignal(ItemStack stack) {
        if (!(stack.getItem() instanceof BundleItem)) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return null;
        }
        Tag items = tag.get("Items");
        return items == null ? null : items.copy();
    }
}
