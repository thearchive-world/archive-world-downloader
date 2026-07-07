// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.jspecify.annotations.Nullable;

/**
 * Tracks whether an open menu's slot contents (or the lectern's reading page) changed since the last stash, so the
 * per-tick last-seen-wins stash re-serializes only on a real change rather than every tick (a shulker-filled double
 * chest is around fifteen hundred item encodes per pass). The change signal is per-slot stack identity, count, and
 * bundle contents: a client-side slot mutation either replaces the slot's stack object (a sync packet's fresh stack, a
 * click prediction's move or split), changes its count (a prediction's grow or shrink), or, in the one vanilla in-place
 * case, replaces the stack's {@code minecraft:bundle_contents} value (BundleItem's click-prediction override mutates
 * the slot-resident stack, and the server's matching ack resends no slot; BundleContents is immutable and swapped
 * wholesale on every insert or remove, so a reference compare catches it). BundleItem is the only vanilla overrider of
 * the stack-on-stack click hooks, so no other component mutates in a slot. The signal also carries a small menu-only
 * ContainerData vector (a crafter's disabled and triggered flags, a brewing stand's brew time and fuel), so a data-only
 * tick like a running brew with no slot movement re-stashes. Main-thread only, reset at bind and close so one open menu
 * never inherits another's snapshot.
 */
final class MenuChangeTracker {
    static final int[] NO_DATA = new int[0];

    private ItemStack @Nullable [] lastStacks;
    private int @Nullable [] lastCounts;
    // A slot with no bundle holds a null element (most slots), so this array's elements are nullable at
    // runtime; the reads below only ever reference-compare them.
    private BundleContents @Nullable [] lastBundles;
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
        BundleContents[] bundles = lastBundles;
        if (stacks == null || counts == null || bundles == null || stacks.length != slots.size()
                || page != lastPage || !Arrays.equals(data, lastData)) {
            snapshot(slots, page, data);
            return true;
        }
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = slots.get(i).getItem();
            if (current != stacks[i] || current.getCount() != counts[i]
                    || current.get(DataComponents.BUNDLE_CONTENTS) != bundles[i]) {
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
        BundleContents[] bundles = lastBundles;
        if (stacks == null || counts == null || bundles == null || stacks.length != slots.size()) {
            stacks = new ItemStack[slots.size()];
            counts = new int[slots.size()];
            bundles = new BundleContents[slots.size()];
            lastStacks = stacks;
            lastCounts = counts;
            lastBundles = bundles;
        }
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = slots.get(i).getItem();
            stacks[i] = current;
            counts[i] = current.getCount();
            bundles[i] = current.get(DataComponents.BUNDLE_CONTENTS);
        }
        lastData = data.clone();
        lastPage = page;
    }
}
