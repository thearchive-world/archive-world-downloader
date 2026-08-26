// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Arrays;
import java.util.List;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Tracks whether an open menu's slot contents (or the lectern's reading page) changed since the last stash, so the
 * per-tick last-seen-wins stash re-serializes only on a real change rather than every tick (a shulker-filled double
 * chest is around fifteen hundred item encodes per pass). The change signal is per-slot stack identity and count: a
 * client-side slot mutation either replaces the slot's stack object (a sync packet's fresh stack, a click prediction's
 * move or split) or changes its count (a prediction's grow or shrink). The signal also carries a small menu-only
 * ContainerData vector (a crafter's disabled and triggered flags, a brewing stand's brew time and fuel), so a data-only
 * tick like a running brew with no slot movement re-stashes. Main-thread only, reset at bind and close so one open menu
 * never inherits another's snapshot.
 */
final class MenuChangeTracker {
    static final int[] NO_DATA = new int[0];

    private ItemStack @Nullable [] lastStacks;
    private int @Nullable [] lastCounts;
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
        if (stacks == null || counts == null || stacks.length != slots.size()
                || page != lastPage || !Arrays.equals(data, lastData)) {
            snapshot(slots, page, data);
            return true;
        }
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = slots.get(i).getStack();
            if (current != stacks[i] || current.getCount() != counts[i]) {
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
        lastData = null;
        lastPage = 0;
    }

    private void snapshot(List<Slot> slots, int page, int[] data) {
        ItemStack[] stacks = lastStacks;
        int[] counts = lastCounts;
        if (stacks == null || counts == null || stacks.length != slots.size()) {
            stacks = new ItemStack[slots.size()];
            counts = new int[slots.size()];
            lastStacks = stacks;
            lastCounts = counts;
        }
        for (int i = 0; i < stacks.length; i++) {
            ItemStack current = slots.get(i).getStack();
            stacks[i] = current;
            counts[i] = current.getCount();
        }
        lastData = data.clone();
        lastPage = page;
    }
}
